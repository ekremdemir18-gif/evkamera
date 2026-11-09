package com.evkamera.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Arka planda (ekran kapalıyken, uygulama kapatılsa bile) çalışan kamera servisi.
 * İzleyici web sayfası PeerJS sunucusu üzerinden bu kamerayı arar, servis görüntü ve sesle cevap verir.
 */
class CameraService : Service() {

    companion object {
        const val ACTION_STOP = "com.evkamera.STOP"
        const val ACTION_SWITCH = "com.evkamera.SWITCH"
        private const val CHANNEL = "evk_camera"
        private const val NOTIF_ID = 7
        private const val TAG = "EvKamera"

        @Volatile var running = false
        @Volatile var status = "Kapalı"
        @Volatile var viewers = 0
        var listener: (() -> Unit)? = null
        private val ui = Handler(Looper.getMainLooper())
        fun notifyUi() = ui.post { listener?.invoke() }

        fun cameraId(ctx: Context): String {
            val p = ctx.getSharedPreferences("evk", Context.MODE_PRIVATE)
            p.getString("camId", null)?.let { return it }
            val b = ByteArray(10).also { SecureRandom().nextBytes(it) }
            val id = "evk-" + b.joinToString("") { "%02x".format(it) }
            p.edit().putString("camId", id).apply()
            return id
        }
    }

    private class Conn(val peer: String, val id: String, val pc: PeerConnection) {
        var remoteSet = false
        val pending = ArrayList<IceCandidate>()
        var connected = false
    }

    private val exec = Executors.newSingleThreadScheduledExecutor()
    private val http = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    private var eglBase: EglBase? = null
    private var factory: PeerConnectionFactory? = null
    private var capturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var videoTrack: VideoTrack? = null
    private var audioTrack: AudioTrack? = null

    private var ws: WebSocket? = null
    private var wsGen = 0
    private var heartbeat: ScheduledFuture<*>? = null
    private var reconnect: ScheduledFuture<*>? = null
    private val conns = HashMap<String, Conn>()
    private var wakeLock: PowerManager.WakeLock? = null
    private var stopping = false

    private val iceServers = listOf(
        PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
        PeerConnection.IceServer.builder(listOf("turn:eu-0.turn.peerjs.com:3478", "turn:us-0.turn.peerjs.com:3478"))
            .setUsername("peerjs").setPassword("peerjsp").createIceServer()
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SWITCH -> { exec.execute { capturer?.switchCamera(null) }; return START_STICKY }
        }
        if (running) return START_STICKY
        running = true
        stopping = false
        goForeground()
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EvKamera::stream").apply { setReferenceCounted(false); acquire() }
        setStatus("Kamera açılıyor…")
        exec.execute {
            try {
                initMedia()
                connectSignaling()
            } catch (e: Throwable) {
                Log.e(TAG, "başlatma hatası", e)
                setStatus("Kamera açılamadı: ${e.message}")
            }
        }
        return START_STICKY
    }

    // ---------------- Bildirim ----------------

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            // Sessiz, küçük bildirim (ses/titreşim yok)
            val ch = NotificationChannel(CHANNEL, "Kamera durumu", NotificationManager.IMPORTANCE_MIN)
            ch.setShowBadge(false)
            nm.createNotificationChannel(ch)
        }
        val type = if (Build.VERSION.SDK_INT >= 30)
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(this, 1, Intent(this, CameraService::class.java).setAction(ACTION_STOP), flags)
        val text = if (viewers > 0) "$viewers izleyici" else "İzleyici bekleniyor"
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("EvKamera açık")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, "Durdur", stop)
            .build()
    }

    private fun updateNotification() {
        if (!running) return
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification())
    }

    private fun setStatus(s: String) {
        status = s
        notifyUi()
    }

    private fun refreshViewers() {
        val n = conns.values.count { it.connected }
        if (n != viewers) {
            viewers = n
            updateNotification()
        }
        if (ws != null && reconnect == null) setStatus(if (n > 0) "Yayında • $n izleyici" else "Hazır • izleyici bekleniyor")
        else notifyUi()
    }

    // ---------------- Kamera / mikrofon ----------------

    private fun initMedia() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(applicationContext).createInitializationOptions()
        )
        val egl = EglBase.create()
        eglBase = egl
        val adm = JavaAudioDeviceModule.builder(applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val f = PeerConnectionFactory.builder()
            .setAudioDeviceModule(adm)
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .createPeerConnectionFactory()
        factory = f

        val en = Camera2Enumerator(applicationContext)
        val name = en.deviceNames.firstOrNull { en.isBackFacing(it) } ?: en.deviceNames.first()
        val cap = en.createCapturer(name, null)
        capturer = cap
        val helper = SurfaceTextureHelper.create("evk-capture", egl.eglBaseContext)
        surfaceHelper = helper
        val vs = f.createVideoSource(false)
        videoSource = vs
        cap.initialize(helper, applicationContext, vs.capturerObserver)
        cap.startCapture(1280, 720, 20)
        videoTrack = f.createVideoTrack("evk-video", vs)

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val aus = f.createAudioSource(audioConstraints)
        audioSource = aus
        audioTrack = f.createAudioTrack("evk-audio", aus)
    }

    // ---------------- PeerJS sinyal sunucusu ----------------

    private fun connectSignaling() {
        if (stopping) return
        reconnect = null
        val gen = ++wsGen
        val id = cameraId(this)
        val token = java.lang.Long.toString(SecureRandom().nextLong() and Long.MAX_VALUE, 36)
        setStatus("Sunucuya bağlanıyor…")
        val req = Request.Builder()
            .url("wss://0.peerjs.com/peerjs?key=peerjs&id=$id&token=$token&version=1.5.4")
            .build()
        ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                exec.execute {
                    if (gen != wsGen) return@execute
                    heartbeat?.cancel(false)
                    heartbeat = exec.scheduleAtFixedRate(Runnable { ws?.send("{\"type\":\"HEARTBEAT\"}") }, 5, 5, TimeUnit.SECONDS)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                exec.execute { if (gen == wsGen) handleServer(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                exec.execute { if (gen == wsGen) lostSignaling("Sunucuya ulaşılamadı, tekrar deneniyor…", 5) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                exec.execute { if (gen == wsGen) lostSignaling("Bağlantı koptu, yeniden bağlanıyor…", 3) }
            }
        })
    }

    private fun lostSignaling(msg: String, delaySec: Long) {
        heartbeat?.cancel(false)
        heartbeat = null
        ws?.cancel()
        ws = null
        wsGen++
        if (stopping) return
        setStatus(msg)
        reconnect?.cancel(false)
        reconnect = exec.schedule(Runnable { connectSignaling() }, delaySec, TimeUnit.SECONDS)
    }

    private fun send(obj: JSONObject) {
        ws?.send(obj.toString())
    }

    private fun handleServer(text: String) {
        val msg = try { JSONObject(text) } catch (e: Exception) { return }
        val src = msg.optString("src")
        val payload = msg.optJSONObject("payload")
        when (msg.optString("type")) {
            "OPEN" -> refreshViewers()
            "ID-TAKEN" -> lostSignaling("Önceki oturum kapanıyor, bekleniyor…", 10)
            "ERROR" -> lostSignaling("Sunucu hatası, tekrar deneniyor…", 10)
            "OFFER" -> if (payload != null && payload.optString("type") == "media") handleOffer(src, payload)
            "CANDIDATE" -> if (payload != null) handleCandidate(payload)
            "LEAVE", "EXPIRE" -> conns.values.filter { it.peer == src }.forEach { closeConn(it) }
        }
    }

    private fun handleOffer(src: String, payload: JSONObject) {
        val f = factory ?: return
        val connId = payload.optString("connectionId")
        val sdp = payload.optJSONObject("sdp")?.optString("sdp") ?: return
        // Aynı izleyiciden gelen eski bağlantıları kapat
        conns.values.filter { it.peer == src }.forEach { closeConn(it) }

        val cfg = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        var conn: Conn? = null
        val pc = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                exec.execute {
                    val cand = JSONObject()
                        .put("candidate", c.sdp)
                        .put("sdpMid", c.sdpMid)
                        .put("sdpMLineIndex", c.sdpMLineIndex)
                    send(JSONObject().put("type", "CANDIDATE").put("dst", src).put("payload",
                        JSONObject().put("candidate", cand).put("type", "media").put("connectionId", connId)))
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                exec.execute {
                    val c = conn ?: return@execute
                    when (newState) {
                        PeerConnection.PeerConnectionState.CONNECTED -> { c.connected = true; refreshViewers() }
                        PeerConnection.PeerConnectionState.FAILED,
                        PeerConnection.PeerConnectionState.CLOSED -> closeConn(c)
                        PeerConnection.PeerConnectionState.DISCONNECTED -> {
                            // Kısa kopmalar kendiliğinden düzelebilir; 20 sn sonra hâlâ kopuksa kapat
                            exec.schedule(Runnable {
                                if (conns[connId] === c && c.pc.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) closeConn(c)
                            }, 20, TimeUnit.SECONDS)
                        }
                        else -> {}
                    }
                }
            }

            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: return

        val c = Conn(src, connId, pc)
        conn = c
        conns[connId] = c

        pc.setRemoteDescription(object : Sdp() {
            override fun onSetSuccess() {
                exec.execute {
                    if (conns[connId] !== c) return@execute
                    c.remoteSet = true
                    c.pending.forEach { pc.addIceCandidate(it) }
                    c.pending.clear()
                    videoTrack?.let { pc.addTrack(it, listOf("evk")) }
                    audioTrack?.let { pc.addTrack(it, listOf("evk")) }
                    pc.createAnswer(object : Sdp() {
                        override fun onCreateSuccess(answer: SessionDescription) {
                            exec.execute {
                                pc.setLocalDescription(object : Sdp() {
                                    override fun onSetSuccess() {
                                        exec.execute {
                                            send(JSONObject().put("type", "ANSWER").put("dst", src).put("payload",
                                                JSONObject()
                                                    .put("sdp", JSONObject().put("type", "answer").put("sdp", answer.description))
                                                    .put("type", "media")
                                                    .put("connectionId", connId)
                                                    .put("browser", "android")))
                                        }
                                    }
                                }, answer)
                            }
                        }
                    }, MediaConstraints())
                }
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    private fun handleCandidate(payload: JSONObject) {
        val c = conns[payload.optString("connectionId")] ?: return
        val j = payload.optJSONObject("candidate") ?: return
        val cand = IceCandidate(j.optString("sdpMid"), j.optInt("sdpMLineIndex"), j.optString("candidate"))
        if (c.remoteSet) c.pc.addIceCandidate(cand) else c.pending.add(cand)
    }

    private fun closeConn(c: Conn) {
        if (conns.remove(c.id) == null) return
        try { c.pc.dispose() } catch (e: Throwable) { Log.w(TAG, "dispose", e) }
        refreshViewers()
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(sdp: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(error: String?) { Log.w(TAG, "SDP oluşturma hatası: $error") }
        override fun onSetFailure(error: String?) { Log.w(TAG, "SDP ayarlama hatası: $error") }
    }

    // ---------------- Kapanış ----------------

    override fun onDestroy() {
        stopping = true
        running = false
        viewers = 0
        status = "Kapalı"
        exec.execute {
            try {
                heartbeat?.cancel(false)
                reconnect?.cancel(false)
                ws?.close(1000, "bye")
                ws = null
                conns.values.toList().forEach { try { it.pc.dispose() } catch (_: Throwable) {} }
                conns.clear()
                try { capturer?.stopCapture() } catch (_: Throwable) {}
                capturer?.dispose()
                surfaceHelper?.dispose()
                videoTrack?.dispose(); audioTrack?.dispose()
                videoSource?.dispose(); audioSource?.dispose()
                factory?.dispose()
                eglBase?.release()
            } catch (e: Throwable) {
                Log.w(TAG, "kapanış", e)
            }
            exec.shutdown()
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        notifyUi()
        super.onDestroy()
    }
}
