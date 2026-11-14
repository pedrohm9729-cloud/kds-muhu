package pe.muhu.kds

/**
 * Convierte los bytes ESC/POS que envía el POS (Wally) en una comanda.
 * Misma lógica que la versión Python probada con tickets reales.
 */

class Item(
    var cant: String,
    var producto: String,
    val mods: MutableList<String> = mutableListOf(),
    var hecho: Boolean = false,
    var cancelado: Boolean = false,
    var marca: String? = null,
    /** Estación que lo prepara: "cocina" o "barra". */
    var est: String = "cocina",
)

class Parseada(
    var mesa: String? = null,
    var zona: String? = null,
    var titulo: String? = null,
    var fecha: String? = null,
    var hora: String? = null,
    var mozo: String? = null,
    val items: MutableList<Item> = mutableListOf(),
    val cancelados: MutableList<Item> = mutableListOf(),
)

object Parser {
    // Mitad alta de la página de códigos CP850 (0x80-0xFF)
    private const val CP850 =
        "ÇüéâäàåçêëèïîìÄÅ" + "ÉæÆôöòûùÿÖÜø£Ø×ƒ" + "áíóúñÑªº¿®¬½¼¡«»" + "░▒▓│┤ÁÂÀ©╣║╗╝¢¥┐" +
        "└┴┬├─┼ãÃ╚╔╩╦╠═╬¤" + "ðÐÊËÈıÍÎÏ┘┌█▄¦Ì▀" + "ÓßÔÒõÕµþÞÚÛÙýÝ¯´" + "­±‗¾¶§÷¸°¨·¹³²■ "

    fun escposATexto(d: ByteArray): String {
        val sb = StringBuilder()
        var i = 0
        val n = d.size
        fun b(k: Int) = if (k < n) d[k].toInt() and 0xFF else 0
        while (i < n) {
            val x = b(i)
            when {
                x == 0x1B -> { val c = b(i + 1); i += when (c) { 0x40, 0x3C, 0x32 -> 2; 0x70 -> 5; else -> 3 } }
                x == 0x1D -> {
                    val c = b(i + 1)
                    i += when {
                        c == 0x76 && i + 7 < n -> 8 + (b(i + 4) + b(i + 5) * 256) * (b(i + 6) + b(i + 7) * 256)
                        c == 0x56 && i + 2 < n && (b(i + 2) == 65 || b(i + 2) == 66) -> 4
                        else -> 3
                    }
                }
                x == 0x1C -> i += 2
                x == 0x10 -> i += 3
                else -> {
                    if (x == 0x0A) sb.append('\n')
                    else if (x in 0x20..0x7E) sb.append(x.toChar())
                    else if (x >= 0x80) sb.append(CP850[x - 0x80])
                    i++
                }
            }
        }
        return sb.toString()
    }

    private val I = RegexOption.IGNORE_CASE
    val SEP = Regex("^[^\\p{L}\\p{N}_]{3,}$")
    private val MESA = Regex("^Mesa:\\s*([^-]+?)\\s*(?:-\\s*(.*))?$", I)
    private val HORA = Regex("^Hora:\\s*(\\d{1,2}:\\d{2}(?::\\d{2})?)", I)
    private val FECHA = Regex("^Fecha:\\s*(.*)$", I)
    private val MOZO = Regex("^Tom.\\s*el\\s+pedido:\\s*(.*)$", I)
    val ITEM = Regex("^(\\d+(?:[.,]\\d+)?)\\s+(?:Un\\.?\\s+)?(.+)$")
    private val CANCEL = Regex("PRODUCTOS?\\s+CANCEL", I)
    private val CANCEL_COMPLETO = Regex("CANCELADOS?\\s*$", I)
    /** Sección de ingredientes a quitar: "--- QUITAR ---" seguida de líneas "- grupo: valor;" */
    private val QUITAR = Regex("^-+\\s*QUITAR\\s*-*$", I)
    private val NOTA = Regex("^Nota\\s*:", I)
    private val ADOS = Regex("^a?dos?$", I)

    private fun limpiar(s: String) = s.trim(' ', '.').ifEmpty { null }

    fun parsear(texto: String): Parseada {
        val lineas = texto.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val c = Parseada()

        val idxMozo = lineas.indexOfFirst { MOZO.containsMatchIn(it) && MOZO.find(it) != null }
        val cab = if (idxMozo >= 0) lineas.subList(0, idxMozo) else emptyList()
        val cuerpo = (if (idxMozo >= 0) lineas.subList(idxMozo + 1, lineas.size) else lineas).toMutableList()
        if (idxMozo >= 0) {
            c.mozo = limpiar(MOZO.find(lineas[idxMozo])!!.groupValues[1])
            // Ticket angosto: el nombre del mozo baja a la línea siguiente
            if (c.mozo == null && cuerpo.isNotEmpty() && !SEP.matches(cuerpo[0]) && !ITEM.matches(cuerpo[0])) {
                c.mozo = limpiar(cuerpo.removeAt(0))
            }
        }

        var idxMesa = -1
        cab.forEachIndexed { k, l ->
            MESA.find(l)?.let { m ->
                c.mesa = m.groupValues[1].trim()
                c.zona = m.groupValues[2].trim().ifEmpty { null }
                idxMesa = k
                return@forEachIndexed
            }
            HORA.find(l)?.let { c.hora = it.groupValues[1]; return@forEachIndexed }
            FECHA.find(l)?.let { c.fecha = it.groupValues[1].trim() }
        }
        // Título: líneas libres entre "Mesa" (o el alias) y "Fecha"
        val idxFecha = cab.indexOfFirst { FECHA.containsMatchIn(it) }.let { if (it < 0) cab.size else it }
        val desde = if (idxMesa >= 0) idxMesa + 1 else 1
        if (desde < idxFecha) {
            val libres = cab.subList(desde, idxFecha).filter { !SEP.matches(it) && HORA.find(it) == null }
            c.titulo = libres.joinToString(" ").ifEmpty { null }
        }

        var destino = c.items
        var actual: Item? = null
        var ultMod = false
        var saltarAdos = false
        var quitar = false
        for (l in cuerpo) {
            if (SEP.matches(l)) { actual = null; ultMod = false; quitar = false; continue }
            if (QUITAR.matches(l)) { quitar = actual != null; ultMod = false; continue }
            if (quitar && l.startsWith("-")) {
                // "- salsas: mayonesa;" → "SIN salsas: mayonesa" (se limpia al final)
                actual?.let { it.mods.add("SIN " + l.trimStart('-', ' ').trimEnd(' ', ';')); ultMod = true }
                continue
            }
            if (NOTA.containsMatchIn(l)) {
                actual?.let { it.mods.add(l.trimEnd(' ', ';')); ultMod = true }
                continue
            }
            if (CANCEL.containsMatchIn(l)) {
                destino = c.cancelados; actual = null; ultMod = false
                saltarAdos = !CANCEL_COMPLETO.containsMatchIn(l)
                continue
            }
            if (saltarAdos) {
                saltarAdos = false
                if (ADOS.matches(l)) continue
            }
            if (l.startsWith("+")) {
                quitar = false
                actual?.let { it.mods.add(l.trimStart('+', ' ').trimEnd(' ', ';')); ultMod = true }
                continue
            }
            val m = ITEM.find(l)
            if (m != null) {
                quitar = false
                actual = Item(m.groupValues[1], m.groupValues[2].trim())
                destino.add(actual)
                ultMod = false
                continue
            }
            actual?.let {
                if (ultMod && it.mods.isNotEmpty()) {
                    it.mods[it.mods.size - 1] = (it.mods.last() + " " + l).trimEnd(' ', ';')
                } else {
                    it.producto += " $l"
                }
            }
        }
        // "SIN salsas: mayonesa" → "SIN mayonesa" (lo que importa en cocina es qué ingrediente quitar)
        (c.items + c.cancelados).forEach { it2 ->
            for (k in it2.mods.indices) {
                val md = it2.mods[k]
                if (md.startsWith("SIN ")) {
                    val v = md.removePrefix("SIN ").let { t -> if (':' in t) t.substringAfter(':') else t }.trim().trimEnd(';')
                    it2.mods[k] = "SIN " + v.ifEmpty { md.removePrefix("SIN ") }
                }
            }
        }
        return c
    }
}
