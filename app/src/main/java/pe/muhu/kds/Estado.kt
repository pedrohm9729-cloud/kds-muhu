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
    /** Hora en que cada estación terminó su parte: {"cocina": ts, "barra": ts} */
    val listas: MutableMap<String, Double> = mutableMapOf(),
    /** Pedido extra de una mesa que ya tenía comanda (llegó pasados los 2 min). */
    val adicional: Boolean = false,
)

class Hist(
    val id: Int, val mesa: String?, val mozo: String?, val enc: String?,
    val entro: Double, val listo: Double, val min: Double, val prods: List<Pair<String, String>>,
    val turno: String = "AM",
    /** Minutos de cocina y de barra (null si esa estación no tenía productos). */
    val mc: Double? = null, val mb: Double? = null,
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
        "recibida", "lista", "minutos", "productos", "turno", "min_cocina", "min_barra")
    private val colsV2 = cols.dropLast(2)
    private val colsV1 = cols.dropLast(3)

    val comandas = mutableListOf<Comanda>()
    private val hist = mutableListOf<Hist>()
    private var seq = 0
    var encargado: String? = null; private set
    private var turnoDesde: Double? = null
    var turno: String? = null; private set
    /** Palabras que se pintan de color en la cocina: [{"p":"helado","c":"azul"}] */
    var palabras: JSONArray = JSONArray("""[{"p":"helado","c":"azul"},{"p":"frapp","c":"celeste"},{"p":"alergi","c":"rojo"}]"""); private set
    /** Barra y cocina separadas. Si está apagado, todo va a cocina como antes. */
    @Volatile var estaciones = false; private set
    /** Productos que prepara la barra (inicio de palabra, sin tildes). */
    var barraPal: JSONArray = JSONArray(listOf("cafe", "espresso", "americano", "capuccino", "cappuccino", "latte",
        "flat white", "mocca", "macchiato", "cortado", "frapp", "jugo", "limonada", "te", "infusion",
        "chocolate caliente", "smoothie", "batido", "gaseosa", "chicha", "emoliente")); private set
    var verde = 6; private set
    var ambar = 10; private set
    var ip: String = "-"
    private var afecta: Set<String> = emptySet()

    val clientes = CopyOnWriteArrayList<LinkedBlockingQueue<String>>()
    /** Aviso al servicio para sonar en la tablet: (evento, estaciones afectadas). */
    @Volatile var alEvento: ((String, Set<String>) -> Unit)? = null

    private val fFecha = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fHora = SimpleDateFormat("HH:mm:ss", Locale.US)

    val sync = Sync(dir)

    init {
        cargar(); cargarHistHoy()
        sync.alCambiar = { synchronized(lock) { cambio("estado") } }
    }

    private fun ahora() = System.currentTimeMillis() / 1000.0

    // ------------------------------------------------------------- estaciones
    private fun norm(s: String) = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)

    /** ¿A qué estación va un producto? Coincide al inicio de una palabra; las reglas cortas, palabra completa. */
    fun estacionDe(producto: String): String {
        if (!estaciones) return "cocina"
        val n = norm(producto)
        for (k in 0 until barraPal.length()) {
            val r = norm(barraPal.optString(k)).trim()
            if (r.isEmpty()) continue
            val fin = if (r.length <= 3) "(?![\\p{L}\\p{N}])" else ""
            if (Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(r) + fin).containsMatchIn(n)) return "barra"
        }
        return "cocina"
    }

    /** Estaciones con productos vigentes en la comanda. */
    private fun estacionesDe(c: Comanda): Set<String> {
        val s = c.items.filter { !it.cancelado }.map { it.est }.toSet()
        return if (s.isEmpty()) setOf("cocina") else s
    }

    // ------------------------------------------------------------- JSON
    private fun Any?.j(): Any = this ?: JSONObject.NULL

    private fun itemJ(it: Item) = JSONObject().apply {
        put("cant", it.cant); put("producto", it.producto); put("mods", JSONArray(it.mods))
        put("hecho", it.hecho); put("cancelado", it.cancelado); put("marca", it.marca.j()); put("est", it.est)
    }

    private fun comandaJ(c: Comanda) = JSONObject().apply {
        put("id", c.id); put("mesa", c.mesa.j()); put("zona", c.zona.j()); put("titulo", c.titulo.j())
        put("mozo", c.mozo.j()); put("hora", c.hora.j()); put("estado", c.estado)
        put("items", JSONArray().apply { c.items.forEach { put(itemJ(it)) } })
        put("recibida_ts", c.recibida); put("actualizada_ts", c.actualizada); put("lista_ts", c.lista.j())
        put("listas", JSONObject().apply { c.listas.forEach { (k, v) -> put(k, v) } })
        put("adicional", c.adicional)
    }

    private fun histJ(h: Hist) = JSONObject().apply {
        put("id", h.id); put("mesa", h.mesa.j()); put("mozo", h.mozo.j()); put("enc", h.enc.j())
        put("turno", h.turno)
        put("entro", h.entro); put("listo", h.listo); put("min", h.min)
        put("mc", h.mc.j()); put("mb", h.mb.j())
        put("prods", JSONArray().apply { h.prods.forEach { put(JSONArray(listOf(it.first, it.second))) } })
    }

    private fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k)

    private fun itemDe(o: JSONObject) = Item(o.optString("cant"), o.optString("producto"),
        MutableList(o.optJSONArray("mods")?.length() ?: 0) { o.getJSONArray("mods").getString(it) },
        o.optBoolean("hecho"), o.optBoolean("cancelado"), o.str("marca"), o.optString("est", "cocina"))

    // -------------------------------------------------------- persistencia
    private fun cargar() {
        try {
            val d = JSONObject(archEstado.readText())
            seq = d.optInt("seq")
            encargado = d.str("encargado")
            turnoDesde = if (d.isNull("turno_desde")) null else d.optDouble("turno_desde")
            turno = d.optString("turno").takeIf { Turnos.valido(it) }
            verde = d.optInt("verde", 6); ambar = d.optInt("ambar", 10)
            d.optJSONArray("palabras")?.let { palabras = it }
            estaciones = d.optBoolean("estaciones", false)
            d.optJSONArray("barra")?.let { barraPal = it }
            val arr = d.optJSONArray("comandas") ?: JSONArray()
            for (k in 0 until arr.length()) {
                val o = arr.getJSONObject(k)
                val its = o.getJSONArray("items")
                val c = Comanda(o.getInt("id"), o.str("mesa"), o.str("zona"), o.str("titulo"),
                    o.str("mozo"), o.str("hora"), MutableList(its.length()) { itemDe(its.getJSONObject(it)) },
                    o.optString("estado", "abierta"), o.getDouble("recibida_ts"), o.getDouble("actualizada_ts"),
                    if (o.isNull("lista_ts")) null else o.getDouble("lista_ts"), adicional = o.optBoolean("adicional"))
                o.optJSONObject("listas")?.let { l -> l.keys().forEach { c.listas[it] = l.getDouble(it) } }
                comandas.add(c)
            }
            comandas.forEach { cerrarSiAnulada(it) }      // comandas trabadas de versiones anteriores
        } catch (_: Exception) { }
    }

    private fun guardar() {
        try {
            val d = JSONObject().apply {
                put("seq", seq); put("encargado", encargado.j()); put("turno_desde", turnoDesde.j())
                put("turno", turno.j())
                put("verde", verde); put("ambar", ambar); put("palabras", palabras)
                put("estaciones", estaciones); put("barra", barraPal)
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
                    ent, lis, r[9].toDoubleOrNull() ?: 0.0, prods, tu,
                    r.getOrNull(12)?.toDoubleOrNull(), r.getOrNull(13)?.toDoubleOrNull())
            }
        } catch (_: Exception) { }
        return porId.values.toList()
    }

    private fun cargarHistHoy() {
        try {
            if (!archHist.exists()) return
            val cab = archHist.bufferedReader(Charsets.UTF_8).use { it.readLine() } ?: return
            val c0 = cab.trimStart('﻿').split(";")
            if (c0 == colsV1 || c0 == colsV2) {
                // Versiones anteriores: se agrega el turno (según la hora de entrada) y las columnas de estación
                val filas = archHist.readLines(Charsets.UTF_8).drop(1).filter { it.isNotBlank() }.map { l ->
                    val r = l.split(";")
                    if (c0 == colsV1) l + ";" + (if (r.size > 7) Turnos.de(r[7]) else "AM") + ";;" else "$l;;"
                }
                archHist.writeText("﻿" + cols.joinToString(";") + "\n" +
                    filas.joinToString("") { it + "\n" }, Charsets.UTF_8)
            } else if (c0 != cols) {
                archHist.renameTo(File(dir, "historial_anterior.csv")); return
            }
            hist.addAll(histDe(fecha(ahora())))
        } catch (_: Exception) { }
    }

    private fun csv(s: String?) = (s ?: "").replace(";", ",").replace("\n", " ")
    private fun f1(x: Double?) = if (x == null) "" else String.format(Locale.US, "%.1f", x)

    private fun registrarHist(c: Comanda) {
        val lista = c.lista ?: return
        val prods = c.items.filter { !it.cancelado }.map { it.cant to it.producto }
        val mins = (lista - c.recibida) / 60
        val sts = estacionesDe(c)
        val mc = if ("cocina" in sts) ((c.listas["cocina"] ?: lista) - c.recibida) / 60 else null
        val mb = if ("barra" in sts) ((c.listas["barra"] ?: lista) - c.recibida) / 60 else null
        val tu = turno ?: Turnos.de(hhmmss(c.recibida))
        hist.add(Hist(c.id, c.mesa, c.mozo, encargado, c.recibida, lista, mins, prods, tu, mc, mb))
        sync.encolar(sync.fila(c.id, fecha(c.recibida), c.mesa, c.zona, c.titulo, encargado, c.mozo,
            hhmmss(c.recibida), hhmmss(lista), mins, prods, tu, mc, mb))
        try {
            val nuevo = !archHist.exists()
            val linea = listOf(c.id.toString(), fecha(c.recibida), csv(c.mesa), csv(c.zona), csv(c.titulo),
                csv(encargado), csv(c.mozo), hhmmss(c.recibida), hhmmss(lista), f1(mins),
                csv(prods.joinToString(" | ") { "${it.first} ${it.second}" }), tu, f1(mc), f1(mb)).joinToString(";")
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
            put("afecta", JSONArray(afecta.toList()))
            put("palabras", palabras); put("estaciones", estaciones); put("barra", barraPal)
            put("encargado", encargado.j()); put("turno_desde", turnoDesde.j()); put("ip", ip)
            put("turno", turno.j())
            put("turnos", JSONObject().apply {
                Turnos.horario.forEach { (k, v) -> put(k, JSONArray(listOf(v.first, v.second))) }
            })
            put("sync", JSONObject().apply {
                put("activo", sync.activo); put("pendientes", sync.pendientes()); put("url", sync.url); put("clave", sync.clave)
                put("ultimo_ok", sync.ultimoOk.j()); put("error", sync.error.j())
            })
            put("comandas", JSONArray().apply { comandas.forEach { put(comandaJ(it)) } })
            put("hist", JSONArray().apply { hist.filter { it.listo >= hoy0 }.forEach { put(histJ(it)) } })
        }.toString()
    }

    private fun cambio(evento: String, af: Set<String> = emptySet()) {
        afecta = af
        guardar()
        val msg = snapshot(evento)
        clientes.forEach { it.offer(msg) }
        try { alEvento?.invoke(evento, af) } catch (_: Exception) { }
    }

    /** Comandas que la cocina aún no termina y ya pasaron a rojo (≥ ambar min). */
    fun atrasadasCocina(): List<Int> = synchronized(lock) {
        val lim = ahora() - ambar * 60
        comandas.filter { c ->
            c.estado == "abierta" && c.recibida <= lim && c.items.any { !it.cancelado } &&
                (!estaciones || ("cocina" in estacionesDe(c) && "cocina" !in c.listas))
        }.map { it.id }
    }

    // --------------------------------------------------------------- lógica
    /** Si todas las estaciones de la comanda terminaron, queda lista. */
    /** Si todos sus productos fueron anulados, la comanda queda ANULADA: se detiene el reloj y no cuenta en los tiempos. */
    private fun cerrarSiAnulada(c: Comanda): Boolean {
        if (c.estado != "abierta" || c.items.any { !it.cancelado }) return false
        c.estado = "anulada"; c.lista = ahora()
        return true
    }

    private fun revisarLista(c: Comanda): Boolean {
        if (c.estado != "abierta") return false
        if (cerrarSiAnulada(c)) return false
        val sts = estacionesDe(c)
        if (!sts.all { it in c.listas }) return false
        c.estado = "lista"; c.lista = sts.mapNotNull { c.listas[it] }.maxOrNull() ?: ahora()
        registrarHist(c)
        return true
    }

    /**
     * Nueva comanda. [desdeBarra] = llegó por la impresora "Barra" (puerto 9101): todo va a barra.
     * Si no, cada producto va a la estación que indique la lista de productos de barra.
     */
    /** Ventana para juntar adicionales en la misma tarjeta: 2 min desde que entró la comanda. */
    private val VENTANA = 120.0

    /** ¿Es el mismo pedido? Misma mesa y zona; en la mesa 0 (para llevar) también el mismo título. */
    private fun mismaMesa(c: Comanda, p: Parseada) = p.mesa != null && c.mesa == p.mesa && c.zona == p.zona &&
        (p.mesa != "0" || c.titulo == p.titulo)

    /**
     * Nueva comanda. [desdeBarra] = llegó por la impresora "Barra" (puerto 9101): todo va a barra.
     * Si no, cada producto va a la estación que indique la lista de productos de barra.
     * Adicionales de la misma mesa: dentro de 2 min se suman a la misma tarjeta (mismo tiempo);
     * después, son una tarjeta nueva con su propio tiempo. Las anulaciones siempre tachan donde esté el producto.
     */
    fun nuevaComanda(p: Parseada, desdeBarra: Boolean = false): Int = synchronized(lock) {
        val t = ahora()
        (p.items + p.cancelados).forEach { it.est = if (estaciones && desdeBarra) "barra" else estacionDe(it.producto) }
        val af = (p.items + p.cancelados).map { it.est }.toSet()
        val abiertas = comandas.filter { it.estado == "abierta" && mismaMesa(it, p) }.sortedByDescending { it.recibida }

        // 1) Anulaciones: se tachan en la tarjeta donde está el producto (la más reciente primero)
        val tocadas = mutableSetOf<Comanda>()
        val sinMatch = mutableListOf<Item>()
        for (cx in p.cancelados) {
            val cand = abiertas.flatMap { c -> c.items.filter { !it.cancelado && it.producto == cx.producto }.map { c to it } }
            val obj = cand.firstOrNull { it.second.mods == cx.mods } ?: cand.firstOrNull()
            if (obj != null) { obj.second.cancelado = true; tocadas += obj.first } else sinMatch += cx.also { it.cancelado = true }
        }
        val reciente = abiertas.firstOrNull()
        if (p.items.isEmpty() && (tocadas.isNotEmpty() || reciente != null)) {
            val destino = tocadas.firstOrNull() ?: reciente!!
            destino.items.addAll(sinMatch)
            var listo = false
            (tocadas + destino).forEach { it.actualizada = t; if (revisarLista(it)) listo = true }
            cambio(if (listo) "listo" else "anulacion", af)
            return destino.id
        }

        // 2) Productos dentro de los 2 min: misma tarjeta, mismo tiempo
        if (reciente != null && p.items.isNotEmpty() && t - reciente.recibida <= VENTANA) {
            reciente.items.addAll(sinMatch)
            // Wally manda un ticket por impresora: la parte de la otra estación que llega enseguida no es un cambio
            val previas = reciente.items.filter { !it.cancelado }.map { it.est }.toSet()
            val parte = p.cancelados.isEmpty() && p.items.none { it.est in previas }
            val marca = if (parte) null else if (p.cancelados.isNotEmpty()) "cambio" else "nuevo"
            p.items.forEach { reciente.items.add(it.also { i -> i.marca = marca }) }
            p.items.forEach { reciente.listas.remove(it.est) }          // esa estación vuelve a tener trabajo
            reciente.actualizada = t
            tocadas.forEach { it.actualizada = t }
            cambio(if (parte) "nueva" else "cambio", af)
            return reciente.id
        }

        // 3) Pedido nuevo, o adicional pasados los 2 min: tarjeta propia con su propio tiempo
        val adicional = p.items.isNotEmpty() && comandas.any { mismaMesa(it, p) }
        if (p.cancelados.isNotEmpty() && p.items.isNotEmpty()) p.items.forEach { it.marca = "cambio" }
        tocadas.forEach { it.actualizada = t; revisarLista(it) }
        seq += 1
        val c = Comanda(seq, p.mesa, p.zona, p.titulo, p.mozo, p.hora,
            (p.items + sinMatch).toMutableList(), recibida = t, actualizada = t, adicional = adicional)
        comandas.add(c)
        cerrarSiAnulada(c)      // ticket solo de anulaciones (de algo ya despachado): no queda corriendo
        cambio(if (p.items.isNotEmpty()) "nueva" else "anulacion", af)
        return c.id
    }

    fun accion(a: String, id: Int?, idx: Int?, nombre: String?, v: Int?, am: Int?,
               url: String? = null, clave: String? = null, turnoSel: String? = null,
               est: String? = null): Boolean = synchronized(lock) {
        val c = comandas.firstOrNull { it.id == id }
        val e = est?.takeIf { estaciones && (it == "cocina" || it == "barra") }
        var evento: String
        var af: Set<String> = emptySet()
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
            a == "palabras" -> {
                val arr = try { JSONArray(url ?: "") } catch (_: Exception) { return false }
                val limpio = JSONArray()
                for (k in 0 until minOf(arr.length(), 40)) {
                    val o = arr.optJSONObject(k) ?: continue
                    val pal = o.optString("p").trim().take(30); val col = o.optString("c").trim().take(12)
                    if (pal.isNotEmpty() && col.isNotEmpty()) limpio.put(JSONObject().apply { put("p", pal); put("c", col) })
                }
                palabras = limpio; evento = "estado"
            }
            a == "estaciones" -> {
                if (v != null) estaciones = v == 1
                if (!url.isNullOrBlank()) {
                    val arr = try { JSONArray(url) } catch (_: Exception) { return false }
                    val limpio = JSONArray()
                    for (k in 0 until minOf(arr.length(), 80)) {
                        val s = arr.optString(k).trim().take(30); if (s.isNotEmpty()) limpio.put(s)
                    }
                    barraPal = limpio
                }
                // Reclasifica lo que está abierto en pantalla
                comandas.filter { it.estado == "abierta" }.forEach { c2 ->
                    c2.items.forEach { it.est = estacionDe(it.producto) }
                    c2.listas.keys.retainAll(estacionesDe(c2)); revisarLista(c2)
                }
                evento = "estado"
            }
            a == "config" -> {
                val nv = v ?: return false; val na = am ?: return false
                if (nv < 1 || na <= nv || na > 120) return false
                verde = nv; ambar = na; evento = "estado"
            }
            a == "item" && c != null && idx != null && idx in c.items.indices -> {
                c.items[idx].hecho = !c.items[idx].hecho; evento = "estado"
            }
            a == "listo" && c != null && c.estado == "abierta" && c.items.none { !it.cancelado } -> {
                cerrarSiAnulada(c); evento = "estado"
            }
            a == "listo" && c != null && c.estado == "abierta" -> {
                val sts = estacionesDe(c)
                if (e == null || e !in sts) sts.forEach { c.listas.putIfAbsent(it, ahora()) }
                else c.listas[e] = ahora()
                af = if (e != null) setOf(e) else sts
                evento = if (revisarLista(c)) "listo" else "estacion"
            }
            a == "entregado" && c != null -> { comandas.remove(c); evento = "estado" }
            a == "recuperar" -> {
                val u = if (e == null) comandas.filter { it.estado == "lista" }.maxByOrNull { it.lista ?: 0.0 }
                        else comandas.filter { e in it.listas }.maxByOrNull { it.listas[e] ?: 0.0 }
                if (u == null) return false
                if (e == null) u.listas.clear() else u.listas.remove(e)
                if (u.estado == "lista") {
                    u.estado = "abierta"; u.lista = null
                    hist.removeAll { it.id == u.id }
                }
                evento = "estado"
            }
            else -> return false
        }
        // Las despachadas hace más de 30 min salen de pantalla
        val lim = ahora() - 1800
        comandas.removeAll { (it.estado == "lista" || it.estado == "anulada") && (it.lista ?: 0.0) < lim }
        cambio(evento, af)
        true
    }
}
