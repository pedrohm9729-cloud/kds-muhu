package pe.muhu.kds

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.HandlerThread
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Sonido de cocina. Suena por el canal de ALARMA (no depende del volumen de multimedia
 * ni del modo silencio) y sube ese volumen antes de cada aviso.
 * Niveles: 0 = Normal, 1 = Fuerte, 2 = Máximo (por defecto).
 */
object Sonido {
    private const val SR = 44100
    private val h by lazy { Handler(HandlerThread("sonido").apply { start() }.looper) }

    private fun prefs(c: Context) = c.getSharedPreferences("kds", Context.MODE_PRIVATE)
    fun nivel(c: Context) = prefs(c).getInt("volumen", 2).coerceIn(0, 2)
    fun ponerNivel(c: Context, n: Int) = prefs(c).edit().putInt("volumen", n.coerceIn(0, 2)).apply()

    fun sonar(c: Context, evento: String) {
        val n = nivel(c)
        // (frecuencia Hz, duración ms); frecuencia 0 = silencio
        val beep = if (n == 0) 1500 else 2600
        val patron: List<Pair<Int, Int>> = when (evento) {
            "nueva" -> {
                val rafaga = listOf(beep to 160, 0 to 80, beep to 160, 0 to 80, beep to 160)
                when (n) { 0 -> rafaga; 1 -> rafaga + (0 to 300) + rafaga; else -> rafaga + (0 to 300) + rafaga + (0 to 300) + rafaga }
            }
            "cambio" -> listOf(1800 to 180, 0 to 60, beep to 260) + (if (n > 0) listOf(0 to 200, 1800 to 180, 0 to 60, beep to 260) else emptyList())
            "anulacion" -> listOf(700 to 450, 0 to 120, 520 to 650)
            "prueba" -> return sonar(c, "nueva")
            else -> return
        }
        h.post { tocar(c, n, patron) }
    }

    private fun tocar(c: Context, n: Int, patron: List<Pair<Int, Int>>) {
        val am = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val obj = when (n) { 0 -> (max * 0.6).toInt(); 1 -> (max * 0.85).toInt(); else -> max }.coerceAtLeast(1)
            if (am.getStreamVolume(AudioManager.STREAM_ALARM) != obj) am.setStreamVolume(AudioManager.STREAM_ALARM, obj, 0)
        } catch (_: Exception) { }   // "No molestar" puede impedirlo; igual intentamos sonar

        val total = patron.sumOf { it.second } * SR / 1000
        val pcm = ShortArray(total)
        val duro = if (n == 2) 4.0 else if (n == 1) 2.5 else 1.0   // más "cuadrada" = se oye más fuerte
        val norm = tanh(duro)
        var pos = 0
        for ((f, ms) in patron) {
            val len = ms * SR / 1000
            if (f > 0) {
                val rampa = SR / 200   // 5 ms para evitar chasquidos
                for (i in 0 until len) {
                    val env = minOf(1.0, i.toDouble() / rampa, (len - i).toDouble() / rampa)
                    val s = tanh(duro * sin(2 * PI * f * i / SR)) / norm
                    pcm[pos + i] = (s * env * 32000).toInt().toShort()
                }
            }
            pos += len
        }
        try {
            val t = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(SR)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            t.write(pcm, 0, pcm.size)
            t.play()
            Thread.sleep(pcm.size * 1000L / SR + 100)
            t.release()
        } catch (_: Exception) { }
    }
}
