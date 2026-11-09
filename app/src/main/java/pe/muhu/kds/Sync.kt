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

    private val archFallidas = File(dir, "sync_fallidas.jsonl")

    /** Resultado de un envío: ok, o el motivo del error. */
    private class Res(val ok: Boolean, val error: String? = null, val global: Boolean = false)

    private fun enviar(filas: List<String>): Res {
        val arr = JSONArray()
        filas.forEach { try { arr.put(JSONObject(it)) } catch (_: Exception) { } }
        val cuerpo = JSONObject().apply { put("clave", clave); put("filas", arr) }.toString()
        val respuesta = try { post(url, cuerpo) } catch (e: Exception) {
            return Res(false, "Sin conexión con Google Sheets (${e.javaClass.simpleName})", global = true)
        }
        val r = try { JSONObject(respuesta) } catch (_: Exception) { null }
        if (r != null && r.optBoolean("ok")) return Res(true)
        if (r != null && r.optString("error") == "clave") return Res(false, "La clave no coincide con la de la hoja", global = true)
        if (r != null) return Res(false, "Error de la hoja: ${r.optString("error")}")
        try { File(dir, "sync_ultima_respuesta.html").writeText(respuesta) } catch (_: Exception) { }
        if (respuesta.contains("accounts.google.com/v3/signin") || respuesta.contains("ServiceLogin"))
            return Res(false, "Google pide iniciar sesión: en Apps Script, el acceso debe ser \"Cualquier usuario\"", global = true)
        // Página de error de Apps Script: el mensaje viene en un <div> monoespaciado
        val texto = (Regex("monospace[^>]*>([^<]+)</div>").findAll(respuesta).lastOrNull()?.groupValues?.get(1)
            ?: Regex("<title>(.*?)</title>", RegexOption.IGNORE_CASE).find(respuesta)?.groupValues?.get(1) ?: "página inesperada")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
        return Res(false, "Google respondió un error: ${texto.trim().take(180)}")
    }

    private fun quitarDeCola(enviadas: Collection<String>) = synchronized(lock) {
        val actuales = if (archCola.exists()) archCola.readLines().filter { it.isNotBlank() }.toMutableList() else mutableListOf()
        enviadas.forEach { actuales.remove(it) }
        archCola.writeText(if (actuales.isEmpty()) "" else actuales.joinToString("\n", postfix = "\n"))
    }

    private fun enviarLote() {
        if (!activo) return
        val lote = synchronized(lock) {
            if (!archCola.exists()) emptyList() else archCola.readLines().filter { it.isNotBlank() }.take(50)
        }
        if (lote.isEmpty()) return
        val r = enviar(lote)
        if (r.ok) { quitarDeCola(lote); exito(); return }
        if (r.global) { marcarError(r.error ?: "Error"); return }

        // Algo de este lote hace fallar la hoja: se prueba de a una para que una comanda mala no bloquee las demás
        val buenas = mutableListOf<String>(); val malas = mutableListOf<String>()
        var ultimoError = r.error
        for (f in lote) {
            val x = enviar(listOf(f))
            if (x.ok) buenas += f
            else { if (x.global) { marcarError(x.error ?: "Error"); break }; malas += f; ultimoError = x.error }
        }
        if (buenas.isNotEmpty()) {
            quitarDeCola(buenas)
            // Solo se apartan las que fallan cuando otras sí pasaron (el problema es de esa comanda, no de la hoja)
            if (malas.isNotEmpty()) {
                synchronized(lock) { try { archFallidas.appendText(malas.joinToString("\n", postfix = "\n")) } catch (_: Exception) { } }
                quitarDeCola(malas)
            }
            exito()
        } else marcarError(ultimoError ?: "Error")
    }

    private fun exito() {
        error = null
        ultimoOk = System.currentTimeMillis() / 1000.0
        guardarConf()
        alCambiar?.invoke()
    }

    fun fallidas(): Int = synchronized(lock) { if (!archFallidas.exists()) 0 else archFallidas.readLines().count { it.isNotBlank() } }

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
