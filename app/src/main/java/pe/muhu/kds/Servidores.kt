package pe.muhu.kds

import android.util.Log
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

const val PUERTO_IMPRESORA = 9100
const val PUERTO_WEB = 8080
private const val TAG = "KDS"

/** Impresora virtual: recibe los tickets ESC/POS que el POS envía al puerto 9100. */
class ServidorImpresora(private val estado: Estado, private val capturas: File) {
    @Volatile private var srv: ServerSocket? = null

    fun iniciar() = thread(name = "impresora", isDaemon = true) {
        while (true) {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PUERTO_IMPRESORA)) }
                srv = s
                while (!s.isClosed) {
                    val cx = s.accept()
                    thread(isDaemon = true) { atender(cx) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "impresora: ${e.message}")
                Thread.sleep(2000)               // reintenta si el puerto estaba ocupado
            }
        }
    }

    fun detener() { try { srv?.close() } catch (_: Exception) { } }

    private fun atender(cx: Socket) {
        val buf = java.io.ByteArrayOutputStream()
        try {
            cx.soTimeout = 2000
            val inp = cx.getInputStream(); val out = cx.getOutputStream()
            val tmp = ByteArray(65536)
            while (true) {
                val n = try { inp.read(tmp) } catch (_: SocketTimeoutException) { -1 }
                if (n < 0) break
                buf.write(tmp, 0, n)
                for (k in 0 until n - 1) {                       // sondeos de estado
                    val a = tmp[k].toInt() and 0xFF; val b = tmp[k + 1].toInt() and 0xFF
                    if (a == 0x10 && b == 0x04) out.write(0x12)
                    else if (a == 0x1D && b == 0x72) out.write(0x00)
                }
                out.flush()
            }
        } catch (_: Exception) {
        } finally { try { cx.close() } catch (_: Exception) { } }

        val data = buf.toByteArray()
        if (data.size < 10) return                               // sondeo sin comanda
        try {
            capturas.mkdirs()
            val marca = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            File(capturas, "$marca.bin").writeBytes(data)
            // Conserva solo las últimas 300 capturas
            capturas.listFiles()?.sortedBy { it.name }?.dropLast(300)?.forEach { it.delete() }
        } catch (_: Exception) { }

        val texto = Parser.escposATexto(data)
        val p = Parser.parsear(texto)
        if (p.items.isEmpty() && p.cancelados.isEmpty()) {
            val lineas = texto.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lineas.isEmpty() || lineas.any { it.uppercase().contains("ANCHO") }) return  // prueba de Wally
            // Nunca perder una comanda: si no se entiende el formato, se muestra tal cual
            val cuerpo = lineas.filter { !Parser.SEP.matches(it) }.take(25)
            if (cuerpo.isEmpty()) p.items.add(Item("", "(comanda en imagen: ver impresora)"))
            else cuerpo.forEach { p.items.add(Item("", it)) }
            if (p.titulo == null) p.titulo = "Formato no reconocido: revisar"
        }
        estado.nuevaComanda(p)
    }
}

/** Servidor web local: pantallas de cocina, caja y tiempos + eventos en vivo. */
class ServidorWeb(private val estado: Estado, private val pagina: String) {
    @Volatile private var srv: ServerSocket? = null

    fun iniciar() = thread(name = "web", isDaemon = true) {
        while (true) {
            try {
                val s = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(PUERTO_WEB)) }
                srv = s
                while (!s.isClosed) {
                    val cx = s.accept()
                    thread(isDaemon = true) { atender(cx) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "web: ${e.message}")
                Thread.sleep(2000)
            }
        }
    }

    fun detener() { try { srv?.close() } catch (_: Exception) { } }

    private fun leerLinea(inp: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = inp.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return null
        }
    }

    private fun responder(out: OutputStream, codigo: Int, tipo: String, cuerpo: ByteArray, extra: String = "") {
        val est = when (codigo) { 200 -> "OK"; 400 -> "Bad Request"; else -> "Not Found" }
        out.write(("HTTP/1.1 $codigo $est\r\nContent-Type: $tipo\r\nContent-Length: ${cuerpo.size}\r\n" +
                "Cache-Control: no-store\r\nConnection: close\r\n$extra\r\n").toByteArray())
        out.write(cuerpo); out.flush()
    }

    private fun atender(cx: Socket) {
        try {
            cx.soTimeout = 15000
            val inp = BufferedInputStream(cx.getInputStream())
            val out = cx.getOutputStream()
            val inicio = leerLinea(inp) ?: return
            val partes = inicio.split(" ")
            if (partes.size < 2) return
            val metodo = partes[0]
            val ruta = partes[1].substringBefore("?").trimEnd('/').ifEmpty { "/" }
            var largo = 0
            while (true) {
                val h = leerLinea(inp) ?: break
                if (h.isEmpty()) break
                if (h.lowercase().startsWith("content-length:")) largo = h.substringAfter(":").trim().toIntOrNull() ?: 0
            }
            val html = "text/html; charset=utf-8"
            when {
                metodo == "GET" && (ruta == "/" || ruta == "/cocina") ->
                    responder(out, 200, html, pagina.replace("__MODO__", "cocina").toByteArray())
                metodo == "GET" && ruta == "/caja" ->
                    responder(out, 200, html, pagina.replace("__MODO__", "caja").toByteArray())
                metodo == "GET" && ruta == "/tiempos" ->
                    responder(out, 200, html, pagina.replace("__MODO__", "tiempos").toByteArray())
                metodo == "GET" && ruta == "/historial.csv" -> {
                    val f = estado.archHist
                    if (f.exists()) responder(out, 200, "text/csv; charset=utf-8", f.readBytes(),
                        "Content-Disposition: attachment; filename=\"historial_kds.csv\"\r\n")
                    else responder(out, 404, "text/plain; charset=utf-8", "Aún no hay historial".toByteArray())
                }
                metodo == "GET" && ruta == "/eventos" -> eventos(cx, out)
                metodo == "GET" && ruta == "/reporte.pdf" -> {
                    val dia = Regex("fecha=(\\d{4}-\\d{2}-\\d{2})").find(partes[1])?.groupValues?.get(1) ?: estado.hoy()
                    responder(out, 200, "application/pdf", Reporte.generar(estado, dia),
                        "Content-Disposition: attachment; filename=\"reporte_cocina_$dia.pdf\"\r\n")
                }
                metodo == "POST" && ruta == "/accion" -> {
                    val cuerpo = ByteArray(largo.coerceIn(0, 65536))
                    var leido = 0
                    while (leido < cuerpo.size) {
                        val n = inp.read(cuerpo, leido, cuerpo.size - leido); if (n < 0) break; leido += n
                    }
                    val d = try { JSONObject(String(cuerpo, 0, leido, Charsets.UTF_8)) } catch (_: Exception) { JSONObject() }
                    val ok = if (d.optString("accion") == "simular") { estado.nuevaComanda(comandaPrueba()); true }
                    else estado.accion(d.optString("accion"),
                        if (d.has("id") && !d.isNull("id")) d.optInt("id") else null,
                        if (d.has("idx") && !d.isNull("idx")) d.optInt("idx") else null,
                        if (d.isNull("nombre")) null else d.optString("nombre"),
                        if (d.has("verde")) d.optInt("verde") else null,
                        if (d.has("ambar")) d.optInt("ambar") else null)
                    responder(out, if (ok) 200 else 400, "application/json", "{\"ok\": $ok}".toByteArray())
                }
                else -> responder(out, 404, "text/plain; charset=utf-8", "No encontrado".toByteArray())
            }
        } catch (_: Exception) {
        } finally { try { cx.close() } catch (_: Exception) { } }
    }

    private fun eventos(cx: Socket, out: OutputStream) {
        cx.soTimeout = 0
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-store\r\n" +
                "Connection: keep-alive\r\n\r\n").toByteArray())
        val q = LinkedBlockingQueue<String>(50)
        estado.clientes.add(q)
        try {
            out.write("data: ${estado.snapshot()}\n\n".toByteArray(Charsets.UTF_8)); out.flush()
            while (true) {
                val msg = q.poll(15, TimeUnit.SECONDS)
                out.write((if (msg != null) "data: $msg\n\n" else ": ping\n\n").toByteArray(Charsets.UTF_8))
                out.flush()
            }
        } catch (_: Exception) {
        } finally { estado.clientes.remove(q) }
    }

    private fun comandaPrueba(): Parseada {
        val carta = listOf("Capuccino" to listOf("Tipo de Leche: Avena"), "Latte" to listOf(),
            "Americano" to listOf("Azucar bebida: sin azúcar"), "Croissant de jamón y queso" to listOf(),
            "Torta de chocolate con fudge" to listOf("Helado: Bola de Helado"),
            "Jugo de mango c/ leche" to listOf("Tipo de Leche: Almendra"), "Sándwich de pollo" to listOf())
        val p = Parseada(mesa = (1..12).random().toString(), zona = listOf("Salón", "Terraza").random(),
            titulo = listOf(null, null, "PRUEBA").random(), mozo = listOf("Prueba").random(),
            hora = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()))
        carta.shuffled().take((1..3).random()).forEach { (n, m) ->
            p.items.add(Item(listOf("1", "1", "2").random(), n, m.toMutableList()))
        }
        return p
    }
}
