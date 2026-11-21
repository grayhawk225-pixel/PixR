package com.pixr.app

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Niveau de stabilité de la tenue du téléphone (gyroscope). */
enum class StabilityLevel(val label: String) {
    STABLE("Stable"),
    WARNING("Attention"),
    UNSTABLE("Instable")
}

object StabilityRules {
    /** Vitesse angulaire (rad/s) sous laquelle la tenue est jugée stable pour la fusion. */
    const val STABLE_MAX = 0.10f
    /** Au-delà : instable. Entre les deux : attention. */
    const val WARNING_MAX = 0.30f

    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)

    fun classify(angularSpeed: Float): StabilityLevel = when {
        angularSpeed < STABLE_MAX -> StabilityLevel.STABLE
        angularSpeed < WARNING_MAX -> StabilityLevel.WARNING
        else -> StabilityLevel.UNSTABLE
    }

    /** Lissage exponentiel pour éviter le clignotement de la pastille. */
    fun smooth(previous: Float, sample: Float, alpha: Float = 0.3f): Float = previous + alpha * (sample - previous)
}

enum class CaptureStrategy { RAW, YUV }

object StrategyChooser {
    /** RAW seulement si la capacité RAW est annoncée ET qu'une taille RAW_SENSOR existe ; sinon repli YUV. */
    fun choose(hasRawCapability: Boolean, hasRawSensorFormat: Boolean): CaptureStrategy =
        if (hasRawCapability && hasRawSensorFormat) CaptureStrategy.RAW else CaptureStrategy.YUV
}

object BurstRules {
    const val MIN_FRAMES = 6
    const val MAX_FRAMES = 8

    fun clampCount(n: Int): Int = n.coerceIn(MIN_FRAMES, MAX_FRAMES)

    /** Texte / Loin : plus de trames pour l'alignement ; zoom fort : une trame de plus. */
    fun frameCount(textMode: Boolean, zoom: Float): Int = clampCount(
        when {
            textMode -> MAX_FRAMES
            zoom >= 2f -> 7
            else -> MIN_FRAMES
        }
    )
}

object DeferredRules {
    const val MAX_FRAMES = 12
    const val DENOISE_IMMEDIATE = 0.35f
    const val DENOISE_DEFERRED = 0.8f

    /** Nombre de trames d'une rafale à développer plus tard : borné par la mémoire (budget en octets) et par 6..12. */
    fun frameCount(frameBytes: Long, budgetBytes: Long): Int =
        if (frameBytes <= 0L) BurstRules.MIN_FRAMES
        else (budgetBytes / frameBytes).toInt().coerceIn(BurstRules.MIN_FRAMES, MAX_FRAMES)
}

object ZoomMath {
    val STEPS: List<Float> = listOf(1f, 2f, 3f, 4f)

    fun isAvailable(step: Float, maxZoom: Float): Boolean = step <= maxZoom + 1e-3f

    /** CONTROL_ZOOM_RATIO n'existe qu'à partir de l'API 30 et doit rester dans la plage du capteur. */
    fun usesZoomRatio(sdk: Int, requested: Float, min: Float, max: Float): Boolean =
        sdk >= 30 && requested >= min && requested <= max

    /** Sous ×2 on garde la définition native ; à partir de ×2, fusion sur grille ×2. */
    fun outputFactor(zoom: Float): Int = if (zoom >= 2f) 2 else 1

    /** Recadrage centré (l, t, r, b) pour SCALER_CROP_REGION. */
    fun cropRegion(w: Int, h: Int, zoom: Float): IntArray {
        val z = max(1f, zoom)
        val cw = max(1, (w / z).toInt())
        val ch = max(1, (h / z).toInt())
        val l = (w - cw) / 2
        val t = (h - ch) / 2
        return intArrayOf(l, t, l + cw, t + ch)
    }

    /** Recadrage centré aligné sur le motif Bayer 2×2 (origine et taille paires). */
    fun centerCrop(w: Int, h: Int, zoom: Float): IntArray {
        val z = max(1f, zoom)
        val cw = max(2, (w / z).toInt() and 1.inv())
        val ch = max(2, (h / z).toInt() and 1.inv())
        val x0 = ((w - cw) / 2) and 1.inv()
        val y0 = ((h - ch) / 2) and 1.inv()
        return intArrayOf(x0, y0, x0 + cw, y0 + ch)
    }

    /** Région à extraire du RAW : le recadrage élargi de [margin] px (pour l'alignement), origine et taille paires. */
    fun region(crop: IntArray, w: Int, h: Int, margin: Int): IntArray {
        val x0 = max(0, crop[0] - margin) and 1.inv()
        val y0 = max(0, crop[1] - margin) and 1.inv()
        var x1 = min(w, crop[2] + margin)
        var y1 = min(h, crop[3] + margin)
        x1 = x0 + ((x1 - x0) and 1.inv())
        y1 = y0 + ((y1 - y0) and 1.inv())
        return intArrayOf(x0, y0, x1, y1)
    }

    fun relative(crop: IntArray, region: IntArray): IntArray =
        intArrayOf(crop[0] - region[0], crop[1] - region[1], crop[2] - region[0], crop[3] - region[1])
}

object SizePicker {
    private const val RATIO = 4.0 / 3.0

    private fun isFourThree(s: Pair<Int, Int>): Boolean = abs(s.first.toDouble() / s.second - RATIO) < 0.02

    /** Plus grande taille 4:3 sous [maxPixels] (aperçu fluide, même cadrage que le RAW). */
    fun pickPreview(sizes: List<Pair<Int, Int>>, maxPixels: Long = 2_100_000L): Pair<Int, Int>? =
        sizes.filter { isFourThree(it) && it.first.toLong() * it.second <= maxPixels }
            .maxByOrNull { it.first.toLong() * it.second }
            ?: sizes.minByOrNull { abs(it.first.toLong() * it.second - 1_000_000L) }

    /** Taille YUV de repli : 4:3, sous [maxPixels] pour que la fusion ×2 tienne en mémoire. */
    fun pickYuv(sizes: List<Pair<Int, Int>>, maxPixels: Long = 4_000_000L): Pair<Int, Int>? =
        sizes.filter { isFourThree(it) && it.first.toLong() * it.second <= maxPixels }
            .maxByOrNull { it.first.toLong() * it.second }
            ?: sizes.minByOrNull { it.first.toLong() * it.second }

    fun pickRaw(sizes: List<Pair<Int, Int>>): Pair<Int, Int>? = sizes.maxByOrNull { it.first.toLong() * it.second }
}

object BayerMath {
    /** Codes Android SENSOR_INFO_COLOR_FILTER_ARRANGEMENT : 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR. */
    fun cfaName(code: Int): String = when (code) {
        0 -> "RGGB"
        1 -> "GRBG"
        2 -> "GBRG"
        3 -> "BGGR"
        else -> "inconnu"
    }

    private val PATTERNS = arrayOf(
        intArrayOf(0, 1, 1, 2), // RGGB
        intArrayOf(1, 0, 2, 1), // GRBG
        intArrayOf(1, 2, 0, 1), // GBRG
        intArrayOf(2, 1, 1, 0)  // BGGR
    )

    /** Couleur du photosite (x, y) : 0 = R, 1 = G, 2 = B. Index raster du motif 2×2 : (y&1)*2 + (x&1). */
    fun colorAt(cfa: Int, x: Int, y: Int): Int = PATTERNS[cfa.coerceIn(0, 3)][((y and 1) shl 1) or (x and 1)]

    /** Niveau de noir du photosite (x, y) ; [blacks] est dans l'ordre raster du motif 2×2. */
    fun blackFor(blacks: IntArray, x: Int, y: Int): Int = blacks[((y and 1) shl 1) or (x and 1)]

    /** Normalisation linéaire (valeur brute - noir) / (blanc - noir), bornée à [0, 1]. */
    fun normalize(raw: Int, black: Int, white: Int): Float {
        val range = (white - black).coerceAtLeast(1)
        return ((raw - black).toFloat() / range).coerceIn(0f, 1f)
    }
}

object WhiteBalance {
    private fun validGains(g: FloatArray?): Boolean =
        g != null && g.size >= 4 && g.all { it.isFinite() && it > 0.05f && it < 16f }

    private fun distinct(g: FloatArray): Boolean = (g.max() - g.min()) > 0.02f

    /**
     * Gains [R, Gr, Gb, B] : ceux du résultat de capture s'ils sont valides et non triviaux,
     * sinon dérivés du point neutre (1/neutre), sinon 1.
     */
    fun resolve(gains: FloatArray?, neutral: FloatArray?): FloatArray {
        if (gains != null && validGains(gains) && distinct(gains)) return gains.copyOf(4)
        if (neutral != null && neutral.size >= 3 && neutral.all { it.isFinite() && it > 0.01f }) {
            val g = neutral[1]
            return floatArrayOf(g / neutral[0], 1f, 1f, g / neutral[2])
        }
        return floatArrayOf(1f, 1f, 1f, 1f)
    }
}

object ColorMath {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** XYZ (D50) -> sRGB linéaire (Bradford). */
    val XYZ_D50_TO_SRGB = floatArrayOf(
        3.1338561f, -1.6168667f, -0.4906146f,
        -0.9787684f, 1.9161415f, 0.0334540f,
        0.0719453f, -0.2289914f, 1.4052427f
    )

    private fun det(m: FloatArray): Float =
        m[0] * (m[4] * m[8] - m[5] * m[7]) - m[1] * (m[3] * m[8] - m[5] * m[6]) + m[2] * (m[3] * m[7] - m[4] * m[6])

    private fun finite(m: FloatArray?): Boolean = m != null && m.size >= 9 && m.all { it.isFinite() }

    /** Une CCM caméra->sRGB plausible : finie, inversible, lignes de somme proche de 1 (blanc conservé). */
    fun isPlausibleCcm(m: FloatArray?): Boolean {
        if (!finite(m)) return false
        val c = m!!
        if (abs(det(c)) < 0.05f) return false
        for (r in 0 until 3) {
            val s = c[r * 3] + c[r * 3 + 1] + c[r * 3 + 2]
            if (s < 0.7f || s > 1.4f) return false
        }
        return true
    }

    /** Vrai si la matrice est (presque) l'identité : la CCM du résultat n'apporte alors aucune information couleur. */
    fun isNearIdentity(m: FloatArray?): Boolean =
        m != null && m.size >= 9 && m.indices.all { abs(m[it] - IDENTITY[it]) < 0.02f }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (i in 0 until 3) for (j in 0 until 3) {
            var s = 0f
            for (k in 0 until 3) s += a[i * 3 + k] * b[k * 3 + j]
            out[i * 3 + j] = s
        }
        return out
    }

    /** CCM du résultat si plausible ; sinon sRGB <- XYZ <- matrice de forward ; sinon identité. */
    fun resolveCcm(resultCcm: FloatArray?, forwardMatrix: FloatArray?): FloatArray {
        if (isPlausibleCcm(resultCcm)) return resultCcm!!.copyOf(9)
        if (finite(forwardMatrix) && abs(det(forwardMatrix!!)) > 1e-4f) return multiply(XYZ_D50_TO_SRGB, forwardMatrix)
        return IDENTITY.copyOf()
    }
}

object ExposureLogic {
    /**
     * Mode Texte / Loin : vitesse plus rapide (exposition /2) compensée par l'ISO (x2), bornés aux plages du capteur.
     * Retourne (exposition en ns, ISO).
     */
    fun textMode(
        exposureNs: Long, iso: Int,
        exposureMinNs: Long, exposureMaxNs: Long, isoMin: Int, isoMax: Int
    ): Pair<Long, Int> {
        val exp = (exposureNs / 2).coerceIn(exposureMinNs, exposureMaxNs)
        val ratio = exposureNs.toDouble() / exp.toDouble()
        val newIso = (iso * ratio).toInt().coerceIn(isoMin, isoMax)
        return exp to newIso
    }
}

/** Métadonnées EXIF issues du TotalCaptureResult. */
data class CameraExif(
    val make: String,
    val model: String,
    val dateTime: String,
    val fNumber: Float?,
    val exposureTimeSec: Double?,
    val iso: Int?,
    val focalLengthMm: Float?
)

object CameraExifFormat {
    fun exifDate(millis: Long): String = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date(millis))

    fun decimal(v: Double, digits: Int): String = String.format(Locale.US, "%.${digits}f", v)

    fun exposure(sec: Double): String = String.format(Locale.US, "%.6f", sec)

    fun note(raw: Boolean, frames: Int): String =
        if (raw) "PixR Caméra RAW ×$frames trames, 0 % génératif"
        else "PixR Caméra (repli YUV) ×$frames trames, 0 % génératif"
}

/** Rendu de sortie : contraste (courbe en S) et saturation, appliqués après l'exposition automatique douce. */
enum class RenderLook(val label: String, val contrast: Float, val saturation: Float, val shadows: Float) {
    NATURAL("Naturel", 0.35f, 1.10f, 0.35f),
    VIVID("Éclatant", 0.60f, 1.25f, 0.25f),
    SOFT("Doux", 0.15f, 1.00f, 0.50f)
}

/** Accentuation de sortie (netteté perçue) : compense le flou du démosaïçage et de l'agrandissement. */
enum class Sharpness(val label: String, val amount: Float) {
    SOFT("Douce", 0.5f),
    NATURAL("Naturelle", 0.9f),
    CRISP("Nette", 1.3f)
}

object ExposureMath {
    const val MAX_EV = 2f

    fun evGain(ev: Float): Float = Math.pow(2.0, ev.toDouble()).toFloat()

    /** Indice de compensation d'exposition AE le plus proche, borné à la plage du capteur. */
    fun compensationIndex(ev: Float, step: Float, min: Int, max: Int): Int =
        if (step <= 0f) 0 else Math.round(ev / step).coerceIn(min, max)

    /** EV restant à appliquer numériquement une fois la compensation AE (par pas) retirée. */
    fun residualEv(ev: Float, step: Float, index: Int): Float = if (step <= 0f) ev else ev - index * step

    /**
     * Paramètres de rendu pour le moteur natif :
     * [gain d'exposition numérique, contraste, saturation, débruitage 0..1, exposition auto (1 = oui), remontée des ombres 0..1,
     *  bruit S, bruit O (profil Camera2, 0 = inconnu), accentuation de sortie].
     */
    fun tone(look: RenderLook, residualEv: Float, denoise: Float = 0.35f, sharpness: Float = Sharpness.NATURAL.amount): FloatArray =
        floatArrayOf(evGain(residualEv), look.contrast, look.saturation, denoise, 1f, look.shadows, 0f, 0f, sharpness)
}

/** Force du débruitage déduite du bruit RÉEL du capteur (profil Camera2) et du nombre d'images fusionnées. */
object NoiseModel {
    /**
     * [profile] = [S0, O0, S1, O1, ...] (Camera2 SENSOR_NOISE_PROFILE) : variance(x) = S·x + O, x = valeur normalisée 0..1.
     * Retourne l'écart-type moyen des canaux à luminosité [x], sur l'échelle 0..255, ou null si le profil est inutilisable.
     */
    fun sigmaMid255(profile: DoubleArray?, x: Double = 0.2): Double? {
        if (profile == null || profile.size < 2 || profile.size % 2 != 0) return null
        var sum = 0.0
        var n = 0
        for (i in profile.indices step 2) {
            val v = profile[i] * x + profile[i + 1]
            if (!v.isFinite() || v < 0.0) return null
            sum += Math.sqrt(v)
            n++
        }
        return if (n == 0) null else sum / n * 255.0
    }

    /** Moyenne des couples (S, O) du profil : le moteur s'en sert pour distinguer détail et bruit, et régler le rejet de mouvement. */
    fun meanSO(profile: DoubleArray?): Pair<Float, Float>? {
        if (profile == null || profile.size < 2 || profile.size % 2 != 0) return null
        var s = 0.0
        var o = 0.0
        val n = profile.size / 2
        for (i in 0 until n) {
            val si = profile[2 * i]
            val oi = profile[2 * i + 1]
            if (!si.isFinite() || !oi.isFinite() || si < 0.0 || oi < 0.0) return null
            s += si
            o += oi
        }
        // Profil aberrant (mauvaise échelle du fabricant) : mieux vaut l'ignorer que de tout flouter à tort.
        val sigmaMid = Math.sqrt(s / n * 0.2 + o / n) * 255.0
        if (!sigmaMid.isFinite() || sigmaMid < 0.05 || sigmaMid > 60.0) return null
        return (s / n).toFloat() to (o / n).toFloat()
    }

    /** Bruit résiduel après fusion de [frames] images : l'écart-type diminue comme 1/√N. */
    fun denoiseStrength(sigmaMid255: Double?, frames: Int, deferred: Boolean): Float {
        val base = if (deferred) DeferredRules.DENOISE_DEFERRED else DeferredRules.DENOISE_IMMEDIATE
        if (sigmaMid255 == null || frames < 1) return base
        val residual = sigmaMid255 / Math.sqrt(frames.toDouble())
        val s = (0.15 + 0.12 * residual).coerceIn(0.10, 0.80)
        return (if (deferred) s * 1.4 else s).coerceIn(0.10, 0.90).toFloat()
    }
}

/** Choix de l'image de référence : la plus nette (comme Google et Samsung), pas simplement la première. */
object FrameQuality {
    /** Énergie de gradient entre pixels de même couleur (distance 2), échantillonnée : plus haut = plus net. */
    fun sharpness(frame: ShortArray, w: Int, h: Int): Double {
        var acc = 0.0
        var n = 0
        var y = 4
        while (y < h - 4) {
            var x = 4
            while (x < w - 4) {
                val a = frame[y * w + x].toInt() and 0xFFFF
                val b = frame[y * w + x + 2].toInt() and 0xFFFF
                val c = frame[(y + 2) * w + x].toInt() and 0xFFFF
                acc += Math.abs(a - b) + Math.abs(a - c)
                n++
                x += 5
            }
            y += 5
        }
        return if (n == 0) 0.0 else acc / n
    }

    /** Indice de la meilleure référence ; garde la première image sauf gain net (> 2 %), pour ne pas réordonner pour rien. */
    fun pickReference(frames: List<ShortArray>, w: Int, h: Int): Int {
        if (frames.size < 2) return 0
        val scores = frames.map { sharpness(it, w, h) }
        var best = 0
        for (i in 1 until scores.size) if (scores[i] > scores[best]) best = i
        return if (scores[best] > scores[0] * 1.02) best else 0
    }
}
