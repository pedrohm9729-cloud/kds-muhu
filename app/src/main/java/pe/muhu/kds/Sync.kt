package pe.muhu.kds

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Envía las comandas despachadas a Google Sheets (Apps Script).
 * Cada comanda se guarda primero en una cola en disco; un hilo la envía cada 20 s.
 * Si no hay internet, la cola espera y se envía cuando vuelva: nunca se pierde nada.
 */
class Sync(private val dir: File) {
    private val lock = Any()
    private val archCola = File(dir, "sync_pendientes.jsonl")
    private val archConf = File(dir, "sync_config.json")

    @Volatile var url: String = ""; private set
    @Volatile var clave: String = ""; private set
    @Volatile var ultimoOk: Double? = null; private set
    @Volatile var error: String? = null; private set
    @Volatile var alCambiar: (() -> Unit)? = null

    companion object {
        /** Hoja de Muhu: viene configurada de fábrica, nadie tiene que escribirla. */
        const val URL_MUHU = "https://script.google.com/macros/s/AKfycbzVqCkVEyeqiSeQAn7VVvPFj3rFIvNwPiCSx1ekxI65kmRFm0yvaLU119u4V8I6eXYI/exec"
        const val CLAVE_MUHU = "b64b96a9cf53f9560fc12b88"
    }

    init {
        try {
            val c = JSONObject(archConf.readText())
            url = c.optString("url"); clave = c.optString("clave")
            ultimoOk = if (c.has("ultimo_ok")) c.optDouble("ultimo_ok") else null
        } catch (_: Exception) { }
        if (url.isBlank()) { url = URL_MUHU; clave = CLAVE_MUHU }
        if (!archConf.exists()) {
            guardarConf()
            // Primera vez: sube también lo que ya estaba guardado en la tablet (la hoja descarta duplicados)
            encolarHistorial(File(dir, "historial_comandas.csv"))
        }
    }

    val activo get() = url.startsWith("https://")

    fun pendientes(): Int = synchronized(lock) {
        if (!archCola.exists()) 0 else archCola.readLines().count { it.isNotBlank() }
    }

    fun configurar(nuevaUrl: String, nuevaClave: String): Boolean {
        val u = nuevaUrl.trim(); val k = nuevaClave.trim()
        if (u.isNotEmpty() && !u.startsWith("https://script.google.com/")) return false
        url = u; clave = k; error = null
        guardarConf()
        return true
    }

    private fun guardarConf() {
        try {
            archConf.writeText(JSONObject().apply {
                put("url", url); put("clave", clave); ultimoOk?.let { put("ultimo_ok", it) }
            }.toString())
        } catch (_: Exception) { }
    }

    fun encolar(fila: JSONObject) = synchronized(lock) {
        try { archCola.appendText(fila.toString() + "\n") } catch (_: Exception) { }
    }

    /** Encola todo el historial guardado en la tablet (la hoja descarta duplicados). */
    fun encolarHistorial(archHist: File): Int {
        if (!archHist.exists()) return 0
        var n = 0
        archHist.readLines(Charsets.UTF_8).drop(1).forEach { linea ->
            val r = linea.split(";")
            if (r.size < 11) return@forEach
            encolar(fila(r[0].toIntOrNull() ?: 0, r[1], r[2], r[3], r[4], r[5], r[6], r[7], r[8],
                r[9].toDoubleOrNull() ?: 0.0,
                r[10].split(" | ").mapNotNull { p -> Parser.ITEM.find(p.trim())?.let { it.groupValues[1] to it.groupValues[2] } },
                r.getOrNull(11)?.takeIf { Turnos.valido(it) } ?: Turnos.de(r[7]),
                r.getOrNull(12)?.toDoubleOrNull(), r.getOrNull(13)?.toDoubleOrNull()))
            n++
        }
        return n
    }

    fun fila(id: Int, fecha: String, mesa: String?, zona: String?, titulo: String?, enc: String?, mozo: String?,
             entro: String, listo: String, minutos: Double, prods: List<Pair<String, String>>,
             turno: String, mc: Double? = null, mb: Double? = null) = JSONObject().apply {
        put("id", id); put("fecha", fecha); put("mesa", mesa ?: ""); put("zona", zona ?: "")
        put("titulo", titulo ?: ""); put("encargado", enc ?: ""); put("mozo", mozo ?: "")
        put("turno", turno); put("entro", entro); put("listo", listo); put("minutos", Math.round(minutos * 10) / 10.0)
        put("min_cocina", mc?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
        put("min_barra", mb?.let { Math.round(it * 10) / 10.0 } ?: JSONObject.NULL)
        put("productos", JSONArray().apply { prods.forEach { put(JSONArray(listOf(it.first, it.second))) } })
    }

    fun iniciar() = thread(name = "sync", isDaemon = true) {
        while (true) {
            try { if (activo) enviarLote() } catch (_: Exception) { }
            Thread.sleep(20000)
        }
    }

    /** Pide un envío inmediato (por ejemplo al pulsar "Probar conexión"). */
    fun enviarAhora() = thread(isDaemon = true) { try { enviarLote() } catch (_: Exception) { } }

    private fun enviarLote() {
        if (!activo) return
        val lote = synchronized(lock) {
            if (!archCola.exists()) emptyList() else archCola.readLines().filter { it.isNotBlank() }.take(200)
        }
        val filas = JSONArray()
        lote.forEach { try { filas.put(JSONObject(it)) } catch (_: Exception) { } }
        val cuerpo = JSONObject().apply { put("clave", clave); put("filas", filas) }.toString()

        val respuesta = try { post(url, cuerpo) } catch (e: Exception) {
            marcarError("Sin conexión con Google Sheets (${e.javaClass.simpleName})"); return
        }
        val r = try { JSONObject(respuesta) } catch (_: Exception) { null }
        if (r == null || !r.optBoolean("ok")) {
            marcarError(when (r?.optString("error")) {
                "clave" -> "La clave no coincide con la de la hoja"
                null -> if (respuesta.contains("accounts.google.com") || respuesta.contains("<html", true))
                    "Google pide iniciar sesión: en Apps Script, el acceso debe ser \"Cualquier usuario\""
                    else "Respuesta inesperada. Revisa la URL de la aplicación web"
                else -> "Error de la hoja: ${r.optString("error")}"
            })
            return
        }
        synchronized(lock) {
            // Quita lo enviado; lo que llegó mientras tanto queda en la cola
            val actuales = if (archCola.exists()) archCola.readLines().filter { it.isNotBlank() } else emptyList()
            val resto = actuales.drop(lote.size)
            archCola.writeText(if (resto.isEmpty()) "" else resto.joinToString("\n", postfix = "\n"))
        }
        error = null
        ultimoOk = System.currentTimeMillis() / 1000.0
        guardarConf()
        alCambiar?.invoke()
    }

    private fun marcarError(m: String) {
        if (error != m) { error = m; alCambiar?.invoke() }
    }

    /** Apps Script responde con una redirección: el POST ya se ejecutó y la respuesta se lee con GET. */
    private fun post(destino: String, cuerpo: String): String {
        val c = (URL(destino).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false
            connectTimeout = 15000; readTimeout = 30000
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        c.outputStream.use { it.write(cuerpo.toByteArray(Charsets.UTF_8)) }
        val codigo = c.responseCode
        if (codigo in 300..399) {
            val loc = c.getHeaderField("Location") ?: throw IllegalStateException("sin Location")
            c.disconnect()
            val g = URL(loc).openConnection() as HttpURLConnection
            g.connectTimeout = 15000; g.readTimeout = 30000
            g.instanceFollowRedirects = true
            return g.inputStream.bufferedReader().use { it.readText() }
        }
        return (if (codigo in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
    }
}
