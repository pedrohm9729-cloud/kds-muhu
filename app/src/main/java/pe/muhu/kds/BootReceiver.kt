package pe.muhu.kds

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Al prender la tablet (o al actualizar la app) el KDS arranca solo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        KdsService.iniciar(ctx)
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) { }
    }
}
