package pe.muhu.kds

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

class Comanda(
    val id: Int,
    var mesa: String?, var zona: String?, var titulo: String?, var mozo: String?, var hora: String?,
    val items: MutableList<Item>,
    var estado: String = "abierta",
    var recibida: Double, var actualizada: Double, var lista: Double? = null,
)

class Hist(
    val id: Int, val mesa: String?, val mozo: String?, val enc: String?,
    val entro: Double, val listo: Double, val min: Double, val prods: List<Pair<String, String>>,
)

/** Estado del KDS: comandas, turno y tiempos. Se guarda en disco y se difunde a las pantallas. */
class Estado(private val dir: File) {
    private val lock = Any()
    private val archEstado = File(dir, "kds_estado.json")
    val archHist = File(dir, "historial_comandas.csv")
    private val cols = listOf("id", "fecha", "mesa", "zona", "titulo", "encargado", "mozo",
        "recibida", "lista", "minutos", "productos")

    val comandas = mutableListOf<Comanda>()
    private val hist = mutableListOf<Hist>()
    private var seq = 0
    private var ultimaDespachada: Int? = null
    var encargado: String? = null; private set
    private var turnoDesde: Double? = null
    var verde = 6; private set
    var ambar = 10; private set
    var ip: String = "-"

    val clientes = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()

    private val fFecha = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fHora = SimpleDateFormat("HH:mm:ss", Locale.US)

    init { cargar(); cargarHistHoy() }

    private fun ahora() = System.currentTimeMillis() / 1000.0

    // ------------------------------------------------------------- JSON
    private fun Any?.j(): Any = this ?: JSONObject.NULL

    private fun itemJ(it: Item) = JSONObject().apply {
        put("cant", it.cant); put("producto", it.producto); put("mods", JSONArray(it.mods))
        put("hecho", it.hecho); put("cancelado", it.cancelado); put("marca", it.marca.j())
    }

    private fun comandaJ(c: Comanda) = JSONObject().apply {
        put("id", c.id); put("mesa", c.mesa.j()); put("zona", c.zona.j()); put("titulo", c.titulo.j())
        put("mozo", c.mozo.j()); put("hora", c.hora.j()); put("estado", c.estado)
        put("items", JSONArray().apply { c.items.forEach { put(itemJ(it)) } })
        put("recibida_ts", c.recibida); put("actualizada_ts", c.actualizada); put("lista_ts", c.lista.j())
    }

    private fun histJ(h: Hist) = JSONObject().apply {
        put("id", h.id); put("mesa", h.mesa.j()); put("mozo", h.mozo.j()); put("enc", h.enc.j())
        put("entro", h.entro); put("listo", h.listo); put("min", h.min)
        put("prods", JSONArray().apply { h.prods.forEach { put(JSONArray(listOf(it.first, it.second))) } })
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

    private fun itemDe(o: JSONObject) = Item(o.optString("cant"), o.optString("producto"),
        MutableList(o.optJSONArray("mods")?.length() ?: 0) { o.getJSONArray("mods").getString(it) },
        o.optBoolean("hecho"), o.optBoolean("cancelado"), o.str("marca"))

    // -------------------------------------------------------- persistencia
    private fun cargar() {
        try {
            val d = JSONObject(archEstado.readText())
            seq = d.optInt("seq")
            encargado = d.str("encargado")
            turnoDesde = if (d.isNull("turno_desde")) null else d.optDouble("turno_desde")
            verde = d.optInt("verde", 6); ambar = d.optInt("ambar", 10)
            val arr = d.optJSONArray("comandas") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val its = o.getJSONArray("items")
                comandas.add(Comanda(o.getInt("id"), o.str("mesa"), o.str("zona"), o.str("titulo"),
                    o.str("mozo"), o.str("hora"), MutableList(its.length()) { itemDe(its.getJSONObject(it)) },
                    o.optString("estado", "abierta"), o.getDouble("recibida_ts"), o.getDouble("actualizada_ts"),
                    if (o.isNull("lista_ts")) null else o.getDouble("lista_ts")))
            }
        } catch (_: Exception) { }
    }

    private fun guardar() {
        try {
            val d = JSONObject().apply {
                put("seq", seq); put("encargado", encargado.j()); put("turno_desde", turnoDesde.j())
                put("verde", verde); put("ambar", ambar)
                put("comandas", JSONArray().apply { comandas.forEach { put(comandaJ(it)) } })
            }
            val tmp = File(dir, "kds_estado.json.tmp")
            tmp.writeText(d.toString())
            tmp.renameTo(archEstado)
        } catch (_: Exception) { }
    }

    private fun fecha(t: Double) = fFecha.format(Date((t * 1000).toLong()))
    private fun hhmmss(t: Double) = fHora.format(Date((t * 1000).toLong()))

    private fun cargarHistHoy() {
        try {
            if (!archHist.exists()) return
            val filas = archHist.readLines(Charsets.UTF_8)
            if (filas.isEmpty() || filas[0].trimStart('﻿').split(";") != cols) {
                archHist.renameTo(File(dir, "historial_anterior.csv")); return
            }
            val hoy = fecha(ahora())
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            for (fila in filas.drop(1)) {
                val r = fila.split(";")
                if (r.size < 11 || r[1] != hoy) continue
                val ent = (f.parse("${r[1]} ${r[7]}")?.time ?: continue) / 1000.0
                val lis = (f.parse("${r[1]} ${r[8]}")?.time ?: continue) / 1000.0
                val prods = r[10].split(" | ").mapNotNull { p ->
                    Parser.ITEM.find(p.trim())?.let { it.groupValues[1] to it.groupValues[2] }
                }
                hist.add(Hist(r[0].toIntOrNull() ?: 0, r[2], r[6], r[5].ifEmpty { null }, ent, lis,
                    r[9].toDoubleOrNull() ?: 0.0, prods))
            }
        } catch (_: Exception) { }
    }

    private fun csv(s: String?) = (s ?: "").replace(";", ",").replace("\n", " ")

    private fun registrarHist(c: Comanda) {
        val lista = c.lista ?: return
        val prods = c.items.filter { !it.cancelado }.map { it.cant to it.producto }
        val mins = (lista - c.recibida) / 60
        hist.add(Hist(c.id, c.mesa, c.mozo, encargado, c.recibida, lista, mins, prods))
        try {
            val nuevo = !archHist.exists()
            val linea = listOf(c.id.toString(), fecha(c.recibida), csv(c.mesa), csv(c.zona), csv(c.titulo),
                csv(encargado), csv(c.mozo), hhmmss(c.recibida), hhmmss(lista),
                String.format(Locale.US, "%.1f", mins),
                csv(prods.joinToString(" | ") { "${it.first} ${it.second}" })).joinToString(";")
            archHist.appendText((if (nuevo) "﻿" + cols.joinToString(";") + "\n" else "") + linea + "\n")
        } catch (_: Exception) { }
    }

    // ------------------------------------------------------------- difusión
    fun snapshot(evento: String = "estado"): String = synchronized(lock) {
        val hoy0 = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis / 1000.0
        JSONObject().apply {
            put("evento", evento); put("ahora", ahora()); put("verde", verde); put("ambar", ambar)
            put("encargado", encargado.j()); put("turno_desde", turnoDesde.j()); put("ip", ip)
            put("comandas", JSONArray().apply { comandas.forEach { put(comandaJ(it)) } })
            put("hist", JSONArray().apply { hist.filter { it.listo >= hoy0 }.forEach { put(histJ(it)) } })
        }.toString()
    }

    private fun cambio(evento: String) {
        guardar()
        val msg = snapshot(evento)
        clientes.forEach { it.offer(msg) }
    }

    // --------------------------------------------------------------- lógica
    fun nuevaComanda(p: Parseada): Int = synchronized(lock) {
        val t = ahora()
        val abierta = if (p.mesa != null) comandas.firstOrNull {
            it.estado == "abierta" && it.mesa == p.mesa && it.zona == p.zona
        } else null
        if (abierta != null && (p.items.isNotEmpty() || p.cancelados.isNotEmpty())) {
            for (cx in p.cancelados) {
                val cand = abierta.items.filter { !it.cancelado && it.producto == cx.producto }
                val obj = cand.firstOrNull { it.mods == cx.mods } ?: cand.firstOrNull()
                if (obj != null) obj.cancelado = true
                else abierta.items.add(cx.also { it.cancelado = true })
            }
            val marca = if (p.cancelados.isNotEmpty()) "cambio" else "nuevo"
            p.items.forEach { abierta.items.add(it.also { i -> i.marca = marca }) }
            abierta.actualizada = t
            cambio(if (p.cancelados.isNotEmpty() && p.items.isEmpty()) "anulacion" else "cambio")
            return abierta.id
        }
        seq += 1
        val c = Comanda(seq, p.mesa, p.zona, p.titulo, p.mozo, p.hora,
            (p.items + p.cancelados.onEach { it.cancelado = true }).toMutableList(),
            recibida = t, actualizada = t)
        comandas.add(c)
        cambio(if (p.items.isNotEmpty()) "nueva" else "anulacion")
        c.id
    }

    fun accion(a: String, id: Int?, idx: Int?, nombre: String?, v: Int?, am: Int?): Boolean = synchronized(lock) {
        val c = comandas.firstOrNull { it.id == id }
        val evento: String
        when {
            a == "turno" -> {
                val n = (nombre ?: "").replace(Regex("\\s+"), " ").trim().take(24)
                if (n.isEmpty()) return false
                if (n != encargado) { encargado = n; turnoDesde = ahora() }
                evento = "turno"
            }
            a == "config" -> {
                val nv = v ?: return false; val na = am ?: return false
                if (nv < 1 || na <= nv || na > 120) return false
                verde = nv; ambar = na; evento = "estado"
            }
            a == "item" && c != null && idx != null && idx in c.items.indices -> {
                c.items[idx].hecho = !c.items[idx].hecho; evento = "estado"
            }
            a == "listo" && c != null && c.estado == "abierta" -> {
                c.estado = "lista"; c.lista = ahora(); ultimaDespachada = c.id
                registrarHist(c); evento = "listo"
            }
            a == "entregado" && c != null -> { comandas.remove(c); evento = "estado" }
            a == "recuperar" && ultimaDespachada != null -> {
                val u = comandas.firstOrNull { it.id == ultimaDespachada }
                if (u != null && u.estado == "lista") {
                    u.estado = "abierta"; u.lista = null
                    hist.removeAll { it.id == u.id }
                }
                ultimaDespachada = null; evento = "estado"
            }
            else -> return false
        }
        // Las despachadas hace más de 30 min salen de pantalla
        val lim = ahora() - 1800
        comandas.removeAll { it.estado == "lista" && (it.lista ?: 0.0) < lim }
        cambio(evento)
        true
    }
}
