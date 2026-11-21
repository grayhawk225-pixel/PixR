package com.pixr.app

import kotlin.math.max
import kotlin.math.min

enum class ProcessingMode(val label: String, val nativeId: Int) {
    STANDARD("Standard fidèle", 0),
    COMPRESSED("Photo compressée", 1),
    TEXT("Texte & Documents", 2)
}

enum class CropPreset(val label: String, val ratio: Float?) {
    FULL("Complet", null),
    SQUARE("1:1", 1f),
    R43("4:3", 4f / 3f),
    R169("16:9", 16f / 9f)
}

data class Tile(val x: Int, val y: Int, val w: Int, val h: Int)

object TilePlanner {
    /** Découpe une image en tuiles de [tileSize] px max (les tuiles du bord sont plus petites). */
    fun plan(width: Int, height: Int, tileSize: Int): List<Tile> {
        require(width > 0 && height > 0 && tileSize > 0)
        val tiles = ArrayList<Tile>()
        var y = 0
        while (y < height) {
            var x = 0
            val h = min(tileSize, height - y)
            while (x < width) {
                tiles += Tile(x, y, min(tileSize, width - x), h)
                x += tileSize
            }
            y += tileSize
        }
        return tiles
    }
}

object CropMath {
    /** Recadrage centré au ratio voulu (null = image entière), réduit par [zoom] (>= 1). */
    fun centerCrop(width: Int, height: Int, ratio: Float?, zoom: Float): Tile {
        val z = max(1f, zoom)
        var cw = width.toFloat()
        var ch = height.toFloat()
        if (ratio != null) {
            if (cw / ch > ratio) cw = ch * ratio else ch = cw / ratio
        }
        val w = max(1, (cw / z).toInt())
        val h = max(1, (ch / z).toInt())
        return Tile((width - w) / 2, (height - h) / 2, w, h)
    }
}

object OutputEstimator {
    const val MAX_OUTPUT_PIXELS = 36_000_000L

    fun outputPixels(w: Int, h: Int, factor: Int): Long = w.toLong() * h * factor * factor

    fun megapixels(w: Int, h: Int, factor: Int): Double = outputPixels(w, h, factor) / 1_000_000.0

    fun estimatedMegabytes(w: Int, h: Int, factor: Int, format: ExportFormat = ExportFormat.JPEG98): Double =
        outputPixels(w, h, factor) * format.bytesPerPixel / 1_000_000.0

    /** Plus petit sous-échantillonnage (puissance de 2) gardant la sortie sous [maxOut] pixels. */
    fun safeSampleSize(w: Int, h: Int, factor: Int, maxOut: Long = MAX_OUTPUT_PIXELS): Int {
        var s = 1
        while (outputPixels(w / s, h / s, factor) > maxOut) s *= 2
        return s
    }
}

object ExifPolicy {
    fun processingNote(factor: Int): String = "×$factor fidèle, 0 % génératif"

    fun fusionNote(frames: Int): String = "Fusion de $frames photos ×2, 0 % génératif"

    /** Texte d'état affiché après export selon le format et le résultat de l'écriture EXIF. */
    fun exportStatus(format: ExportFormat, exifWritten: Boolean): String = when {
        exifWritten -> "Enregistré dans Pictures/PixR (${format.label}, EXIF conservés)."
        format == ExportFormat.PNG -> "Enregistré dans Pictures/PixR (PNG sans perte ; EXIF non écrits : format non pris en charge)."
        else -> "Enregistré dans Pictures/PixR (${format.label}, EXIF non écrits)."
    }
}

enum class ExportFormat(
    val label: String,
    val mime: String,
    val extension: String,
    val bytesPerPixel: Double,
    val quality: Int
) {
    JPEG98("JPEG 98 %", "image/jpeg", "jpg", 0.45, 98),
    PNG("PNG sans perte", "image/png", "png", 2.2, 100)
}

object FusionRules {
    const val MIN_IMAGES = 3
    const val MAX_IMAGES = 6
    const val MAX_OUTPUT_PIXELS = 24_000_000L

    fun isValidCount(n: Int): Boolean = n in MIN_IMAGES..MAX_IMAGES

    fun countMessage(n: Int): String? = when {
        n < MIN_IMAGES -> "Choisis au moins $MIN_IMAGES photos similaires (reçu : $n)."
        n > MAX_IMAGES -> "Maximum $MAX_IMAGES photos pour la fusion (reçu : $n)."
        else -> null
    }

    /** Pixels max par image source pour que la sortie ×2 reste sous [MAX_OUTPUT_PIXELS]. */
    fun maxInputPixels(): Long = MAX_OUTPUT_PIXELS / 4

    /** Deux photos sont « similaires » si leur ratio d'aspect diffère de moins de [tolerance]. */
    fun areSimilar(w1: Int, h1: Int, w2: Int, h2: Int, tolerance: Float = 0.02f): Boolean {
        if (w1 <= 0 || h1 <= 0 || w2 <= 0 || h2 <= 0) return false
        val a = w1.toFloat() / h1
        val b = w2.toFloat() / h2
        return kotlin.math.abs(a - b) / a <= tolerance
    }
}

enum class PauseReason(val label: String) {
    LOW_BATTERY("batterie sous 15 %"),
    OVERHEAT("téléphone trop chaud")
}

object BatchRules {
    const val MIN_IMAGES = 2
    const val MAX_IMAGES = 20
    const val MIN_BATTERY_PERCENT = 15
    /** PowerManager.THERMAL_STATUS_SEVERE */
    const val THERMAL_SEVERE = 3

    fun isValidCount(n: Int): Boolean = n in MIN_IMAGES..MAX_IMAGES

    fun countMessage(n: Int): String? = when {
        n < MIN_IMAGES -> "Choisis au moins $MIN_IMAGES photos (reçu : $n)."
        n > MAX_IMAGES -> "Maximum $MAX_IMAGES photos par lot (reçu : $n)."
        else -> null
    }

    /** Raison de pause du lot (null = on continue). La chauffe sévère prime sur la batterie. */
    fun pauseReason(batteryPercent: Int, charging: Boolean, thermalStatus: Int): PauseReason? = when {
        thermalStatus >= THERMAL_SEVERE -> PauseReason.OVERHEAT
        batteryPercent < MIN_BATTERY_PERCENT && !charging -> PauseReason.LOW_BATTERY
        else -> null
    }
}
