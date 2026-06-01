package cz.mormegil.vrvideoplayer

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts

/**
 * StartupActivity — стартовый экран приложения.
 *
 * Его задача:
 * 1. понять, как приложение было открыто;
 * 2. если видео передали извне — сразу открыть его;
 * 3. если видео не передали — открыть выбор видео из галереи;
 * 4. после выбора видео запустить MainActivity.
 *
 * Эта Activity сама видео не воспроизводит.
 * Она только получает Uri видео и передаёт его в MainActivity.
 */
class StartupActivity : ComponentActivity() {

    companion object {
        // TAG для логов в Logcat.
        private const val TAG = "VRVideoPlayerS"
    }

    /**
     * Регистрация выбора видео из галереи.
     *
     * ActivityResultContracts.PickVisualMedia() открывает системный выборщик медиа.
     *
     * Когда пользователь выберет видео, Android вызовет функцию:
     *
     * initWithVideo(uri)
     *
     * Если пользователь ничего не выберет, придёт null.
     */
    private val videoGalleryChooser =
        registerForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
            ::initWithVideo
        )

    /**
     * onCreate вызывается при запуске Activity.
     *
     * Здесь приложение проверяет:
     * - запущено ли оно через ACTION_VIEW, то есть через внешний файл/ссылку;
     * - или запущено обычным способом через иконку приложения.
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "onCreate()")

        when (intent.action) {

            /**
             * ACTION_VIEW означает:
             * приложение открыли как просмотрщик файла.
             *
             * Например:
             * - пользователь нажал на видео в файловом менеджере;
             * - другое приложение передало Uri видео;
             * - Android предложил открыть видео через этот VR-плеер.
             */
            Intent.ACTION_VIEW -> {
                val viewUri = intent.data

                if (viewUri != null) {
                    // Если Uri видео есть — сразу запускаем MainActivity с этим видео.
                    initWithVideo(viewUri)
                } else {
                    // Если Uri почему-то нет — открываем выбор видео из галереи.
                    chooseVideoFromGallery()
                }
            }

            /**
             * Любой другой запуск.
             *
             * Обычно это ACTION_MAIN, когда пользователь нажал на иконку приложения.
             *
             * В этом случае видео заранее нет,
             * поэтому открываем выбор видео из галереи.
             */
            else -> {
                chooseVideoFromGallery()
            }
        }
    }

    /**
     * Открывает системный выборщик видео.
     *
     * PickVisualMedia.VideoOnly означает,
     * что пользователь сможет выбрать только видео, не фото.
     */
    private fun chooseVideoFromGallery() {
        videoGalleryChooser.launch(
            PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.VideoOnly
            )
        )
    }

    /**
     * Запускает MainActivity с выбранным видео.
     *
     * videoUri — ссылка на видеофайл.
     * Это может быть:
     * - content://... из галереи;
     * - file://... из файлового менеджера;
     * - другой Uri, если видео передано извне.
     */
    private fun initWithVideo(videoUri: Uri?) {
        if (videoUri == null) {
            /*
             * Пользователь не выбрал видео или Uri не пришёл.
             * В этом случае дальше запускать нечего.
             */
            Log.d(TAG, "No video chosen, aborting")
            finish()

            // Важно: после finish() лучше сделать return,
            // иначе код ниже всё равно продолжит выполняться.
            return
        }

        Log.d(TAG, "initWithVideo: $videoUri")

        /*
         * Создаём Intent для запуска MainActivity.
         *
         * MainActivity потом получит videoUri через intent.data.
         */
        val intent = Intent(this, MainActivity::class.java)

        // Передаём Uri видео в MainActivity.
        intent.data = videoUri

        // Запускаем основной VR-плеер.
        startActivity(intent)

        // Закрываем StartupActivity, чтобы пользователь не вернулся на неё кнопкой Back.
        finish()
    }
}