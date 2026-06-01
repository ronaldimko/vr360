package cz.mormegil.vrvideoplayer

import android.content.Context
import android.content.res.AssetManager

/**
 * NativeLibrary — объект-связка между Kotlin/Android и C++ кодом.
 *
 * Все функции с ключевым словом external реализованы НЕ в Kotlin,
 * а в C++ через JNI.
 *
 * Kotlin вызывает, например:
 * NativeLibrary.nativeDrawFrame(...)
 *
 * А реально выполнение происходит в C++ функции вида:
 * Java_cz_mormegil_vrvideoplayer_NativeLibrary_nativeDrawFrame(...)
 */
object NativeLibrary {

    /**
     * Инициализация нативной C++ части приложения.
     *
     * Обычно внутри C++ здесь создаётся главный объект приложения/рендера,
     * например Renderer или Application.
     *
     * Возвращает Long — это указатель на C++ объект.
     * Потом этот указатель передаётся во все остальные native-функции.
     *
     * context — Android Context.
     * assetManager — доступ к assets приложения.
     * videoTexturePlayer — Kotlin/Java объект видеоплеера.
     * controller — Kotlin/Java объект контроллера кнопок.
     */
    external fun nativeInit(
        context: Context,
        assetManager: AssetManager,
        videoTexturePlayer: VideoTexturePlayer,
        controller: Controller
    ): Long

    /**
     * Сообщает C++ части, что Activity возобновила работу.
     *
     * Обычно вызывается из MainActivity.onResume().
     */
    external fun nativeOnResume(nativeApp: Long)

    /**
     * Сообщает C++ части, что Activity ушла в паузу.
     *
     * Обычно вызывается из MainActivity.onPause().
     */
    external fun nativeOnPause(nativeApp: Long)

    /**
     * Уничтожение C++ объекта.
     *
     * Важно вызывать при закрытии Activity,
     * чтобы освободить память, OpenGL-ресурсы, Cardboard API и т.д.
     */
    external fun nativeOnDestroy(nativeApp: Long)

    /**
     * Вызывается, когда GLSurfaceView создал OpenGL-поверхность.
     *
     * Здесь C++ обычно инициализирует:
     * - OpenGL;
     * - текстуры;
     * - шейдеры;
     * - mesh сферы;
     * - Cardboard renderer.
     */
    external fun nativeOnSurfaceCreated(nativeApp: Long)

    /**
     * Передаёт в C++ размер экрана/поверхности.
     *
     * width — ширина GLSurfaceView.
     * height — высота GLSurfaceView.
     *
     * Нужно для правильного viewport, aspect ratio и Cardboard-разделения экрана.
     */
    external fun nativeSetScreenParams(nativeApp: Long, width: Int, height: Int)

    /**
     * Передаёт в C++ размер видео.
     *
     * width — ширина видеофайла/потока.
     * height — высота видеофайла/потока.
     *
     * Нужно, чтобы правильно построить UV-координаты,
     * stereo split, 180/360 projection и т.д.
     */
    external fun nativeOnVideoSizeChanged(nativeApp: Long, width: Int, height: Int)

    /**
     * Запускает сканирование QR-кода Cardboard.
     *
     * Это стандартный способ выбрать профиль очков:
     * - расстояние между линзами;
     * - искажение линз;
     * - FOV;
     * - параметры экрана.
     */
    external fun nativeScanCardboardQr(nativeApp: Long)

    /**
     * Показать progress bar.
     *
     * Судя по названию, C++ может вызвать отображение индикатора загрузки.
     * Надо смотреть реализацию в JNI/C++ — возможно, сейчас не используется.
     */
    external fun nativeShowProgressBar(nativeApp: Long)

    /**
     * Установка режима видео и вывода.
     *
     * inputLayout:
     * - Mono;
     * - StereoHoriz;
     * - StereoVert;
     * - AnaglyphRedCyan.
     *
     * inputMode:
     * - PlainFov;
     * - Equirect180;
     * - Equirect360;
     * - Panorama180;
     * - Panorama360 и т.д.
     *
     * outputMode:
     * - MonoLeft;
     * - MonoRight;
     * - CardboardStereo.
     *
     * Важно:
     * Здесь передаются ordinal enum-ов.
     * Поэтому порядок enum class в Kotlin должен совпадать
     * с enum class в C++.
     */
    external fun nativeSetOptions(
        nativeApp: Long,
        inputLayout: Int,
        inputMode: Int,
        outputMode: Int
    )

    /**
     * Отрисовать один кадр.
     *
     * Обычно вызывается каждый кадр из GLSurfaceView.Renderer.onDrawFrame().
     *
     * videoPosition — текущая позиция видео.
     * Она может использоваться для таймкодов, GUI, текста, точек, синхронизации.
     */
    external fun nativeDrawFrame(
        nativeApp: Long,
        videoPosition: Float
    )

    /**
     * Передаёт ручной поворот камеры из Android SensorManager.
     *
     * yaw — поворот вправо/влево.
     * pitch — вверх/вниз.
     * roll — наклон головы вбок.
     *
     * Важно:
     * Для CardboardStereo лучше использовать CardboardHeadTracker в C++.
     * Эта функция больше подходит для fallback-режимов MonoLeft/MonoRight.
     */
    external fun nativeSetManualRotation(
        nativeApp: Long,
        yaw: Float,
        pitch: Float,
        roll: Float
    )

    /**
     * Команда повернуть камеру/взгляд на конкретную точку.
     *
     * Используется для управления с сервера:
     * например, учитель отправляет команду посмотреть на yaw/pitch.
     *
     * yawDeg — горизонтальный угол в градусах.
     * pitchDeg — вертикальный угол в градусах.
     * fovDeg — угол обзора/зум.
     * durationMs — длительность плавного поворота в миллисекундах.
     */
    external fun nativeLookAtPoint(
        nativeApp: Long,
        yawDeg: Float,
        pitchDeg: Float,
        fovDeg: Float,
        durationMs: Int
    )

    /**
     * Загружает C++ библиотеку.
     *
     * Имя "vrvideoplayer" соответствует файлу:
     * libvrvideoplayer.so
     *
     * Без этого вызова external-функции работать не будут.
     */
    init {
        System.loadLibrary("vrvideoplayer")
    }
}

/**
 * InputLayout описывает, как видео хранит левый/правый глаз.
 *
 * Очень важно:
 * ordinal этого enum передаётся в C++.
 *
 * Поэтому порядок значений должен совпадать с C++ enum:
 *
 * Kotlin:
 * None = 0
 * Mono = 1
 * StereoHoriz = 2
 * StereoVert = 3
 * AnaglyphRedCyan = 4
 *
 * C++ должен ожидать такие же числа.
 */
enum class InputLayout {

    /**
     * Нет режима.
     *
     * Используется как служебное значение.
     * В меню его показывать нельзя.
     */
    None {
        override fun menuItemId(): Int = throw IllegalArgumentException()
    },

    /**
     * Обычное mono-видео.
     *
     * Один кадр используется для обоих глаз.
     */
    Mono {
        override fun menuItemId(): Int = R.id.input_layout_mono
    },

    /**
     * Стерео-видео, где левый и правый глаз расположены горизонтально.
     *
     * Обычно:
     * левая половина кадра — левый глаз,
     * правая половина кадра — правый глаз.
     */
    StereoHoriz {
        override fun menuItemId(): Int = R.id.input_layout_stereo_horiz
    },

    /**
     * Стерео-видео, где левый и правый глаз расположены вертикально.
     *
     * Обычно:
     * верхняя половина кадра — один глаз,
     * нижняя половина кадра — второй глаз.
     */
    StereoVert {
        override fun menuItemId(): Int = R.id.input_layout_stereo_vert
    },

    /**
     * Анаглифное видео red/cyan.
     *
     * Старый формат 3D через красно-голубые очки.
     */
    AnaglyphRedCyan {
        override fun menuItemId(): Int = R.id.input_layout_anaglyph_red_cyan
    };

    /**
     * Возвращает ID пункта меню, который соответствует этому режиму.
     *
     * Например:
     * InputLayout.Mono -> R.id.input_layout_mono
     */
    abstract fun menuItemId(): Int
}

/**
 * InputMode описывает геометрию видео.
 *
 * То есть не stereo/mono, а именно:
 * - обычная плоскость;
 * - 180°;
 * - 360°;
 * - panorama;
 * - cube map и т.д.
 *
 * ordinal этого enum тоже передаётся в C++.
 * Порядок должен совпадать с C++ enum InputVideoMode.
 */
enum class InputMode {

    /**
     * Служебное значение.
     * В меню не используется.
     */
    None {
        override fun menuItemId(): Int = throw IllegalArgumentException()
    },

    /**
     * Обычное видео на плоскости с заданным FOV.
     *
     * Не полноценная сфера.
     */
    PlainFov {
        override fun menuItemId(): Int = R.id.input_mode_plain_fov
    },

    /**
     * Эквиректангулярное 180° видео.
     *
     * Видео натягивается на полусферу.
     */
    Equirect180 {
        override fun menuItemId(): Int = R.id.input_mode_equirect_180
    },

    /**
     * Эквиректангулярное 360° видео.
     *
     * Самый обычный формат VR360.
     * Видео натягивается на полную сферу.
     */
    Equirect360 {
        override fun menuItemId(): Int = R.id.input_mode_equirect_360
    },

    /**
     * Cube map.
     *
     * В меню сейчас не добавлен.
     * Если выбрать menuItemId(), будет ошибка.
     */
    CubeMap {
        override fun menuItemId(): Int = throw IllegalArgumentException()
    },

    /**
     * Equi-angular cube map.
     *
     * В меню сейчас не добавлен.
     */
    EquiangCubeMap {
        override fun menuItemId(): Int = throw IllegalArgumentException()
    },

    /**
     * Pyramid projection.
     *
     * В меню сейчас не добавлен.
     */
    Pyramid {
        override fun menuItemId(): Int = throw IllegalArgumentException()
    },

    /**
     * Панорама 180°.
     *
     * Отличается от Equirect180 тем,
     * как C++ renderer строит геометрию/UV.
     */
    Panorama180 {
        override fun menuItemId(): Int = R.id.input_mode_panorama_180
    },

    /**
     * Панорама 360°.
     */
    Panorama360 {
        override fun menuItemId(): Int = R.id.input_mode_panorama_360
    };

    /**
     * Возвращает ID пункта меню для режима видео.
     */
    abstract fun menuItemId(): Int
}

/**
 * OutputMode описывает, как показывать изображение пользователю.
 *
 * Это не формат исходного видео,
 * а именно режим вывода на экран.
 */
enum class OutputMode {

    /**
     * Служебное значение.
     */
    None {
        override fun menuItemId(): Int = throw IllegalArgumentException()
    },

    /**
     * Показывать только левый глаз.
     *
     * Удобно для отладки без Cardboard.
     */
    MonoLeft {
        override fun menuItemId(): Int = R.id.output_mode_mono_left_eye
    },

    /**
     * Показывать только правый глаз.
     *
     * Тоже отладочный режим.
     */
    MonoRight {
        override fun menuItemId(): Int = R.id.output_mode_mono_right_eye
    },

    /**
     * Полноценный Cardboard stereo.
     *
     * Экран делится на две части:
     * - левый глаз;
     * - правый глаз.
     *
     * Плюс применяется коррекция линз Cardboard.
     */
    CardboardStereo {
        override fun menuItemId(): Int = R.id.output_mode_cardboard
    };

    /**
     * Возвращает ID пункта меню для режима вывода.
     */
    abstract fun menuItemId(): Int
}