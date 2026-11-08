package pe.muhu.kds

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Sonido de cocina. Suena por el canal de ALARMA (no depende del volumen de multimedia
 * ni del modo silencio) y ajusta ese volumen antes de cada aviso.
 *  - Pedido nuevo: campanita suave, una vez (o sin sonido).
 *  - Cambio / anulación: tonos cortos y suaves.
 *  - Retraso: el único aviso fuerte, una vez cuando una comanda pasa a rojo.
 * Volumen: 0 = Normal, 1 = Fuerte, 2 = Máximo.
 */
object Sonido {
    private const val SR = 44100
    private val h by lazy { Handler(HandlerThread("sonido").apply { start() }.looper) }

    private fun prefs(c: Context) = c.getSharedPreferences("kds", Context.MODE_PRIVATE)
    fun nivel(c: Context) = prefs(c).getInt("volumen", 1).coerceIn(0, 2)
    fun ponerNivel(c: Context, n: Int) = prefs(c).edit().putInt("volumen", n.coerceIn(0, 2)).apply()
    fun nuevoActivo(c: Context) = prefs(c).getBoolean("sonido_nuevo", true)
    fun ponerNuevo(c: Context, b: Boolean) = prefs(c).edit().putBoolean("sonido_nuevo", b).apply()

    /** Nota: frecuencia (0 = silencio), duración ms, campana (decae) o tono plano. */
    private class Nota(val f: Int, val ms: Int, val campana: Boolean = true)

    fun sonar(c: Context, evento: String, forzar: Boolean = false) {
        val (notas, duro) = when (evento) {
            "nueva" -> {
                if (!forzar && !nuevoActivo(c)) return
                listOf(Nota(1319, 260), Nota(988, 520)) to 0.0                       // "din-don" suave
            }
            "cambio" -> listOf(Nota(1175, 160), Nota(0, 40), Nota(1175, 220)) to 0.0
            "anulacion" -> listOf(Nota(659, 240), Nota(523, 420)) to 0.0
            "retraso" -> {
                val b = listOf(Nota(1760, 150, false), Nota(0, 70), Nota(1760, 150, false), Nota(0, 70), Nota(1760, 260, false))
                (b + Nota(0, 350) + b) to 2.0                                        // el aviso que sí debe notarse
            }
            else -> return
        }
        h.post { tocar(c, notas, duro) }
    }

    private fun tocar(c: Context, notas: List<Nota>, duro: Double) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val obj = when (nivel(c)) { 0 -> (max * 0.55).toInt(); 1 -> (max * 0.8).toInt(); else -> max }.coerceAtLeast(1)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) != obj) am.setStreamVolume(AudioManager.STREAM_ALARM, obj, 0)
        } catch (_: Exception) { }   // "No molestar" puede impedirlo; igual intentamos sonar

        // Las campanas se dejan sonar un poco más allá de su nota para que no se corten
        val total = notas.sumOf { it.ms } * SR / 1000 + SR / 3
        val pcm = DoubleArray(total)
        var pos = 0
        for (n in notas) {
            val len = n.ms * SR / 1000
            if (n.f > 0) {
                val dur = if (n.campana) minOf(total - pos, len + SR / 3) else len
                val rampa = SR / 200
                for (i in 0 until dur) {
                    val t = i.toDouble() / SR
                    val ataque = minOf(1.0, i.toDouble() / rampa)
                    val env = if (n.campana) ataque * exp(-t * 5.5)
                              else ataque * minOf(1.0, (len - i).toDouble() / rampa)
                    var s = sin(2 * PI * n.f * t)
                    if (n.campana) s = 0.8 * s + 0.2 * sin(2 * PI * n.f * 2 * t)    // armónico: suena a campana
                    else if (duro > 0) s = tanh(duro * s) / tanh(duro)
                    pcm[pos + i] += s * env
                }
            }
            pos += len
        }
        val out = ShortArray(total) { (pcm[it].coerceIn(-1.0, 1.0) * 30000).toInt().toShort() }
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(out.size * 2)
                .build()
            t.write(out, 0, out.size)
            t.play()
            Thread.sleep(out.size * 1000L / SR + 100)
            t.release()
        } catch (_: Exception) { }
    }
}
