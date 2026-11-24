package systems.hiraeth.dxgarden

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log

/**
 * The foreground service (type mediaPlayback) that keeps the app's process
 * alive and allowed to play while it isn't on screen. It only shows the
 * notification; the work is in [Playback] and the native player.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(
                Playback.NOTIFICATION_ID, Playback.notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } catch (e: RuntimeException) {
            // E.g. started from the background without being allowed to.
            Log.w("DXGarden", "playback: could not start in the foreground: $e")
            stopSelf()
        }
        // If the system kills the process, don't restart the service on its
        // own: the player lived in that process.
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // mediaPlayback services have no time limit; this is only a guard.
        stopSelf()
    }

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
}
