package systems.hiraeth.dxgarden

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Playback outside the app: the media session (notification, lock screen,
 * headset and Bluetooth buttons), audio focus, headphones unplugged, and
 * the Wi-Fi and wake locks. The player itself is native (player.c);
 * native code reports its state through [update] and takes commands
 * through [nativeCommand]. Everything here runs on the main thread.
 *
 * Only Android's own APIs (android.media.session, Notification.MediaStyle):
 * no AndroidX library.
 */
object Playback {
    // States reported by native code (main.c, bridge_playback).
    const val STOPPED = 0
    const val CONNECTING = 1
    const val PLAYING = 2
    const val PAUSED = 3
    const val ERROR = 4

    // Commands to native code (main.c).
    const val CMD_PLAY = 1
    const val CMD_PAUSE = 2
    const val CMD_STOP = 3
    const val CMD_NEXT = 4
    const val CMD_PREVIOUS = 5

    const val NOTIFICATION_ID = 1
    private const val CHANNEL = "playback"
    private const val ACTION_STOP = "stop"
    private const val TAG = "DXGarden"

    /** Implemented in libmain (main.c): queues a command for the native loop. */
    @JvmStatic
    external fun nativeCommand(cmd: Int)

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var session: MediaSession? = null
    private var audio: AudioManager? = null
    private var focusRequest: AudioFocusRequest? = null
    private var hasFocus = false
    private var resumeOnFocus = false
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var noisyRegistered = false
    private var serviceStarted = false

    var state = STOPPED
        private set
    var station = ""
        private set
    var detail = ""
        private set
    var place = ""
        private set

    private val noisy = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "playback: headphones unplugged, pausing")
                command(CMD_PAUSE)
            }
        }
    }

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                hasFocus = true
                if (resumeOnFocus && state == PAUSED) {
                    Log.i(TAG, "playback: focus back, resuming")
                    command(CMD_PLAY)
                }
                resumeOnFocus = false
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // A call, or a short sound from another app: pause, and
                // carry on when it's over.
                if (state == PLAYING || state == CONNECTING) {
                    Log.i(TAG, "playback: focus lost for a moment, pausing")
                    // Play pressed meanwhile asks for focus again, which is
                    // refused during a call.
                    hasFocus = false
                    resumeOnFocus = true
                    command(CMD_PAUSE)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app is playing now: pause and stay paused.
                Log.i(TAG, "playback: focus lost, pausing")
                hasFocus = false
                resumeOnFocus = false
                if (state == PLAYING || state == CONNECTING) command(CMD_PAUSE)
            }
            // AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK: Android lowers the volume
            // itself (automatic ducking), so nothing to do.
        }
    }

    fun init(context: Context) {
        if (session != null) return
        app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
                description = app.getString(R.string.channel_playback_about)
                setShowBadge(false)
            }
        )
        audio = app.getSystemService(AudioManager::class.java)
        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(focusListener, main)
            .build()
        wifiLock = app.getSystemService(WifiManager::class.java)
            ?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "DXGarden:stream")
            ?.apply { setReferenceCounted(false) }
        wakeLock = app.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DXGarden:stream")
            ?.apply { setReferenceCounted(false) }
        session = MediaSession(app, "DXGarden").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = command(CMD_PLAY)
                override fun onPause() = command(CMD_PAUSE)
                override fun onStop() = command(CMD_STOP)
                override fun onSkipToNext() = command(CMD_NEXT)
                override fun onSkipToPrevious() = command(CMD_PREVIOUS)
                override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                    if (action == ACTION_STOP) command(CMD_STOP)
                }
            }, main)
            setSessionActivity(openAppIntent())
        }
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        app, 0,
        Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private fun command(cmd: Int) {
        Log.i(TAG, "playback: command $cmd")
        try {
            nativeCommand(cmd)
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "playback: native code not loaded: $e")
        }
    }

    /** From native code, on any thread. */
    @JvmStatic
    fun update(newState: Int, newStation: String, newDetail: String, newPlace: String) {
        main.post { apply(newState, newStation, newDetail, newPlace) }
    }

    private fun apply(newState: Int, newStation: String, newDetail: String, newPlace: String) {
        val s = session ?: return
        val was = state
        state = newState
        station = newStation
        detail = newDetail
        place = newPlace
        Log.i(TAG, "playback: state $was -> $newState, '$newStation'")

        val active = newState == CONNECTING || newState == PLAYING
        if (active && !hasFocus) {
            val r = audio?.requestAudioFocus(focusRequest!!) ?: AudioManager.AUDIOFOCUS_REQUEST_FAILED
            hasFocus = r == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
            if (!hasFocus) {
                // Usually a phone call in progress.
                Log.i(TAG, "playback: audio focus refused, pausing")
                command(CMD_PAUSE)
            }
        }
        locks(active)
        noisyReceiver(active)

        if (newState == STOPPED) {
            if (hasFocus) audio?.abandonAudioFocusRequest(focusRequest!!)
            hasFocus = false
            resumeOnFocus = false
            s.isActive = false
            s.setPlaybackState(PlaybackState.Builder().setState(PlaybackState.STATE_STOPPED, 0, 0f).build())
            stopService()
            return
        }

        s.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, newStation)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, newDetail.ifEmpty { newPlace })
                .putString(MediaMetadata.METADATA_KEY_ALBUM, newPlace)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, -1) // live: no seek bar
                .build()
        )
        val ps = when (newState) {
            PLAYING -> PlaybackState.STATE_PLAYING
            CONNECTING -> PlaybackState.STATE_BUFFERING
            PAUSED -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_ERROR
        }
        s.setPlaybackState(
            PlaybackState.Builder()
                .setState(ps, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
                        PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(ACTION_STOP, app.getString(R.string.stop), R.drawable.ic_stop).build()
                )
                .apply { if (newState == ERROR) setErrorMessage(newDetail) }
                .build()
        )
        s.isActive = true
        if (!serviceStarted) {
            serviceStarted = true
            app.startForegroundService(Intent(app, PlaybackService::class.java))
        } else {
            app.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
        }
    }

    /** The playback notification; the service shows it while it runs. */
    fun notification(): Notification {
        val s = session!!
        return Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(station)
            .setContentText(detail.ifEmpty { place })
            .setSubText(place)
            .setContentIntent(openAppIntent())
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(state == PLAYING || state == CONNECTING)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setStyle(Notification.MediaStyle().setMediaSession(s.sessionToken))
            .build()
    }

    private fun locks(on: Boolean) {
        try {
            if (on) {
                // Wi-Fi locks no longer do anything from Android 14 on, but
                // help on 13; the wake lock keeps the CPU up while
                // connecting or buffering, when no audio is playing yet.
                if (wifiLock?.isHeld == false) wifiLock?.acquire()
                if (wakeLock?.isHeld == false) wakeLock?.acquire()
            } else {
                if (wifiLock?.isHeld == true) wifiLock?.release()
                if (wakeLock?.isHeld == true) wakeLock?.release()
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "playback: locks: $e")
        }
    }

    private fun noisyReceiver(on: Boolean) {
        if (on && !noisyRegistered) {
            app.registerReceiver(noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), Context.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
        } else if (!on && noisyRegistered) {
            app.unregisterReceiver(noisy)
            noisyRegistered = false
        }
    }

    private fun stopService() {
        if (serviceStarted) {
            serviceStarted = false
            app.stopService(Intent(app, PlaybackService::class.java))
        }
    }

    /** The app is going away (its activity destroyed): stop everything. */
    fun release() {
        if (session == null) return
        main.removeCallbacksAndMessages(null)
        apply(STOPPED, "", "", "")
        session?.release()
        session = null
    }
}
