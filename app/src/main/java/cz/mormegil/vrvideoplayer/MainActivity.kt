package cz.mormegil.vrvideoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.wifi.WifiManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.util.Log
import android.view.MenuInflater
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import cz.mormegil.vrvideoplayer.databinding.ActivityMainBinding
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Главная Activity приложения.
 *
 * Она отвечает за:
 * 1. запуск OpenGL-поверхности;
 * 2. запуск нативного C++ рендера;
 * 3. запуск видеоплеера;
 * 4. получение команд с сервера учителя;
 * 5. обработку сенсоров телефона;
 * 6. переключение режимов видео: mono, stereo, 180, 360, Cardboard.
 */
class MainActivity : AppCompatActivity(),
    MediaPlayer.OnVideoSizeChangedListener,
    SensorEventListener {

    companion object {
        private const val TAG = "VRVideoPlayer"

        /*
         * Чувствительность Android SensorManager.
         *
         * Важно:
         * В CardboardStereo основной трекер головы должен работать в Renderer.cpp
         * через CardboardHeadTracker.
         *
         * Эти коэффициенты больше нужны для fallback-режима:
         * MonoLeft / MonoRight.
         */
        private const val YAW_SENSITIVITY = 4.0f
        private const val PITCH_SENSITIVITY = 4.0f
        private const val ROLL_SENSITIVITY = 1.0f
    }

    // ViewBinding для activity_main.xml.
    private lateinit var binding: ActivityMainBinding

    // OpenGL-поверхность, на которой рисуется видео/VR-сцена.
    private lateinit var glView: GLSurfaceView

    // Класс, который управляет видео-текстурой.
    // Он отдаёт кадр видео в OpenGL.
    private lateinit var videoTexturePlayer: VideoTexturePlayer

    // Контроллер кнопок: громкость, перемотка и т.д.
    private lateinit var controller: Controller

    // Указатель на C++ объект Renderer/Application.
    // Long хранит native pointer.
    private var nativeApp: Long = 0L

    // WebSocket-клиент для команд с сервера учителя.
    private var teacherControlClient: TeacherControlClient? = null

    /*
     * IP сервера.
     *
     * Важно:
     * 127.0.0.1 на телефоне — это сам телефон.
     * Поэтому здесь должен быть IP компьютера в локальной сети.
     */
    private val serverIp = "192.168.1.104"

    /*
     * Порт WebSocket сервера.
     *
     * Итоговый адрес будет:
     * ws://192.168.1.104:8071/vr-view-ws
     */
    private val vrWsPort = 8071

    /*
     * Стартовый режим приложения.
     *
     * inputLayout — как записано видео:
     * Mono, StereoHoriz, StereoVert и т.д.
     *
     * inputMode — геометрия видео:
     * Equirect360, Equirect180, Panorama180 и т.д.
     *
     * outputMode — как показывать:
     * MonoLeft, MonoRight или CardboardStereo.
     *
     * Сейчас стартовый режим:
     * обычное 360° mono-видео в Cardboard.
     */
    private var inputLayout: InputLayout = InputLayout.Mono
    private var inputMode: InputMode = InputMode.Equirect360
    private var outputMode: OutputMode = OutputMode.CardboardStereo

    // Блокировка Wi-Fi multicast, нужна для приёма multicast/RTP потоков по Wi-Fi.
    private var multicastLock: WifiManager.MulticastLock? = null

    // Последние координаты касания экрана.
    private var lastTouchCoordinates = arrayOf(1.0f, 0.0f)

    // Android SensorManager для чтения датчиков ориентации.
    private lateinit var sensorManager: SensorManager

    // Датчик поворота телефона.
    // Сначала пробуем TYPE_GAME_ROTATION_VECTOR, потом TYPE_ROTATION_VECTOR.
    private var rotationSensor: Sensor? = null

    // Матрица поворота от Android sensor API.
    private val rotationMatrix = FloatArray(9)

    // Матрица после remapCoordinateSystem.
    // Нужна, потому что телефон в landscape, а оси Android по умолчанию рассчитаны на portrait.
    private val remappedRotationMatrix = FloatArray(9)

    // yaw, pitch, roll после SensorManager.getOrientation().
    private val orientationAngles = FloatArray(3)

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate()")

        // Принудительно переводим приложение в landscape.
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE

        // Разрешаем multicast-пакеты по Wi-Fi.
        acquireMulticastLock()

        // Загружаем layout через ViewBinding.
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        /*
         * Инициализация датчиков.
         *
         * TYPE_GAME_ROTATION_VECTOR предпочтительнее:
         * он обычно не использует магнитометр и меньше "прыгает".
         *
         * Если его нет, берём TYPE_ROTATION_VECTOR.
         */
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor =
            sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        Log.d(TAG, "rotationSensor=${rotationSensor?.name}")

        /*
         * Создаём видеоплеер.
         *
         * videoSizeChangedListener = this означает,
         * что MainActivity получит callback onVideoSizeChanged().
         */
        videoTexturePlayer = VideoTexturePlayer(
            context = this,
            videoSizeChangedListener = this
        )

        /*
         * Создаём Controller.
         *
         * Он будет вызываться из native/C++ или UI,
         * чтобы выполнить действия кнопок:
         * громкость, перемотка и т.д.
         */
        controller = Controller(
            getSystemService(AudioManager::class.java),
            videoTexturePlayer
        )

        /*
         * Инициализируем native C++ часть.
         *
         * Передаём:
         * - Activity;
         * - assets;
         * - videoTexturePlayer;
         * - controller.
         *
         * В ответ получаем nativeApp — указатель на C++ объект.
         */
        nativeApp = NativeLibrary.nativeInit(
            this,
            assets,
            videoTexturePlayer,
            controller
        )

        /*
         * Сразу задаём стартовый режим:
         * Mono + Equirect360 + CardboardStereo.
         *
         * Это делается до первого кадра.
         */
        forceStart360CardboardDirect()

        /*
         * Настройка GLSurfaceView.
         *
         * OpenGL ES 3.0.
         */
        glView = binding.surfaceView
        glView.setEGLContextClientVersion(3)

        /*
         * Назначаем Renderer.
         *
         * Этот Renderer — Kotlin-обёртка.
         * Реальная отрисовка всё равно происходит в C++ через NativeLibrary.
         */
        val renderer = Renderer()
        glView.setRenderer(renderer)

        // Постоянно перерисовываем сцену.
        glView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        /*
         * Запоминаем координаты касания.
         *
         * Сейчас координаты только сохраняются.
         * В этом файле они дальше явно не используются.
         */
        glView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                lastTouchCoordinates[0] = event.x
                lastTouchCoordinates[1] = event.y
            }
            false
        }

        /*
         * Тестовый клик по экрану.
         *
         * При клике отправляем команду повернуть взгляд на yaw=90.
         * Это диагностический тест:
         * если поворот работает, значит nativeLookAtPoint и Renderer.cpp живые.
         *
         * В рабочей версии можно убрать или закомментировать.
         */
        glView.setOnClickListener {
            lookAtFromServer(
                yaw = 90f,
                pitch = 0f,
                radius = 150f,
                duration = 1000
            )
        }

        // Уведомляем C++ часть, что Activity возобновлена.
        NativeLibrary.nativeOnResume(nativeApp)

        /*
         * Повторно задаём режим Cardboard уже через GL-поток.
         *
         * Это важный фикс:
         * иногда C++ Renderer после nativeOnResume может остаться
         * в своём дефолтном режиме MONO_LEFT / PLAIN_FOV.
         */
        forceStart360CardboardOnGlThread()

        /*
         * Создаём WebSocket-клиент для управления от сервера.
         *
         * Сервер может прислать:
         * - команду повернуть взгляд;
         * - команду сменить режим видео.
         */
        teacherControlClient = TeacherControlClient(
            wsUrl = "ws://$serverIp:$vrWsPort/vr-view-ws",

            // Команда от сервера: посмотреть в конкретную точку.
            onLookAt = { yaw: Float, pitch: Float, radius: Float, duration: Int ->
                lookAtFromServer(
                    yaw = yaw,
                    pitch = pitch,
                    radius = radius,
                    duration = duration
                )
            },

            // Команда от сервера: сменить режим видео.
            onVideoMode = { newInputLayout: InputLayout,
                            newInputMode: InputMode,
                            newOutputMode: OutputMode ->
                applyVideoModeFromServer(
                    newInputLayout = newInputLayout,
                    newInputMode = newInputMode,
                    newOutputMode = newOutputMode
                )
            }
        )

        // Запускаем WebSocket.
        teacherControlClient?.start()

        // Прячем системные панели Android.
        enterImmersiveMode()

        // Максимальная яркость экрана.
        val layout = window.attributes
        layout.screenBrightness = 1f
        window.attributes = layout

        // Не даём экрану выключаться.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Кнопки громкости телефона будут управлять громкостью видео/музыки.
        volumeControlStream = AudioManager.STREAM_MUSIC

        Log.d(
            TAG,
            "Started with inputLayout=$inputLayout inputMode=$inputMode outputMode=$outputMode"
        )
    }

    /**
     * Принудительно включает стартовый режим напрямую.
     *
     * Вызывается сразу после nativeInit().
     */
    private fun forceStart360CardboardDirect() {
        inputLayout = InputLayout.Mono
        inputMode = InputMode.Equirect360
        outputMode = OutputMode.CardboardStereo

        if (nativeApp != 0L) {
            NativeLibrary.nativeSetOptions(
                nativeApp,
                inputLayout.ordinal,
                inputMode.ordinal,
                outputMode.ordinal
            )

            Log.d(TAG, "FORCE START MODE DIRECT: $inputLayout $inputMode $outputMode")
        }
    }

    /**
     * Принудительно включает Cardboard-режим через GL-поток.
     *
     * Важно:
     * Вызовы, влияющие на OpenGL/C++ Renderer, безопаснее делать через glView.queueEvent.
     */
    private fun forceStart360CardboardOnGlThread() {
        inputLayout = InputLayout.Mono
        inputMode = InputMode.Equirect360
        outputMode = OutputMode.CardboardStereo

        if (!::glView.isInitialized) {
            Log.w(TAG, "forceStart360CardboardOnGlThread ignored: glView not initialized")
            return
        }

        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeSetOptions(
                    nativeApp,
                    inputLayout.ordinal,
                    inputMode.ordinal,
                    outputMode.ordinal
                )

                Log.d(TAG, "FORCE START MODE ON GL: $inputLayout $inputMode $outputMode")
            }
        }
    }

    /**
     * Закрыть плеер.
     *
     * Обычно вызывается кнопкой из XML через android:onClick.
     */
    fun closePlayer(view: View) {
        finish()
    }

    /**
     * Показать меню настроек.
     *
     * Здесь пользователь может выбрать:
     * - layout видео;
     * - геометрию видео;
     * - режим вывода;
     * - сканирование QR Cardboard.
     */
    fun showSettings(view: View) {
        val popup = PopupMenu(this, view)
        val inflater: MenuInflater = popup.menuInflater

        inflater.inflate(R.menu.settings_menu, popup.menu)
        MenuCompat.setGroupDividerEnabled(popup.menu, true)

        // Отмечаем текущие пункты меню галочками.
        popup.menu.findItem(inputMode.menuItemId())?.isChecked = true
        popup.menu.findItem(inputLayout.menuItemId())?.isChecked = true
        popup.menu.findItem(outputMode.menuItemId())?.isChecked = true

        popup.setOnMenuItemClickListener { item: MenuItem ->
            when (item.itemId) {
                R.id.switch_viewer -> {
                    // Открыть сканирование QR-кода Cardboard.
                    NativeLibrary.nativeScanCardboardQr(nativeApp)
                    true
                }

                R.id.input_layout_mono -> {
                    setInputLayout(InputLayout.Mono, item)
                    true
                }

                R.id.input_layout_stereo_horiz -> {
                    setInputLayout(InputLayout.StereoHoriz, item)
                    true
                }

                R.id.input_layout_stereo_vert -> {
                    setInputLayout(InputLayout.StereoVert, item)
                    true
                }

                R.id.input_layout_anaglyph_red_cyan -> {
                    setInputLayout(InputLayout.AnaglyphRedCyan, item)
                    true
                }

                R.id.input_mode_plain_fov -> {
                    setInputMode(InputMode.PlainFov, item)
                    true
                }

                R.id.input_mode_equirect_180 -> {
                    setInputMode(InputMode.Equirect180, item)
                    true
                }

                R.id.input_mode_equirect_360 -> {
                    setInputMode(InputMode.Equirect360, item)
                    true
                }

                R.id.input_mode_panorama_180 -> {
                    setInputMode(InputMode.Panorama180, item)
                    true
                }

                R.id.input_mode_panorama_360 -> {
                    setInputMode(InputMode.Panorama360, item)
                    true
                }

                R.id.output_mode_mono_left_eye -> {
                    setOutputMode(OutputMode.MonoLeft, item)
                    true
                }

                R.id.output_mode_mono_right_eye -> {
                    setOutputMode(OutputMode.MonoRight, item)
                    true
                }

                R.id.output_mode_cardboard -> {
                    setOutputMode(OutputMode.CardboardStereo, item)
                    true
                }

                else -> false
            }
        }

        popup.show()
    }

    /**
     * Переводит radius от сервера в FOV.
     *
     * radius 0   -> широкий угол примерно 120°
     * radius 150 -> обычный угол примерно 90°
     * radius 300 -> узкий угол примерно 35°
     */
    private fun radiusToFov(radius: Float): Float {
        val r = radius.coerceIn(0f, 300f)

        return if (r <= 150f) {
            val t = r / 150f
            120f + (90f - 120f) * t
        } else {
            val t = (r - 150f) / 150f
            90f + (35f - 90f) * t
        }
    }

    /**
     * Команда от сервера: повернуть взгляд на точку.
     *
     * yaw — горизонтальный угол.
     * pitch — вертикальный угол.
     * radius — условная дистанция/зум, переводится в FOV.
     * duration — длительность анимации поворота.
     */
    private fun lookAtFromServer(
        yaw: Float,
        pitch: Float,
        radius: Float,
        duration: Int
    ) {
        if (nativeApp == 0L) {
            Log.w(TAG, "lookAtFromServer ignored: nativeApp=0")
            return
        }

        if (!::glView.isInitialized) {
            Log.w(TAG, "lookAtFromServer ignored: glView not initialized")
            return
        }

        val fov = radiusToFov(radius)

        Log.d(
            TAG,
            "lookAtFromServer RECEIVED yaw=$yaw pitch=$pitch radius=$radius fov=$fov duration=$duration"
        )

        /*
         * Важно:
         * Renderer работает в GL-потоке.
         * Поэтому nativeLookAtPoint вызываем через queueEvent.
         */
        glView.queueEvent {
            if (nativeApp != 0L) {
                Log.d(
                    TAG,
                    "lookAtFromServer SEND TO GL yaw=$yaw pitch=$pitch radius=$radius fov=$fov duration=$duration"
                )

                NativeLibrary.nativeLookAtPoint(
                    nativeApp,
                    yaw,
                    pitch,
                    fov,
                    duration
                )
            }
        }
    }

    /**
     * Команда от сервера: поменять режим видео.
     *
     * Например:
     * mono 360 cardboard,
     * stereo_horiz 180 cardboard,
     * stereo_vert 360 cardboard и т.д.
     */
    private fun applyVideoModeFromServer(
        newInputLayout: InputLayout,
        newInputMode: InputMode,
        newOutputMode: OutputMode
    ) {
        if (nativeApp == 0L) {
            Log.w(TAG, "applyVideoModeFromServer ignored: nativeApp=0")
            return
        }

        if (!::glView.isInitialized) {
            Log.w(TAG, "applyVideoModeFromServer ignored: glView not initialized")
            return
        }

        // Проверяем, действительно ли режим изменился.
        val changed =
            inputLayout != newInputLayout ||
                    inputMode != newInputMode ||
                    outputMode != newOutputMode

        if (!changed) {
            Log.d(TAG, "applyVideoModeFromServer ignored: already $inputLayout $inputMode $outputMode")
            return
        }

        // Сохраняем новое состояние.
        inputLayout = newInputLayout
        inputMode = newInputMode
        outputMode = newOutputMode

        Log.d(TAG, "applyVideoModeFromServer RECEIVED: $inputLayout $inputMode $outputMode")

        // Применяем новый режим в C++ Renderer через GL-поток.
        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeSetOptions(
                    nativeApp,
                    inputLayout.ordinal,
                    inputMode.ordinal,
                    outputMode.ordinal
                )

                Log.d(TAG, "applyVideoModeFromServer SEND TO GL: $inputLayout $inputMode $outputMode")
            }
        }
    }

    /**
     * Пользователь выбрал новый InputLayout из меню.
     */
    private fun setInputLayout(newLayout: InputLayout, menuItem: MenuItem) {
        if (newLayout == inputLayout) return

        inputLayout = newLayout
        setNativeOptionsOnGlThread()
        menuItem.isChecked = true

        Log.d(TAG, "setInputLayout: $inputLayout")
    }

    /**
     * Пользователь выбрал новый InputMode из меню.
     */
    private fun setInputMode(newMode: InputMode, menuItem: MenuItem) {
        if (newMode == inputMode) return

        inputMode = newMode
        setNativeOptionsOnGlThread()
        menuItem.isChecked = true

        Log.d(TAG, "setInputMode: $inputMode")
    }

    /**
     * Пользователь выбрал новый OutputMode из меню.
     */
    private fun setOutputMode(newMode: OutputMode, menuItem: MenuItem) {
        if (newMode == outputMode) return

        outputMode = newMode
        setNativeOptionsOnGlThread()
        menuItem.isChecked = true

        Log.d(TAG, "setOutputMode: $outputMode")
    }

    /**
     * Передать текущие настройки видео в C++ Renderer.
     *
     * Делается через GL-поток.
     */
    private fun setNativeOptionsOnGlThread() {
        if (nativeApp == 0L || !::glView.isInitialized) return

        glView.queueEvent {
            if (nativeApp != 0L) {
                NativeLibrary.nativeSetOptions(
                    nativeApp,
                    inputLayout.ordinal,
                    inputMode.ordinal,
                    outputMode.ordinal
                )

                Log.d(TAG, "setNativeOptionsOnGlThread: $inputLayout $inputMode $outputMode")
            }
        }
    }

    /**
     * Activity вернулась на экран.
     */
    override fun onResume() {
        super.onResume()

        Log.d(TAG, "onResume()")

        // Сообщаем C++ части, что приложение активно.
        if (nativeApp != 0L) {
            NativeLibrary.nativeOnResume(nativeApp)
        }

        // Регистрируем датчик ориентации телефона.
        rotationSensor?.let { sensor ->
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_GAME
            )

            Log.d(TAG, "Rotation sensor registered: ${sensor.name}")
        } ?: run {
            Log.e(TAG, "No rotation sensor found")
        }

        // Возобновляем OpenGL.
        glView.onResume()

        // Возобновляем видеоплеер.
        videoTexturePlayer.onResume()
    }

    /**
     * Activity ушла в паузу.
     */
    override fun onPause() {
        Log.d(TAG, "onPause()")

        // Отключаем датчики, чтобы не тратить батарею.
        sensorManager.unregisterListener(this)

        // Ставим видео на паузу.
        videoTexturePlayer.onPause()

        // Сообщаем C++ части о паузе.
        if (nativeApp != 0L) {
            NativeLibrary.nativeOnPause(nativeApp)
        }

        // Ставим OpenGL-поверхность на паузу.
        glView.onPause()

        super.onPause()
    }

    /**
     * Activity уничтожается.
     *
     * Здесь важно освободить:
     * - WebSocket;
     * - датчики;
     * - видеоплеер;
     * - native C++ объект;
     * - multicast lock.
     */
    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")

        teacherControlClient?.stop()
        teacherControlClient = null

        try {
            sensorManager.unregisterListener(this)
        } catch (_: Throwable) {
        }

        try {
            videoTexturePlayer.onDestroy()
        } catch (e: Throwable) {
            Log.e(TAG, "videoTexturePlayer.onDestroy error", e)
        }

        if (nativeApp != 0L) {
            NativeLibrary.nativeOnDestroy(nativeApp)
            nativeApp = 0L
        }

        releaseMulticastLock()
        super.onDestroy()
    }

    /**
     * Callback от MediaPlayer, когда изменился размер видео.
     *
     * Передаём размер в C++ Renderer, чтобы он правильно рассчитал текстуры.
     */
    override fun onVideoSizeChanged(mp: MediaPlayer?, width: Int, height: Int) {
        Log.d(TAG, "onVideoSizeChanged width=$width height=$height")

        if (nativeApp != 0L) {
            NativeLibrary.nativeOnVideoSizeChanged(nativeApp, width, height)
        }
    }

    /**
     * Изменилась точность датчика.
     */
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Log.d(TAG, "Sensor accuracy changed: ${sensor?.name}, accuracy=$accuracy")
    }

    /**
     * Пришло новое значение с датчика ориентации.
     *
     * Важно:
     * Для CardboardStereo лучше использовать CardboardHeadTracker в C++.
     * Этот блок больше нужен для fallback-режимов MonoLeft/MonoRight.
     */
    override fun onSensorChanged(event: SensorEvent) {
        if (nativeApp == 0L) return

        // Игнорируем все датчики, кроме rotation vector.
        if (
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_ROTATION_VECTOR
        ) {
            return
        }

        // Получаем матрицу поворота из rotation vector.
        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        /*
         * Переназначаем оси под landscape-режим.
         *
         * Это очень важное место.
         * Если вправо/влево или вверх/вниз перепутаны,
         * в первую очередь проверяется именно remapCoordinateSystem().
         */
        SensorManager.remapCoordinateSystem(
            rotationMatrix,
            SensorManager.AXIS_Y,
            SensorManager.AXIS_MINUS_X,
            remappedRotationMatrix
        )

        // Получаем yaw, pitch, roll из remappedRotationMatrix.
        SensorManager.getOrientation(remappedRotationMatrix, orientationAngles)

        var yaw = orientationAngles[0]
        var pitch = orientationAngles[1]
        var roll = orientationAngles[2]

        // Усиливаем чувствительность.
        yaw *= YAW_SENSITIVITY
        pitch *= PITCH_SENSITIVITY
        roll *= ROLL_SENSITIVITY

        // Ограничиваем pitch, чтобы не было чрезмерного запрокидывания.
        pitch = pitch.coerceIn(-1.45f, 1.45f)

        /*
         * Передаём ручной поворот в native Renderer.
         *
         * В CardboardStereo этот поворот, скорее всего, не должен быть главным,
         * потому что там должен работать CardboardHeadTracker.
         *
         * Поэтому если в Cardboard есть конфликт/дёргание/зеркальность,
         * можно временно отключить этот вызов для CardboardStereo.
         */
        NativeLibrary.nativeSetManualRotation(
            nativeApp,
            yaw,
            pitch,
            roll
        )
    }

    /**
     * Включает multicast lock.
     *
     * Это нужно, чтобы Android/Wi-Fi не фильтровал multicast-пакеты.
     * Актуально, если видео приходит по multicast RTP.
     */
    private fun acquireMulticastLock() {
        try {
            val wifiManager =
                applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            multicastLock = wifiManager.createMulticastLock("vr_multicast_lock").apply {
                setReferenceCounted(true)
                acquire()
            }

            Log.d(TAG, "MulticastLock acquired")
        } catch (e: Throwable) {
            Log.e(TAG, "MulticastLock error", e)
        }
    }

    /**
     * Освобождает multicast lock.
     */
    private fun releaseMulticastLock() {
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                }
            }

            Log.d(TAG, "MulticastLock released")
        } catch (e: Throwable) {
            Log.e(TAG, "releaseMulticastLock error", e)
        }

        multicastLock = null
    }

    /**
     * Включает полноэкранный immersive-режим.
     *
     * Прячет:
     * - status bar;
     * - navigation bar.
     *
     * Пользователь может временно вызвать панели свайпом.
     */
    private fun enterImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Kotlin-обёртка над GLSurfaceView.Renderer.
     *
     * Реальная OpenGL-отрисовка находится в C++.
     * Здесь мы только прокидываем события жизненного цикла поверхности.
     */
    private inner class Renderer : GLSurfaceView.Renderer {

        /**
         * OpenGL-поверхность создана.
         */
        override fun onSurfaceCreated(gl10: GL10?, config: EGLConfig?) {
            Log.d(TAG, "Renderer.onSurfaceCreated")

            if (nativeApp != 0L) {
                NativeLibrary.nativeOnSurfaceCreated(nativeApp)
            }
        }

        /**
         * Изменился размер OpenGL-поверхности.
         */
        override fun onSurfaceChanged(gl10: GL10?, width: Int, height: Int) {
            Log.d(TAG, "Renderer.onSurfaceChanged width=$width height=$height")

            if (nativeApp != 0L) {
                NativeLibrary.nativeSetScreenParams(nativeApp, width, height)
            }
        }

        /**
         * Отрисовка одного кадра.
         *
         * Здесь:
         * 1. обновляем видео-текстуру, если появился новый кадр;
         * 2. вызываем C++ Renderer для отрисовки VR-сцены.
         */
        override fun onDrawFrame(gl10: GL10?) {
            videoTexturePlayer.updateIfNeeded()

            if (nativeApp != 0L) {
                NativeLibrary.nativeDrawFrame(
                    nativeApp,
                    videoTexturePlayer.getVideoPosition()
                )
            }
        }
    }
}