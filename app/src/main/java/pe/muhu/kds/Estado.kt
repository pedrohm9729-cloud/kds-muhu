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
    val turno: String = "AM",
)

/** Turnos de Muhu. AM: 07:00 a 14:30 · PM: 14:30 a 23:00. */
object Turnos {
    const val CAMBIO = "14:30"
    val horario = mapOf("AM" to ("07:00" to "14:30"), "PM" to ("14:30" to "23:00"))
    /** Turno que corresponde a una hora "HH:mm" o "HH:mm:ss". */
    fun de(hora: String) = if (hora.take(5) < CAMBIO) "AM" else "PM"
    fun valido(t: String?) = t == "AM" || t == "PM"
}

/** Estado del KDS: comandas, turno y tiempos. Se guarda en disco y se difunde a las pantallas. */
class Estado(private val dir: File) {
    private val lock = Any()
    private val archEstado = File(dir, "kds_estado.json")
    val archHist = File(dir, "historial_comandas.csv")
    private val cols = listOf("id", "fecha", "mesa", "zona", "titulo", "encargado", "mozo",
        "recibida", "lista", "minutos", "productos", "turno")
    private val colsV1 = cols.dropLast(1)

    val comandas = mutableListOf<Comanda>()
    private val hist = mutableListOf<Hist>()
    private var seq = 0
    private var ultimaDespachada: Int? = null
    var encargado: String? = null; private set
    private var turnoDesde: Double? = null
    var turno: String? = null; private set
    var verde = 6; private set
    var ambar = 10; private set
    var ip: String = "-"

    val clientes = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()
    /** Aviso al servicio para sonar en la tablet aunque la pantalla se esté recargando. */
    @Volatile var alEvento: ((String) -> Unit)? = null

    private val fFecha = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fHora = SimpleDateFormat("HH:mm:ss", Locale.US)

    val sync = Sync(dir)

    init {
        cargar(); cargarHistHoy()
        sync.alCambiar = { synchronized(lock) { cambio("estado") } }
    }

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
        put("turno", h.turno)
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
            turno = d.optString("turno").takeIf { Turnos.valido(it) }
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
                put("turno", turno.j())
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

    fun hoy(): String = fecha(ahora())

    /** Comandas despachadas en un día (yyyy-MM-dd), leídas del historial. Si una se recuperó y
     *  volvió a despachar, cuenta la última vez. */
    fun histDe(dia: String): List<Hist> {
        val porId = LinkedHashMap<Int, Hist>()
        try {
            if (!archHist.exists()) return emptyList()
            val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            for (fila in archHist.readLines(Charsets.UTF_8).drop(1)) {
                val r = fila.split(";")
                if (r.size < 11 || r[1] != dia) continue
                val ent = (f.parse("${r[1]} ${r[7]}")?.time ?: continue) / 1000.0
                val lis = (f.parse("${r[1]} ${r[8]}")?.time ?: continue) / 1000.0
                val prods = r[10].split(" | ").mapNotNull { p ->
                    Parser.ITEM.find(p.trim())?.let { it.groupValues[1] to it.groupValues[2] }
                }
                val id = r[0].toIntOrNull() ?: 0
                porId.remove(id)
                val tu = r.getOrNull(11)?.takeIf { Turnos.valido(it) } ?: Turnos.de(r[7])
                porId[id] = Hist(id, r[2].ifEmpty { null }, r[6].ifEmpty { null }, r[5].ifEmpty { null },
                    ent, lis, r[9].toDoubleOrNull() ?: 0.0, prods, tu)
            }
        } catch (_: Exception) { }
        return porId.values.toList()
    }

    private fun cargarHistHoy() {
        try {
            if (!archHist.exists()) return
            val cab = archHist.bufferedReader(Charsets.UTF_8).use { it.readLine() } ?: return
            val c0 = cab.trimStart('\uFEFF').split(";")
            if (c0 == colsV1) {
                // Versión anterior sin columna de turno: se agrega según la hora de entrada
                val filas = archHist.readLines(Charsets.UTF_8).drop(1).filter { it.isNotBlank() }
                    .map { l -> val r = l.split(";"); l + ";" + (if (r.size > 7) Turnos.de(r[7]) else "AM") }
                archHist.writeText("\uFEFF" + cols.joinToString(";") + "\n" +
                    filas.joinToString("") { it + "\n" }, Charsets.UTF_8)
            } else if (c0 != cols) {
                archHist.renameTo(File(dir, "historial_anterior.csv")); return
            }
            hist.addAll(histDe(fecha(ahora())))
        } catch (_: Exception) { }
    }

    private fun csv(s: String?) = (s ?: "").replace(";", ",").replace("\n", " ")

    private fun registrarHist(c: Comanda) {
        val lista = c.lista ?: return
        val prods = c.items.filter { !it.cancelado }.map { it.cant to it.producto }
        val mins = (lista - c.recibida) / 60
        val tu = turno ?: Turnos.de(hhmmss(c.recibida))
        hist.add(Hist(c.id, c.mesa, c.mozo, encargado, c.recibida, lista, mins, prods, tu))
        sync.encolar(sync.fila(c.id, fecha(c.recibida), c.mesa, c.zona, c.titulo, encargado, c.mozo,
            hhmmss(c.recibida), hhmmss(lista), mins, prods, tu))
        try {
            val nuevo = !archHist.exists()
            val linea = listOf(c.id.toString(), fecha(c.recibida), csv(c.mesa), csv(c.zona), csv(c.titulo),
                csv(encargado), csv(c.mozo), hhmmss(c.recibida), hhmmss(lista),
                String.format(Locale.US, "%.1f", mins),
                csv(prods.joinToString(" | ") { "${it.first} ${it.second}" }), tu).joinToString(";")
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
            put("turno", turno.j())
            put("turnos", JSONObject().apply {
                Turnos.horario.forEach { (k, v) -> put(k, JSONArray(listOf(v.first, v.second))) }
            })
            put("sync", JSONObject().apply {
                put("activo", sync.activo); put("pendientes", sync.pendientes())
                put("ultimo_ok", sync.ultimoOk.j()); put("error", sync.error.j())
            })
            put("comandas", JSONArray().apply { comandas.forEach { put(comandaJ(it)) } })
            put("hist", JSONArray().apply { hist.filter { it.listo >= hoy0 }.forEach { put(histJ(it)) } })
        }.toString()
    }

    private fun cambio(evento: String) {
        guardar()
        val msg = snapshot(evento)
        clientes.forEach { it.offer(msg) }
        try { alEvento?.invoke(evento) } catch (_: Exception) { }
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

    fun accion(a: String, id: Int?, idx: Int?, nombre: String?, v: Int?, am: Int?,
               url: String? = null, clave: String? = null, turnoSel: String? = null): Boolean = synchronized(lock) {
        val c = comandas.firstOrNull { it.id == id }
        val evento: String
        when {
            a == "turno" -> {
                val n = (nombre ?: "").replace(Regex("\\s+"), " ").trim().take(24)
                if (n.isEmpty()) return false
                val tu = turnoSel?.takeIf { Turnos.valido(it) } ?: Turnos.de(hhmmss(ahora()))
                if (n != encargado || tu != turno) { encargado = n; turno = tu; turnoDesde = ahora() }
                evento = "turno"
            }
            a == "sync_config" -> {
                if (!sync.configurar(url ?: "", clave ?: "")) return false
                if (sync.activo) { sync.encolarHistorial(archHist); sync.enviarAhora() }
                evento = "estado"
            }
            a == "sync_ahora" -> { sync.enviarAhora(); evento = "estado" }
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
