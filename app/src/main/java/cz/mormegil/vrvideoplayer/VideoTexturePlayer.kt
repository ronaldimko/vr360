package cz.mormegil.vrvideoplayer

import android.content.Context
import android.graphics.SurfaceTexture
import android.graphics.SurfaceTexture.OnFrameAvailableListener
import android.media.MediaPlayer.OnVideoSizeChangedListener
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.Surface
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import java.io.File
import java.net.DatagramPacket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

class VideoTexturePlayer(
    private val context: Context,
    private val videoSizeChangedListener: OnVideoSizeChangedListener
) : OnFrameAvailableListener {

    companion object {
        private const val TAG = "VlcTexturePlayer"

        // HIGH поток сервера.
        // Если на сервере HIGH поменян — исправить здесь.
        private const val MULTICAST_IP = "239.0.0.1"
        private const val MULTICAST_PORT = 5004
        private const val RTP_PAYLOAD_TYPE = 96

        // Размер нужен только для SurfaceTexture buffer. Реальный кадр может быть другим.
        private const val VIDEO_WIDTH = 2048
        private const val VIDEO_HEIGHT = 1024
    }

    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    private var libVlc: LibVLC? = null
    private var vlcPlayer: MediaPlayer? = null

    private val frameAvailable = AtomicBoolean(false)
    private var videoPosition: Float = 0.0f
    private var initialized = false

    fun initializePlayback(texName: Int) {
        Log.d(TAG, "initializePlayback texName=$texName")

        cleanup()
        initialized = true

        val texture = SurfaceTexture(texName)
        texture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        surfaceTexture = texture
        texture.setOnFrameAvailableListener(this)

        val videoSurface = Surface(texture)
        surface = videoSurface

        // ВАЖНО: сначала проверяем, доходят ли UDP multicast пакеты до телефона.
        // Это не мешает VLC: пробник быстро закрывается и только потом стартует VLC.
        probeMulticastBeforeVlc()

        val vlcOptions = arrayListOf(
            "--verbose=2",
            "--ipv4",
            "--no-audio",
            "--no-video-title-show",
            "--network-caching=300",
            "--live-caching=300",
            "--rtp-caching=300",
            "--clock-jitter=0",
            "--clock-synchro=0",

            // Для диагностики сначала только software decode.
            // Если картинка появилась — потом можно попробовать заменить на --avcodec-hw=any.
            "--avcodec-hw=disabled"
        )

        val vlc = LibVLC(context.applicationContext, vlcOptions)
        libVlc = vlc

        val player = MediaPlayer(vlc)
        vlcPlayer = player

        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Opening -> Log.d(TAG, "VLC Opening")
                MediaPlayer.Event.Buffering -> Log.d(TAG, "VLC Buffering ${event.buffering}")
                MediaPlayer.Event.Playing -> {
                    Log.d(TAG, "VLC Playing")
                    videoSizeChangedListener.onVideoSizeChanged(null, VIDEO_WIDTH, VIDEO_HEIGHT)
                }
                MediaPlayer.Event.Paused -> Log.d(TAG, "VLC Paused")
                MediaPlayer.Event.Stopped -> Log.d(TAG, "VLC Stopped")
                MediaPlayer.Event.EndReached -> Log.w(TAG, "VLC EndReached")
                MediaPlayer.Event.EncounteredError -> Log.e(TAG, "VLC EncounteredError")
                else -> Log.d(TAG, "VLC event=${event.type}")
            }
        }

        player.vlcVout.setVideoSurface(videoSurface, null)
        player.vlcVout.setWindowSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        player.vlcVout.attachViews()

        val sdpFile = createSdpFile()
        val mediaPath = "file://${sdpFile.absolutePath}"
        Log.d(TAG, "Opening SDP path: $mediaPath")

        val media = Media(vlc, Uri.parse(mediaPath))
        media.addOption(":demux=live555")
        media.addOption(":network-caching=300")
        media.addOption(":live-caching=300")
        media.addOption(":rtp-caching=300")
        media.addOption(":clock-jitter=0")
        media.addOption(":clock-synchro=0")
        media.addOption(":no-audio")

        player.media = media
        media.release()

        val result = player.play()
        Log.d(TAG, "VLC play() result=$result")
    }

    fun rewind() {
        Log.d(TAG, "rewind ignored: live multicast")
    }

    fun seek(relSeek: Int) {
        Log.d(TAG, "seek ignored: live multicast relSeek=$relSeek")
    }

    fun getVideoPosition(): Float = videoPosition

    fun updateIfNeeded() {
        if (frameAvailable.getAndSet(false)) {
            try {
                surfaceTexture?.updateTexImage()
            } catch (e: Throwable) {
                Log.e(TAG, "updateTexImage error", e)
            }
        }
    }

    override fun onFrameAvailable(tex: SurfaceTexture?) {
        frameAvailable.set(true)
        videoPosition = 0.0f
        Log.d(TAG, "FRAME AVAILABLE")
    }

    fun onPause() {
        try {
            Log.d(TAG, "onPause")
            vlcPlayer?.stop()
        } catch (e: Throwable) {
            Log.e(TAG, "onPause error", e)
        }
    }

    fun onResume() {
        try {
            Log.d(TAG, "onResume initialized=$initialized")
            if (initialized) {
                vlcPlayer?.play()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "onResume error", e)
        }
    }

    fun onDestroy() {
        Log.d(TAG, "onDestroy")
        cleanup()
    }

    private fun cleanup() {
        try {
            vlcPlayer?.setEventListener(null)
            vlcPlayer?.stop()
            vlcPlayer?.vlcVout?.detachViews()
            vlcPlayer?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "VLC cleanup error", e)
        }
        vlcPlayer = null

        try {
            libVlc?.release()
        } catch (e: Throwable) {
            Log.e(TAG, "LibVLC release error", e)
        }
        libVlc = null

        try { surface?.release() } catch (_: Throwable) {}
        surface = null

        try { surfaceTexture?.release() } catch (_: Throwable) {}
        surfaceTexture = null
    }

    private fun createSdpFile(): File {
        // SDP максимально близкий к серверному multicast.sdp.
        // Специально НЕ указываем sprop-parameter-sets, потому что они должны приходить в RTP-потоке.
        val sdpText = """
v=0
o=- 0 0 IN IP4 0.0.0.0
s=RTP Multicast HIGH VR
t=0 0
a=tool:android-libvlc
m=video $MULTICAST_PORT RTP/AVP $RTP_PAYLOAD_TYPE
c=IN IP4 $MULTICAST_IP/16
b=AS:12000
a=framerate:30
a=rtpmap:$RTP_PAYLOAD_TYPE H264/90000
a=fmtp:$RTP_PAYLOAD_TYPE packetization-mode=1
a=recvonly
""".trimIndent()

        val file = File(context.cacheDir, "multicast_high_debug.sdp")
        file.writeText(sdpText, Charsets.US_ASCII)

        Log.d(TAG, "SDP file: ${file.absolutePath}")
        Log.d(TAG, "\n$sdpText")
        return file
    }

    private fun probeMulticastBeforeVlc() {
        Thread {
            var socket: MulticastSocket? = null
            try {
                Log.d(TAG, "UDP PROBE start: group=$MULTICAST_IP port=$MULTICAST_PORT")

                val group = InetAddress.getByName(MULTICAST_IP)
                socket = MulticastSocket(null)
                socket.reuseAddress = true
                socket.soTimeout = 500
                socket.bind(InetSocketAddress(MULTICAST_PORT))

                val iface = findWifiLikeInterface()
                if (iface != null) {
                    Log.d(TAG, "UDP PROBE networkInterface=${iface.name} ${iface.displayName}")
                    if (Build.VERSION.SDK_INT >= 24) {
                        socket.joinGroup(InetSocketAddress(group, MULTICAST_PORT), iface)
                    } else {
                        @Suppress("DEPRECATION")
                        socket.joinGroup(group)
                    }
                } else {
                    Log.w(TAG, "UDP PROBE no wifi interface found, joinGroup without interface")
                    @Suppress("DEPRECATION")
                    socket.joinGroup(group)
                }

                val started = System.currentTimeMillis()
                var packets = 0
                var bytes = 0L
                val buffer = ByteArray(2048)

                while (System.currentTimeMillis() - started < 2500) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        packets++
                        bytes += packet.length.toLong()
                        if (packets <= 5) {
                            Log.d(
                                TAG,
                                "UDP PROBE packet #$packets len=${packet.length} from=${packet.address.hostAddress}:${packet.port}"
                            )
                        }
                    } catch (_: SocketTimeoutException) {
                    }
                }

                Log.d(TAG, "UDP PROBE result packets=$packets bytes=$bytes")

                try {
                    if (iface != null && Build.VERSION.SDK_INT >= 24) {
                        socket.leaveGroup(InetSocketAddress(group, MULTICAST_PORT), iface)
                    } else {
                        @Suppress("DEPRECATION")
                        socket.leaveGroup(group)
                    }
                } catch (_: Throwable) {
                }
            } catch (e: Throwable) {
                Log.e(TAG, "UDP PROBE error", e)
            } finally {
                try { socket?.close() } catch (_: Throwable) {}
            }
        }.apply {
            name = "vr-multicast-probe"
            start()
            try {
                // Ждём немного, чтобы в Logcat сразу было видно: пакеты есть или нет.
                join(2800)
            } catch (_: InterruptedException) {
            }
        }
    }

    private fun findWifiLikeInterface(): NetworkInterface? {
        return try {
            val interfaces = NetworkInterface.getNetworkInterfaces().toList()
            interfaces.firstOrNull { ni ->
                ni.isUp && !ni.isLoopback && ni.supportsMulticast() &&
                    (ni.name.contains("wlan", ignoreCase = true) ||
                     ni.name.contains("wifi", ignoreCase = true) ||
                     ni.displayName.contains("wlan", ignoreCase = true) ||
                     ni.displayName.contains("wifi", ignoreCase = true))
            } ?: interfaces.firstOrNull { ni ->
                ni.isUp && !ni.isLoopback && ni.supportsMulticast()
            }
        } catch (e: Throwable) {
            Log.e(TAG, "findWifiLikeInterface error", e)
            null
        }
    }
}
