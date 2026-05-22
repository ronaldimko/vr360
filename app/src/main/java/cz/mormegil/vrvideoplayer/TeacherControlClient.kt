package cz.mormegil.vrvideoplayer

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TeacherControlClient(
    private val wsUrl: String,
    private val onLookAt: (yaw: Float, pitch: Float, radius: Float, duration: Int) -> Unit
) {
    private val tag = "TeacherControlClient"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private var webSocket: WebSocket? = null
    private var stopped = false

    fun start() {
        stopped = false
        connect()
    }

    fun stop() {
        stopped = true

        try {
            webSocket?.close(1000, "stop")
        } catch (_: Throwable) {
        }

        webSocket = null
    }

    private fun connect() {
        if (stopped) return

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        Log.d(tag, "Connecting to $wsUrl")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket connected")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "WS raw message: $text")
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket failure: ${t.message}", t)
                reconnectLater()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code $reason")
                reconnectLater()
            }
        })
    }

    private fun reconnectLater() {
        if (stopped) return

        mainHandler.postDelayed({
            if (!stopped) {
                connect()
            }
        }, 2000)
    }

    private fun handleMessage(text: String) {
        try {
            /*
             * Вариант 1:
             * Сервер может прислать строку:
             * /vr-view?x=...&y=...&z=...&yaw=...&pitch=...&radius=...&duration=...
             */
            if (text.contains("yaw=") && text.contains("pitch=")) {
                handleQueryStringMessage(text)
                return
            }

            /*
             * Вариант 2:
             * Сервер прислал JSON:
             * {"type":"look_at","yaw":354.78,"pitch":-3.24,"radius":150,"duration":1000}
             */
            val root = JSONObject(text)

            if (root.optString("type") == "hello") {
                Log.d(tag, "WS hello")
                return
            }

            val json = when {
                root.has("yaw") && root.has("pitch") -> root
                root.has("data") -> root.optJSONObject("data") ?: return
                root.has("vrView") -> root.optJSONObject("vrView") ?: return
                root.has("view") -> root.optJSONObject("view") ?: return
                else -> {
                    Log.d(tag, "WS message ignored: no yaw/pitch")
                    return
                }
            }

            if (!json.has("yaw") || !json.has("pitch")) {
                Log.d(tag, "WS message ignored: nested object has no yaw/pitch")
                return
            }

            val yaw = json.optDouble("yaw", 0.0).toFloat()
            val pitch = json.optDouble("pitch", 0.0).toFloat()
            val radius = json.optDouble("radius", 150.0).toFloat()
            val duration = json.optInt("duration", 1000)

            Log.d(
                tag,
                "Parsed look_at yaw=$yaw pitch=$pitch radius=$radius duration=$duration"
            )

            mainHandler.post {
                onLookAt(yaw, pitch, radius, duration)
            }
        } catch (e: Exception) {
            Log.e(tag, "Bad WS message: $text", e)
        }
    }

    private fun handleQueryStringMessage(text: String) {
        try {
            val clean = text.substringAfter("?")

            val params = clean.split("&")
                .mapNotNull {
                    val parts = it.split("=")
                    if (parts.size == 2) {
                        parts[0] to parts[1]
                    } else {
                        null
                    }
                }
                .toMap()

            val yaw = params["yaw"]?.toFloatOrNull() ?: return
            val pitch = params["pitch"]?.toFloatOrNull() ?: return
            val radius = params["radius"]?.toFloatOrNull() ?: 150f
            val duration = params["duration"]?.toIntOrNull() ?: 1000

            Log.d(
                tag,
                "Parsed query look_at yaw=$yaw pitch=$pitch radius=$radius duration=$duration"
            )

            mainHandler.post {
                onLookAt(yaw, pitch, radius, duration)
            }
        } catch (e: Exception) {
            Log.e(tag, "Bad query message: $text", e)
        }
    }
}