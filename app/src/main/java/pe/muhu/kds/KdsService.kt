package pe.muhu.kds

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.concurrent.thread

/** Servicio permanente: impresora virtual (9100) + servidor de pantallas (8080). */
class KdsService : Service() {

    companion object {
        @Volatile var estado: Estado? = null
        private var impresora: ServidorImpresora? = null
        private var web: ServidorWeb? = null

        fun iniciar(ctx: Context) {
            val i = Intent(ctx, KdsService::class.java)
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }

        fun ipLocal(): String {
            try {
                val ifs = NetworkInterface.getNetworkInterfaces().toList()
                    .filter { it.isUp && !it.isLoopback }
                    .sortedBy { if (it.name.startsWith("wlan")) 0 else if (it.name.startsWith("eth")) 1 else 2 }
                for (ni in ifs) for (a in ni.inetAddresses.toList())
                    if (a is Inet4Address && a.isSiteLocalAddress) return a.hostAddress ?: continue
            } catch (_: Exception) { }
            return "sin red"
        }
    }

    private var wake: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        arrancarPrimerPlano()
        wake = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "kds:servicio").apply { acquire() }
        @Suppress("DEPRECATION")
        wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "kds:wifi").apply { acquire() }

        if (estado == null) {
            val e = Estado(filesDir)
            e.ip = ipLocal()
            e.alEvento = { ev -> Sonido.sonar(this, ev) }
            e.sync.iniciar()
            estado = e
            val pagina = assets.open("kds.html").bufferedReader(Charsets.UTF_8).readText()
            impresora = ServidorImpresora(e, File(filesDir, "capturas")).also { it.iniciar() }
            web = ServidorWeb(e, pagina).also { it.iniciar() }
            // La IP puede cambiar si el WiFi se reconecta
            thread(isDaemon = true) {
                while (true) { Thread.sleep(30000); e.ip = ipLocal() }
            }
        }
    }

    private fun arrancarPrimerPlano() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("kds", "KDS activo", NotificationManager.IMPORTANCE_LOW))
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "kds")
            .setContentTitle("KDS Muhu activo")
            .setContentText("Recibiendo comandas en ${ipLocal()}")
            .setSmallIcon(R.drawable.ic_notif)
            .setContentIntent(abrir)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        try { wake?.release() } catch (_: Exception) { }
        try { wifi?.release() } catch (_: Exception) { }
        super.onDestroy()
    }
}
