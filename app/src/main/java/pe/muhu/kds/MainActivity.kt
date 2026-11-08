package pe.muhu.kds

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import kotlin.concurrent.thread

/** Pantalla de cocina a pantalla completa. Nunca se apaga la pantalla. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private val h = Handler(Looper.getMainLooper())
    private val URL = "http://127.0.0.1:$PUERTO_WEB/cocina"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KdsService.iniciar(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        web = WebView(this).apply {
            setBackgroundColor(0xFF14120F.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Puente(), "Android")
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(v: WebView, r: WebResourceRequest): Boolean {
                    val u = r.url
                    // El Excel y los enlaces externos se abren en el navegador
                    if (u.path?.endsWith(".csv") == true || u.host != "127.0.0.1") {
                        try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) { }
                        return true
                    }
                    return false
                }
                override fun onRenderProcessGone(v: WebView, d: RenderProcessGoneDetail): Boolean {
                    // Si Android cierra el motor de la página, se recrea sin cerrar la app
                    recreate(); return true
                }
                override fun onReceivedError(v: WebView, r: WebResourceRequest, e: WebResourceError) {
                    // El servidor aún está arrancando: reintenta
                    if (r.isForMainFrame) h.postDelayed({ v.loadUrl(URL) }, 1500)
                }
            }
        }
        setContentView(web)
        h.postDelayed({ web.loadUrl(URL) }, 600)
        pantallaCompleta()
        pedirPermisos()
    }

    override fun onResume() { super.onResume(); pantallaCompleta() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus); if (hasFocus) pantallaCompleta()
    }

    private fun pantallaCompleta() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        }
    }

    @SuppressLint("BatteryLife")
    private fun pedirPermisos() {
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) { }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Modo kiosko: el botón atrás no cierra el KDS
        if (web.canGoBack()) web.goBack()
    }

    inner class Puente {
        @JavascriptInterface fun abrirAjustes() {
            runOnUiThread { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        @JavascriptInterface fun elegirInicio() {
            runOnUiThread {
                try { startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        }
        @JavascriptInterface fun reportePdf(dia: String, turno: String) {
            val e = KdsService.estado ?: return
            val fecha = if (Regex("\\d{4}-\\d{2}-\\d{2}").matches(dia)) dia else e.hoy()
            thread {
                try {
                    val dir = File(cacheDir, "reportes").apply { mkdirs() }
                    val tu = turno.takeIf { Turnos.valido(it) }
                    val f = File(dir, "reporte_cocina_$fecha" + (tu?.let { "_$it" } ?: "") + ".pdf")
                    f.writeBytes(Reporte.generar(e, fecha, tu))
                    val uri = FileProvider.getUriForFile(this@MainActivity, "pe.muhu.kds.archivos", f)
                    val envio = Intent(Intent.ACTION_SEND).apply {
                        type = "application/pdf"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_TEXT, "Reporte de cocina Muhu · $fecha" + (tu?.let { " · Turno $it" } ?: ""))
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runOnUiThread { startActivity(Intent.createChooser(envio, "Compartir reporte")) }
                } catch (ex: Exception) {
                    runOnUiThread { Toast.makeText(this@MainActivity, "No se pudo crear el reporte: ${ex.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
        @JavascriptInterface fun recargar() { runOnUiThread { web.loadUrl(URL) } }
    }
}
