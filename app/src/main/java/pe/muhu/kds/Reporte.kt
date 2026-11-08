package pe.muhu.kds

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reporte del servicio en PDF (A4) para compartir en el grupo de cocina:
 * cantidad de comandas, tiempo de cada comanda, promedio, producto que más demoró,
 * resultados por encargado y comandas fuera de tiempo.
 */
object Reporte {
    private const val W = 595f          // A4 en puntos
    private const val H = 842f
    private const val M = 40f           // margen

    private val CAFE = Color.rgb(138, 90, 43)
    private val TINTA = Color.rgb(34, 30, 26)
    private val GRIS = Color.rgb(110, 103, 92)
    private val LINEA = Color.rgb(226, 220, 210)
    private val FONDO = Color.rgb(247, 243, 236)
    private val VERDE = Color.rgb(47, 158, 91)
    private val AMBAR = Color.rgb(217, 139, 25)
    private val ROJO = Color.rgb(217, 68, 63)

    private fun p(size: Float, color: Int = TINTA, bold: Boolean = false) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun mmss(min: Double): String {
        val s = Math.round(min * 60).toInt().coerceAtLeast(0)
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    private fun hhmm(t: Double) = SimpleDateFormat("HH:mm", Locale.US).format(Date((t * 1000).toLong()))

    /** Recorta un texto para que entre en el ancho dado. */
    private fun encaja(t: String, ancho: Float, paint: Paint): String {
        if (paint.measureText(t) <= ancho) return t
        var s = t
        while (s.isNotEmpty() && paint.measureText("$s…") > ancho) s = s.dropLast(1)
        return "$s…"
    }

    /** Lienzo con salto de página automático. */
    private class Hojas(val doc: PdfDocument, val pie: String) {
        var n = 0
        lateinit var pagina: PdfDocument.Page
        lateinit var c: Canvas
        var y = M

        fun nueva() {
            if (n > 0) cerrar()
            n++
            pagina = doc.startPage(PdfDocument.PageInfo.Builder(W.toInt(), H.toInt(), n).create())
            c = pagina.canvas
            y = M
        }

        fun cerrar() {
            val pp = p(8f, GRIS)
            c.drawText(pie, M, H - 22f, pp)
            val num = "Página $n"
            c.drawText(num, W - M - pp.measureText(num), H - 22f, pp)
            doc.finishPage(pagina)
        }

        /** Asegura espacio; si no alcanza, pasa a otra hoja. */
        fun espacio(alto: Float) { if (y + alto > H - 50f) nueva() }
    }

    private class Col(val titulo: String, val ancho: Float, val derecha: Boolean = false)

    private fun tabla(h: Hojas, cols: List<Col>, filas: List<List<String>>, colores: List<Int?> = emptyList()) {
        val pt = p(8.5f, GRIS, true)
        val pf = p(9.5f)
        val alto = 17f
        fun cabecera() {
            h.espacio(alto * 2)
            var x = M
            for (c in cols) {
                val tx = if (c.derecha) x + c.ancho - pt.measureText(c.titulo) - 4 else x + 4
                h.c.drawText(c.titulo.uppercase(), tx, h.y + 11f, pt)
                x += c.ancho
            }
            h.y += alto
            h.c.drawLine(M, h.y - 3, W - M, h.y - 3, Paint().apply { color = LINEA; strokeWidth = 1f })
        }
        cabecera()
        filas.forEachIndexed { i, fila ->
            if (h.y + alto > H - 50f) { h.nueva(); cabecera() }
            if (i % 2 == 1) h.c.drawRect(M, h.y - 2, W - M, h.y + alto - 4, Paint().apply { color = FONDO })
            var x = M
            fila.forEachIndexed { k, v ->
                val c = cols[k]
                val color = if (c.titulo == "Tiempo" || c.titulo == "Promedio") colores.getOrNull(i) else null
                val pinta = if (color != null) p(9.5f, color, true) else pf
                val txt = encaja(v, c.ancho - 8, pinta)
                val tx = if (c.derecha) x + c.ancho - pinta.measureText(txt) - 4 else x + 4
                h.c.drawText(txt, tx, h.y + 10f, pinta)
                x += c.ancho
            }
            h.y += alto
        }
        h.y += 6f
    }

    private fun titulo(h: Hojas, t: String, sub: String? = null) {
        h.espacio(60f)
        h.y += 10f
        h.c.drawText(t, M, h.y + 12f, p(13f, CAFE, true))
        h.y += 18f
        if (sub != null) { h.c.drawText(sub, M, h.y + 9f, p(8.5f, GRIS)); h.y += 14f }
        h.y += 4f
    }

    fun generar(estado: Estado, dia: String): ByteArray {
        val hist = estado.histDe(dia).sortedBy { it.entro }
        val verde = estado.verde
        val ambar = estado.ambar
        fun color(min: Double) = if (min < verde) VERDE else if (min < ambar) AMBAR else ROJO

        val ahora = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.US).format(Date())
        val doc = PdfDocument()
        val h = Hojas(doc, "KDS Muhu · generado el $ahora")
        h.nueva()

        // ---------------------------------------------------------------- cabecera
        h.c.drawRect(0f, 0f, W, 92f, Paint().apply { color = TINTA })
        h.c.drawText("MUHU · REPORTE DE COCINA", M, 40f, p(19f, Color.WHITE, true))
        val fechaLarga = try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dia)!!
            SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy", Locale("es", "PE")).format(d)
                .replaceFirstChar { it.uppercase() }
        } catch (_: Exception) { dia }
        val rango = if (hist.isNotEmpty()) " · Servicio de ${hhmm(hist.first().entro)} a ${hhmm(hist.maxOf { it.listo })}" else ""
        h.c.drawText(fechaLarga + rango, M, 62f, p(10.5f, Color.rgb(230, 220, 205)))
        h.c.drawText("Semáforo: verde menos de $verde min · rojo desde $ambar min", M, 78f, p(8.5f, Color.rgb(192, 138, 84)))
        h.y = 112f

        if (hist.isEmpty()) {
            h.c.drawText("No hay comandas despachadas en esta fecha.", M, h.y + 20f, p(12f, GRIS))
            h.cerrar()
            return ByteArrayOutputStream().also { doc.writeTo(it); doc.close() }.toByteArray()
        }

        // ---------------------------------------------------------------- resumen
        val mins = hist.map { it.min }
        val prom = mins.average()
        val rapida = hist.minBy { it.min }
        val lenta = hist.maxBy { it.min }
        val nProductos = hist.sumOf { hh -> hh.prods.sumOf { it.first.replace(',', '.').toDoubleOrNull()?.toInt() ?: 1 } }
        val aTiempo = mins.count { it < verde }
        val fuera = mins.count { it >= ambar }

        // Producto que más demoró: el de mayor tiempo promedio (en las comandas donde estuvo)
        val porProducto = HashMap<String, MutableList<Double>>()
        hist.forEach { hh -> hh.prods.map { it.second }.distinct().forEach { porProducto.getOrPut(it) { mutableListOf() }.add(hh.min) } }
        val prodLento = porProducto.maxByOrNull { it.value.average() }

        val tarjetas = listOf(
            Triple("Comandas", hist.size.toString(), TINTA),
            Triple("Tiempo promedio", mmss(prom), color(prom)),
            Triple("A tiempo (< $verde min)", "${aTiempo * 100 / hist.size}%", VERDE),
            Triple("Fuera de tiempo (≥ $ambar min)", fuera.toString(), if (fuera > 0) ROJO else TINTA),
            Triple("Productos preparados", nProductos.toString(), TINTA),
            Triple("Más rápida", "${mmss(rapida.min)} · Mesa ${rapida.mesa ?: "-"}", VERDE),
            Triple("Más lenta", "${mmss(lenta.min)} · Mesa ${lenta.mesa ?: "-"}", color(lenta.min)),
            Triple("Producto que más demoró", prodLento?.key ?: "-", AMBAR),
        )
        val gap = 8f
        val tw = (W - 2 * M - 3 * gap) / 4
        val th = 54f
        tarjetas.forEachIndexed { i, (et, valor, col) ->
            val x = M + (i % 4) * (tw + gap)
            val y = h.y + (i / 4) * (th + gap)
            h.c.drawRoundRect(RectF(x, y, x + tw, y + th), 6f, 6f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = FONDO })
            h.c.drawText(encaja(et.uppercase(), tw - 16, p(7f, GRIS, true)), x + 8, y + 16, p(7f, GRIS, true))
            val grande = if (i < 5) 17f else 11f
            h.c.drawText(encaja(valor, tw - 16, p(grande, col, true)), x + 8, y + 40, p(grande, col, true))
        }
        h.y += 2 * th + gap + 6f
        if (prodLento != null) {
            h.c.drawText(encaja("Producto que más demoró: ${prodLento.key} · promedio ${mmss(prodLento.value.average())} en ${prodLento.value.size} comanda(s)",
                W - 2 * M, p(9f, GRIS)), M, h.y + 8f, p(9f, GRIS))
            h.y += 14f
        }

        // ---------------------------------------------------------------- encargados
        val porEnc = hist.groupBy { it.enc ?: "Sin turno" }
        if (porEnc.size >= 1) {
            titulo(h, "Resultado por encargado", "Ordenado del más rápido al más lento")
            val filas = porEnc.map { (k, v) ->
                val pm = v.map { it.min }.average()
                listOf(k, v.size.toString(), mmss(pm), "${v.count { it.min >= ambar } * 100 / v.size}%") to pm
            }.sortedBy { it.second }
            tabla(h, listOf(Col("Encargado", 235f), Col("Comandas", 80f, true), Col("Promedio", 100f, true), Col("Fuera de tiempo", 100f, true)),
                filas.mapIndexed { i, f -> if (i == 0 && filas.size > 1) listOf("★ " + f.first[0]) + f.first.drop(1) else f.first },
                filas.map { color(it.second) })
        }

        // ---------------------------------------------------------------- productos más lentos
        titulo(h, "Productos que más demoraron", "Tiempo promedio de las comandas donde aparece el producto")
        val prods = porProducto.entries.sortedByDescending { it.value.average() }.take(8)
        tabla(h, listOf(Col("Producto", 335f), Col("Veces", 80f, true), Col("Promedio", 100f, true)),
            prods.map { listOf(it.key, it.value.size.toString(), mmss(it.value.average())) },
            prods.map { color(it.value.average()) })

        // ---------------------------------------------------------------- detalle
        titulo(h, "Tiempo por comanda", "${hist.size} comandas en orden de llegada")
        tabla(h, listOf(Col("#", 34f), Col("Mesa", 52f), Col("Entró", 44f), Col("Listo", 44f), Col("Tiempo", 52f, true),
            Col("Encargado", 80f), Col("Productos", 209f)),
            hist.map { hh -> listOf(hh.id.toString(), hh.mesa ?: "-", hhmm(hh.entro), hhmm(hh.listo), mmss(hh.min),
                hh.enc ?: "-", hh.prods.joinToString(", ") { "${it.first} ${it.second}" }) },
            hist.map { color(it.min) })

        h.cerrar()
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}
