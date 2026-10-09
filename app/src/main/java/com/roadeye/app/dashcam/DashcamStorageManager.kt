package com.roadeye.app.dashcam

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import java.io.File

private const val TAG = "RoadEyeDashcam"

enum class ClipKind { NORMAL, FAVORITE }

/**
 * Управление файлами видеорегистратора на SD.
 *
 * Политика избранного — DEFAULT, НЕ подтверждена пользователем (один из
 * трёх открытых вопросов): фиксированная под-квота (DashcamConfig.
 * FAVORITES_QUOTA_BYTES), при переполнении вытесняется САМОЕ СТАРОЕ
 * избранное. Если нужна другая политика ("отклонять новые" или "не
 * ограничивать избранное") — меняется только enforceDirQuota() ниже.
 *
 * ПУТЬ НА SD — UNKNOWN, нужно перепроверить на устройстве: используется
 * getExternalFilesDirs() (app-specific директория, НЕ требует
 * WRITE_EXTERNAL_STORAGE/MANAGE_EXTERNAL_STORAGE на Android 10+) — это
 * самый совместимый путь без доп. разрешений, но файлы в ней недоступны
 * напрямую другим приложениям/через MTP без явного showcase. Если нужен
 * "публичный" доступ к видео с планшета — потребуется
 * MANAGE_EXTERNAL_STORAGE и пересмотр этого класса.
 */
class DashcamStorageManager(private val context: Context) {

    // Кандидаты в порядке приоритета: сначала СЪЁМНЫЕ (SD-карта), потом
    // ВСТРОЕННЫЕ (внутренняя память как "external" раздел) — но
    // ФАКТИЧЕСКИ СМОНТИРОВАННЫЕ (MEDIA_MOUNTED), не просто присутствующие
    // в списке. Раньше проверки на MEDIA_MOUNTED не было — SD-карта,
    // числящаяся в списке, но реально вынутая/повреждённая/не
    // смонтированная, могла быть выбрана и тут же дать сбои записи вместо
    // явного отката на внутреннюю память.
    private val baseDir: File? = run {
        val candidates = context.getExternalFilesDirs(null).filterNotNull()

        val removable = candidates.firstOrNull {
            Environment.isExternalStorageRemovable(it) && isMounted(it)
        }
        if (removable != null) {
            Log.i(TAG, "Dashcam storage: removable SD card at ${removable.absolutePath}")
            return@run removable
        }

        val internal = candidates.firstOrNull { isMounted(it) }
        if (internal != null) {
            Log.i(TAG, "Dashcam storage: SD card недоступна, используется встроенная память: ${internal.absolutePath}")
            return@run internal
        }

        Log.e(TAG, "Dashcam storage: ни один external-раздел не смонтирован (candidates=${candidates.size})")
        null
    }

    private val normalDir = baseDir?.let {
        File(it, "${DashcamConfig.DASHCAM_DIR_NAME}/${DashcamConfig.NORMAL_SUBDIR}")
    }
    private val favoritesDir = baseDir?.let {
        File(it, "${DashcamConfig.DASHCAM_DIR_NAME}/${DashcamConfig.FAVORITES_SUBDIR}")
    }

    init {
        normalDir?.mkdirs()
        favoritesDir?.mkdirs()
        if (baseDir == null) Log.e(TAG, "No external storage available for dashcam")
    }

    private fun isMounted(dir: File): Boolean =
        Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED

    fun isAvailable(): Boolean = baseDir != null

    fun newOutputFile(kind: ClipKind): File? {
        val dir = when (kind) {
            ClipKind.NORMAL -> normalDir
            ClipKind.FAVORITE -> favoritesDir
        } ?: return null
        return File(dir, "roadeye_${System.currentTimeMillis()}.mp4")
    }

    /** Вызывать ПОСЛЕ успешной записи файла — применяет политику квоты. */
    fun enforceQuota(kind: ClipKind) {
        when (kind) {
            ClipKind.NORMAL -> enforceDirQuota(normalDir, DashcamConfig.NORMAL_QUOTA_BYTES)
            ClipKind.FAVORITE -> enforceDirQuota(favoritesDir, DashcamConfig.FAVORITES_QUOTA_BYTES)
        }
    }

    /**
     * Просит систему проиндексировать файл для MediaStore — иначе
     * стандартные приложения вроде Галереи не видят файлы в app-private
     * директории (см. комментарий класса про путь на SD) и могут
     * показывать их как "0 секунд" или не находить вовсе (подтверждено
     * пользователем на реальном устройстве — VLC читает файл с диска
     * напрямую и играет нормально, Галерея — нет). НЕ ПОДТВЕРЖДЕНО, что
     * сканирование app-private папки реально приводит к появлению файла
     * в Галерее на этой версии Android/прошивке — это HYPOTHESIS, но
     * стоит копейки и ничего не ломает, если не сработает.
     */
    fun requestMediaScan(file: File) {
        try {
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
        } catch (e: Exception) {
            Log.e(TAG, "MediaScannerConnection.scanFile failed for ${file.name}", e)
        }
    }

    private fun enforceDirQuota(dir: File?, quotaBytes: Long) {
        if (dir == null) return
        val files = dir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var totalSize = files.sumOf { it.length() }
        while (totalSize > quotaBytes && files.isNotEmpty()) {
            val oldest = files.removeAt(0)
            totalSize -= oldest.length()
            val deleted = oldest.delete()
            Log.i(TAG, "Quota exceeded in ${dir.name}, deleted ${oldest.name} (deleted=$deleted)")
        }
    }
}