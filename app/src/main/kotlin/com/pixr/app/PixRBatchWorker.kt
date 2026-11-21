package com.pixr.app

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** État batterie / chauffe lu au moment du traitement. */
object DeviceState {
    data class Battery(val percent: Int, val charging: Boolean)

    fun battery(context: Context): Battery {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val pct = if (level < 0 || scale <= 0) 100 else level * 100 / scale
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return Battery(pct, charging)
    }

    /** PowerManager.currentThermalStatus (API 29+), 0 (aucun) en dessous. */
    fun thermalStatus(context: Context): Int {
        if (Build.VERSION.SDK_INT < 29) return 0
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.currentThermalStatus
    }
}

/**
 * Traitement par lot hors-ligne. Met le lot en pause (retry WorkManager) si batterie < 15 % hors charge
 * ou chauffe sévère ; la reprise repart de la photo suivante (compteur persistant par id de travail).
 */
class PixRBatchWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.Default) { runBatch() }

    private suspend fun runBatch(): Result {
        val paths = inputData.getStringArray(KEY_PATHS) ?: return Result.failure()
        val mode = inputData.getInt(KEY_MODE, 0)
        val factor = inputData.getInt(KEY_FACTOR, 2)
        val grain = inputData.getInt(KEY_GRAIN, 70)
        val format = runCatching { ExportFormat.valueOf(inputData.getString(KEY_FORMAT) ?: "") }
            .getOrDefault(ExportFormat.JPEG98)
        val total = paths.size
        if (!NativeEngine.available || !BatchRules.isValidCount(total)) return Result.failure()

        val prefs = applicationContext.getSharedPreferences("pixr_batch", Context.MODE_PRIVATE)
        val doneKey = "${id}_done"
        val failKey = "${id}_fail"
        var failed = prefs.getInt(failKey, 0)
        val start = prefs.getInt(doneKey, 0)

        for (i in start until total) {
            val battery = DeviceState.battery(applicationContext)
            val pause = BatchRules.pauseReason(battery.percent, battery.charging, DeviceState.thermalStatus(applicationContext))
            if (pause != null) {
                prefs.edit().putInt(doneKey, i).putInt(failKey, failed).apply()
                setProgress(workDataOf(KEY_DONE to i, KEY_TOTAL to total, KEY_MSG to "En pause : ${pause.label}"))
                return Result.retry()
            }
            setProgress(workDataOf(KEY_DONE to i, KEY_TOTAL to total, KEY_MSG to "Photo ${i + 1}/$total…"))
            val file = File(paths[i])
            try {
                processOne(Uri.fromFile(file), mode, factor, grain, format)
            } catch (_: Throwable) {
                failed++
            } finally {
                file.delete()
            }
            prefs.edit().putInt(doneKey, i + 1).putInt(failKey, failed).apply()
        }
        prefs.edit().remove(doneKey).remove(failKey).apply()
        val ok = total - failed
        return Result.success(
            workDataOf(KEY_DONE to total, KEY_TOTAL to total, KEY_FAILED to failed, KEY_MSG to "Terminé : $ok/$total photos dans Pictures/PixR")
        )
    }

    private fun processOne(uri: Uri, mode: Int, factor: Int, grain: Int, format: ExportFormat) {
        val maxIn = OutputEstimator.MAX_OUTPUT_PIXELS / (factor * factor)
        val bmp = ImageIO.decodeOriented(applicationContext, uri, maxIn) ?: error("illisible")
        val w = bmp.width
        val h = bmp.height
        val out = NativeEngine.upscale(ImageIO.pixels(bmp), w, h, factor, mode, grain, 256) ?: error("échec natif")
        val result = Bitmap.createBitmap(out, w * factor, h * factor, Bitmap.Config.ARGB_8888)
        Exporter.save(applicationContext, result, uri, factor, format) ?: error("export")
        result.recycle()
        bmp.recycle()
    }

    companion object {
        const val KEY_PATHS = "paths"
        const val KEY_MODE = "mode"
        const val KEY_FACTOR = "factor"
        const val KEY_GRAIN = "grain"
        const val KEY_FORMAT = "format"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_FAILED = "failed"
        const val KEY_MSG = "msg"
    }
}
