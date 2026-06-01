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
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * TeacherControlClient — WebSocket-клиент для управления VR-плеером с сервера.
 *
 * Он подключается к серверу, например:
 * ws://192.168.1.104:8071/vr-view-ws
 *
 * И принимает команды:
 * 1. повернуть взгляд на yaw/pitch;
 * 2. повернуть взгляд на координаты x/y/z;
 * 3. изменить режим видео: mono/stereo, 180/360, cardboard/mono.
 */
class TeacherControlClient(
    // Адрес WebSocket-сервера.
    private val wsUrl: String,

    /**
     * Callback, который вызывается, когда сервер прислал команду поворота.
     *
     * yaw — горизонтальный угол.
     * pitch — вертикальный угол.
     * radius — условный радиус/зум.
     * duration — длительность плавного поворота.
     */
    private val onLookAt: (yaw: Float, pitch: Float, radius: Float, duration: Int) -> Unit,

    /**
     * Callback, который вызывается, когда сервер прислал новый режим видео.
     *
     * Например:
     * Mono + Equirect360 + CardboardStereo.
     */
    private val onVideoMode: (
        inputLayout: InputLayout,
        inputMode: InputMode,
        outputMode: OutputMode
    ) -> Unit
) {
    // TAG для Logcat.
    private val tag = "TeacherControlClient"

    /**
     * Handler главного UI-потока.
     *
     * WebSocket callbacks приходят не всегда на main thread.
     * Поэтому команды в MainActivity лучше передавать через mainHandler.post { ... }.
     */
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * OkHttpClient для WebSocket.
     *
     * pingInterval(15 секунд) — периодически отправляет ping,
     * чтобы соединение не считалось мёртвым.
     *
     * retryOnConnectionFailure(true) — OkHttp может пытаться восстановиться
     * при некоторых сетевых ошибках.
     */
    private val client = OkHttpClient.Builder()
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Текущее WebSocket-соединение.
    private var webSocket: WebSocket? = null

    // Флаг, что клиент остановлен вручную.
    // Нужен, чтобы не переподключаться после stop().
    private var stopped = false

    /**
     * Запуск WebSocket-клиента.
     */
    fun start() {
        stopped = false
        connect()
    }

    /**
     * Остановка WebSocket-клиента.
     *
     * Закрывает соединение и запрещает автоматическое переподключение.
     */
    fun stop() {
        stopped = true

        try {
            webSocket?.close(1000, "stop")
        } catch (_: Throwable) {
            // Ошибку закрытия игнорируем.
        }

        webSocket = null
    }

    /**
     * Подключение к WebSocket-серверу.
     */
    private fun connect() {
        if (stopped) return

        val request = Request.Builder()
            .url(wsUrl)
            .build()

        Log.d(tag, "Connecting to $wsUrl")

        webSocket = client.newWebSocket(request, object : WebSocketListener() {

            /**
             * Соединение успешно открыто.
             */
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(tag, "WebSocket connected")

                /*
                 * Отправляем hello-сообщение серверу.
                 *
                 * Это не обязательно, но удобно:
                 * сервер может увидеть, что подключился именно Android VR-клиент.
                 */
                webSocket.send(
                    """
                    {"type":"android_hello","client":"vrvideoplayer","supports":["yaw_pitch","xyz","video_mode"]}
                    """.trimIndent()
                )
            }

            /**
             * Получено текстовое сообщение от сервера.
             */
            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(tag, "WS raw message: $text")
                handleMessage(text)
            }

            /**
             * Ошибка WebSocket.
             *
             * После ошибки пробуем переподключиться.
             */
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(tag, "WebSocket failure: ${t.message}", t)
                reconnectLater()
            }

            /**
             * WebSocket закрыт.
             *
             * Если клиент не остановлен вручную, пробуем переподключиться.
             */
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(tag, "WebSocket closed: $code $reason")
                reconnectLater()
            }
        })
    }

    /**
     * Переподключение через 2 секунды.
     *
     * Используется после обрыва соединения.
     */
    private fun reconnectLater() {
        if (stopped) return

        mainHandler.postDelayed({
            if (!stopped) connect()
        }, 2000)
    }

    /**
     * Главный обработчик входящих сообщений.
     *
     * Поддерживает несколько форматов:
     *
     * 1. Query string:
     *    yaw=90&pitch=0&radius=150
     *
     * 2. URL-like:
     *    /vr-view?yaw=90&pitch=0&radius=150
     *
     * 3. JSON:
     *    {"yaw":90,"pitch":0,"radius":150}
     *
     * 4. JSON с координатами:
     *    {"x":-150,"y":0,"z":0}
     *
     * 5. JSON с режимом видео:
     *    {"type":"video_mode","projection":"360","layout":"mono","output":"cardboard"}
     */
    private fun handleMessage(text: String) {
        try {
            Log.d(tag, "WS raw message handleMessage: $text")

            /*
             * Если сообщение похоже на query string,
             * обрабатываем его отдельно.
             */
            if (looksLikeQuery(text)) {
                handleQueryStringMessage(text)
                return
            }

            // Пробуем разобрать текст как JSON.
            val root = JSONObject(text)

            // hello от сервера — просто логируем и ничего не делаем.
            if (root.optString("type") == "hello") {
                Log.d(tag, "WS hello")
                return
            }

            /*
             * Сначала применяем режим видео.
             *
             * return специально НЕ делаем:
             * в одном JSON могут прийти и режим видео, и команда поворота.
             */
            if (isVideoModeMessage(root)) {
                handleVideoModeMessage(root)
            }

            // Потом пытаемся обработать команду поворота.
            handleLookAtMessage(root)
        } catch (e: Exception) {
            Log.e(tag, "Bad WS message: $text", e)
        }
    }

    /**
     * Быстрая проверка: похоже ли сообщение на query string.
     */
    private fun looksLikeQuery(text: String): Boolean {
        if (text.contains("?")) return true
        if (text.contains("yaw=") && text.contains("pitch=")) return true
        if (text.contains("x=") && text.contains("y=") && text.contains("z=")) return true
        return false
    }

    /**
     * Проверяет, есть ли в JSON признаки сообщения о режиме видео.
     */
    private fun isVideoModeMessage(json: JSONObject): Boolean {
        if (json.optString("type") == "video_mode") return true
        if (json.has("videoMode")) return true
        if (json.has("projection")) return true
        if (json.has("layout")) return true
        if (json.has("inputMode")) return true
        if (json.has("inputLayout")) return true
        if (json.has("outputMode")) return true
        if (json.has("output")) return true
        return false
    }

    /**
     * Обрабатывает сообщение смены режима видео.
     *
     * Сервер может прислать поля по-разному:
     * - videoMode;
     * - projection;
     * - inputMode;
     * - layout;
     * - inputLayout;
     * - output;
     * - outputMode.
     */
    private fun handleVideoModeMessage(root: JSONObject) {
        val videoMode = root.optString("videoMode", "").lowercase()
        val projection = root.optString("projection", "").lowercase()
        val inputModeRaw = root.optString("inputMode", "").lowercase()

        val layout = root.optString(
            "layout",
            root.optString("inputLayout", "")
        ).lowercase()

        val output = root.optString(
            "output",
            root.optString("outputMode", "")
        ).lowercase()

        // Преобразуем строки от сервера в enum-ы приложения.
        val parsedInputMode = parseInputMode(videoMode, projection, inputModeRaw)
        val parsedInputLayout = parseInputLayout(videoMode, layout)
        val parsedOutputMode = parseOutputMode(output)

        Log.d(
            tag,
            "Parsed video mode inputLayout=$parsedInputLayout inputMode=$parsedInputMode outputMode=$parsedOutputMode"
        )

        /*
         * Передаём результат в MainActivity на главном потоке.
         */
        mainHandler.post {
            onVideoMode(parsedInputLayout, parsedInputMode, parsedOutputMode)
        }
    }

    /**
     * Парсит геометрию видео.
     *
     * Возвращает:
     * - Equirect360;
     * - Equirect180;
     * - Panorama180;
     * - Panorama360;
     * - PlainFov.
     */
    private fun parseInputMode(
        videoMode: String,
        projection: String,
        inputModeRaw: String
    ): InputMode {
        /*
         * Приоритет:
         * 1. inputMode;
         * 2. projection;
         * 3. videoMode.
         */
        val value = when {
            inputModeRaw.isNotBlank() -> inputModeRaw
            projection.isNotBlank() -> projection
            else -> videoMode
        }.lowercase()

        return when (value) {
            "360", "360_mono", "360_stereo",
            "equirect360", "equirect_360", "equirectangular360", "equirectangular_360" ->
                InputMode.Equirect360

            "180", "180_mono", "180_stereo",
            "equirect180", "equirect_180", "equirectangular180", "equirectangular_180" ->
                InputMode.Equirect180

            "panorama180", "panorama_180" -> InputMode.Panorama180

            "panorama360", "panorama_360" -> InputMode.Panorama360

            "plain", "plain_fov", "flat", "2d" -> InputMode.PlainFov

            else -> {
                /*
                 * Если сервер прислал неизвестное значение,
                 * безопасно откатываемся к 360.
                 */
                Log.w(tag, "Unknown inputMode/projection='$value', fallback Equirect360")
                InputMode.Equirect360
            }
        }
    }

    /**
     * Парсит формат стерео/моно.
     *
     * Возвращает:
     * - Mono;
     * - StereoHoriz;
     * - StereoVert;
     * - AnaglyphRedCyan.
     */
    private fun parseInputLayout(videoMode: String, layout: String): InputLayout {
        /*
         * Если layout передан явно — используем его.
         * Иначе пытаемся вывести layout из videoMode.
         */
        val value = when {
            layout.isNotBlank() -> layout
            else -> videoMode
        }.lowercase()

        return when (value) {
            "mono", "360_mono", "180_mono" -> InputLayout.Mono

            "stereo", "stereo_horiz", "stereo_horizontal",
            "side_by_side", "side-by-side", "sbs",
            "360_stereo", "180_stereo" -> InputLayout.StereoHoriz

            "stereo_vert", "stereo_vertical",
            "top_bottom", "top-bottom", "tb",
            "over_under", "over-under", "ou" -> InputLayout.StereoVert

            "anaglyph", "anaglyph_red_cyan" -> InputLayout.AnaglyphRedCyan

            else -> {
                /*
                 * Если layout неизвестный — считаем, что видео mono.
                 */
                Log.w(tag, "Unknown layout='$value', fallback Mono")
                InputLayout.Mono
            }
        }
    }

    /**
     * Парсит режим вывода.
     *
     * По умолчанию возвращает CardboardStereo.
     */
    private fun parseOutputMode(output: String): OutputMode {
        return when (output.lowercase()) {
            "cardboard", "cardboard_stereo", "vr", "stereo" -> OutputMode.CardboardStereo
            "mono_left", "left", "mono" -> OutputMode.MonoLeft
            "mono_right", "right" -> OutputMode.MonoRight
            else -> OutputMode.CardboardStereo
        }
    }

    /**
     * Обрабатывает команду поворота из JSON.
     */
    private fun handleLookAtMessage(root: JSONObject) {
        /*
         * Команда поворота может лежать:
         * - прямо в root;
         * - внутри data;
         * - внутри payload;
         * - внутри camera и т.д.
         */
        val json = findLookAtObject(root)

        if (json == null) {
            Log.d(tag, "WS message has no look_at yaw/pitch or x/y/z")
            return
        }

        val lookAt = parseLookAt(json)

        if (lookAt == null) {
            Log.d(tag, "WS look_at ignored: unsupported object=$json")
            return
        }

        Log.d(
            tag,
            "Parsed look_at yaw=${lookAt.yaw} pitch=${lookAt.pitch} radius=${lookAt.radius} duration=${lookAt.duration}"
        )

        /*
         * Передаём команду в MainActivity.
         */
        mainHandler.post {
            onLookAt(lookAt.yaw, lookAt.pitch, lookAt.radius, lookAt.duration)
        }
    }

    /**
     * Ищет объект с координатами поворота.
     *
     * Поддерживает вложенные структуры:
     *
     * {
     *   "data": {
     *     "yaw": 90,
     *     "pitch": 0
     *   }
     * }
     */
    private fun findLookAtObject(root: JSONObject): JSONObject? {
        if (hasLookAtFields(root)) return root

        val keys = listOf("data", "payload", "vrView", "view", "coords", "camera", "message")

        for (key in keys) {
            val obj = root.optJSONObject(key)
            if (hasLookAtFields(obj)) return obj
        }

        return null
    }

    /**
     * Внутренняя структура после успешного разбора команды поворота.
     */
    private data class ParsedLookAt(
        val yaw: Float,
        val pitch: Float,
        val radius: Float,
        val duration: Int
    )

    /**
     * Проверяет, есть ли в JSON поля для поворота камеры.
     *
     * Поддерживаются два варианта:
     * 1. yaw + pitch;
     * 2. x + y + z.
     */
    private fun hasLookAtFields(json: JSONObject?): Boolean {
        if (json == null) return false
        if (json.has("yaw") && json.has("pitch")) return true
        if (json.has("x") && json.has("y") && json.has("z")) return true
        return false
    }

    /**
     * Разбирает команду поворота.
     *
     * Вариант 1:
     * {"yaw":90,"pitch":0,"radius":150,"duration":1000}
     *
     * Вариант 2:
     * {"x":-150,"y":0,"z":0,"radius":150,"duration":1000}
     */
    private fun parseLookAt(json: JSONObject): ParsedLookAt? {
        // durationMs тоже поддерживается как альтернативное имя.
        val duration = json.optInt("duration", json.optInt("durationMs", 1000))

        /*
         * Самый простой формат: сервер сразу прислал yaw и pitch.
         */
        if (json.has("yaw") && json.has("pitch")) {
            return ParsedLookAt(
                yaw = json.optDouble("yaw", 0.0).toFloat(),
                pitch = json.optDouble("pitch", 0.0).toFloat(),
                radius = json.optDouble("radius", 150.0).toFloat(),
                duration = duration
            )
        }

        /*
         * Второй формат: сервер прислал точку x/y/z.
         *
         * Её нужно перевести в yaw/pitch.
         */
        if (json.has("x") && json.has("y") && json.has("z")) {
            val x = json.optDouble("x", 0.0)
            val y = json.optDouble("y", 0.0)
            val z = json.optDouble("z", 0.0)

            // Длина вектора.
            val vectorRadius = sqrt(x * x + y * y + z * z)

            // Защита от деления на 0.
            val safeRadius = if (vectorRadius > 0.001) vectorRadius else 1.0

            /*
             * Обратное преобразование к формуле из index.html:
             *
             * x = -r * sin(yaw) * cos(pitch)
             * y = -r * sin(pitch)
             * z = -r * cos(yaw) * cos(pitch)
             *
             * Отсюда:
             * yaw   = atan2(-x, -z)
             * pitch = asin(-y / r)
             */
            var yaw = Math.toDegrees(atan2(-x, -z)).toFloat()

            // Приводим yaw к диапазону 0..360.
            if (yaw < 0f) yaw += 360f

            val pitch = Math.toDegrees(
                asin((-y / safeRadius).coerceIn(-1.0, 1.0))
            ).toFloat()

            /*
             * Если radius явно передан — используем его.
             * Если нет — используем длину вектора.
             * Если вектор почти нулевой — ставим 150.
             */
            val radius = json.optDouble(
                "radius",
                if (vectorRadius > 0.001) vectorRadius else 150.0
            ).toFloat()

            return ParsedLookAt(
                yaw = yaw,
                pitch = pitch,
                radius = radius,
                duration = duration
            )
        }

        return null
    }

    /**
     * Обрабатывает query string сообщение.
     *
     * Примеры:
     * yaw=90&pitch=0&radius=150&duration=1000
     *
     * /vr-view?yaw=90&pitch=0&radius=150&duration=1000
     *
     * x=-150&y=0&z=0&radius=150
     */
    private fun handleQueryStringMessage(text: String) {
        try {
            /*
             * Если пришёл URL вида /vr-view?yaw=90,
             * берём часть после знака вопроса.
             *
             * Если вопроса нет, substringAfter("?") вернёт исходную строку.
             */
            val clean = text.substringAfter("?")

            /*
             * Разбираем key=value пары.
             */
            val params = clean.split("&")
                .mapNotNull { pair ->
                    val parts = pair.split("=", limit = 2)

                    if (parts.size == 2) {
                        val key = URLDecoder.decode(parts[0], "UTF-8")
                        val value = URLDecoder.decode(parts[1], "UTF-8")
                        key to value
                    } else {
                        null
                    }
                }
                .toMap()

            /*
             * Для повторного использования parseLookAt()
             * собираем параметры в JSONObject.
             */
            val json = JSONObject()

            params["yaw"]?.toDoubleOrNull()?.let { json.put("yaw", it) }
            params["pitch"]?.toDoubleOrNull()?.let { json.put("pitch", it) }
            params["x"]?.toDoubleOrNull()?.let { json.put("x", it) }
            params["y"]?.toDoubleOrNull()?.let { json.put("y", it) }
            params["z"]?.toDoubleOrNull()?.let { json.put("z", it) }
            params["radius"]?.toDoubleOrNull()?.let { json.put("radius", it) }
            params["duration"]?.toIntOrNull()?.let { json.put("duration", it) }

            /*
             * durationMs тоже принимаем,
             * но кладём его внутрь JSON как duration.
             */
            params["durationMs"]?.toIntOrNull()?.let { json.put("duration", it) }

            val lookAt = parseLookAt(json)

            if (lookAt == null) {
                Log.d(tag, "Query message ignored: no yaw/pitch or x/y/z: $text")
                return
            }

            Log.d(
                tag,
                "Parsed query look_at yaw=${lookAt.yaw} pitch=${lookAt.pitch} radius=${lookAt.radius} duration=${lookAt.duration}"
            )

            /*
             * Передаём команду в MainActivity.
             */
            mainHandler.post {
                onLookAt(lookAt.yaw, lookAt.pitch, lookAt.radius, lookAt.duration)
            }
        } catch (e: Exception) {
            Log.e(tag, "Bad query message: $text", e)
        }
    }
}