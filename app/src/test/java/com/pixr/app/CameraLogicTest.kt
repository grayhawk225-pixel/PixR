package com.pixr.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLogicTest {
    @Test
    fun gyroThresholds() {
        assertEquals(StabilityLevel.STABLE, StabilityRules.classify(0.05f))
        assertEquals(StabilityLevel.WARNING, StabilityRules.classify(0.2f))
        assertEquals(StabilityLevel.UNSTABLE, StabilityRules.classify(0.5f))
        assertEquals(5f, StabilityRules.magnitude(3f, 4f, 0f), 1e-5f)
    }

    @Test
    fun rawVersusYuvFallback() {
        assertEquals(CaptureStrategy.RAW, StrategyChooser.choose(true, true))
        assertEquals(CaptureStrategy.YUV, StrategyChooser.choose(true, false))
        assertEquals(CaptureStrategy.YUV, StrategyChooser.choose(false, true))
    }

    @Test
    fun burstSizeBetweenSixAndEight() {
        assertEquals(6, BurstRules.frameCount(false, 1f))
        assertEquals(7, BurstRules.frameCount(false, 3f))
        assertEquals(8, BurstRules.frameCount(true, 1f))
        assertEquals(8, BurstRules.clampCount(20))
        assertEquals(6, BurstRules.clampCount(1))
    }

    @Test
    fun zoomAndCrop() {
        assertTrue(ZoomMath.isAvailable(4f, 8f))
        assertFalse(ZoomMath.isAvailable(4f, 2f))
        assertTrue(ZoomMath.usesZoomRatio(30, 2f, 0.6f, 10f))
        assertFalse(ZoomMath.usesZoomRatio(29, 2f, 0.6f, 10f))
        assertEquals(1, ZoomMath.outputFactor(1f))
        assertEquals(2, ZoomMath.outputFactor(2f))
        assertArrayEquals(intArrayOf(1024, 768, 3072, 2304), ZoomMath.centerCrop(4096, 3072, 2f))
        assertArrayEquals(intArrayOf(1536, 1152, 2560, 1920), ZoomMath.centerCrop(4096, 3072, 4f))
        val crop = ZoomMath.centerCrop(4096, 3072, 4f)
        val region = ZoomMath.region(crop, 4096, 3072, 32)
        assertArrayEquals(intArrayOf(1504, 1120, 2592, 1952), region)
        assertArrayEquals(intArrayOf(32, 32, 1056, 800), ZoomMath.relative(crop, region))
    }

    @Test
    fun bayerAndNormalization() {
        assertEquals("RGGB", BayerMath.cfaName(0))
        assertEquals(0, BayerMath.colorAt(0, 0, 0))
        assertEquals(2, BayerMath.colorAt(0, 1, 1))
        assertEquals(2, BayerMath.colorAt(3, 0, 0))
        assertEquals(0f, BayerMath.normalize(40, 64, 1023), 0f)
        assertEquals(1f, BayerMath.normalize(2000, 64, 1023), 0f)
        assertEquals(0.5f, BayerMath.normalize(543, 64, 1023), 1e-3f)
        assertEquals(7, BayerMath.blackFor(intArrayOf(5, 6, 7, 8), 0, 1))
    }

    @Test
    fun whiteBalanceAndColor() {
        val g = WhiteBalance.resolve(floatArrayOf(2f, 1f, 1f, 1.6f), null)
        assertEquals(2f, g[0], 0f)
        val fromNeutral = WhiteBalance.resolve(floatArrayOf(1f, 1f, 1f, 1f), floatArrayOf(0.5f, 1f, 0.5f))
        assertEquals(2f, fromNeutral[0], 1e-5f)
        assertEquals(2f, fromNeutral[3], 1e-5f)
        assertTrue(ColorMath.isNearIdentity(ColorMath.IDENTITY))
        assertFalse(ColorMath.isNearIdentity(ColorMath.XYZ_D50_TO_SRGB))
        assertArrayEquals(ColorMath.IDENTITY, ColorMath.resolveCcm(null, null), 0f)
    }

    @Test
    fun exifAndExposure() {
        assertEquals("PixR Caméra RAW ×6 trames, 0 % génératif", CameraExifFormat.note(true, 6))
        assertEquals("0.016667", CameraExifFormat.exposure(1.0 / 60))
        assertEquals(1.8, CameraExifFormat.decimal(1.8, 2).toDouble(), 1e-9)
        val (e, i) = ExposureLogic.textMode(20_000_000L, 200, 1_000L, 1_000_000_000L, 50, 3200)
        assertEquals(10_000_000L, e)
        assertEquals(400, i)
        val sizes = listOf(4096 to 3072, 1600 to 1200, 1920 to 1080)
        assertEquals(4096 to 3072, SizePicker.pickRaw(sizes))
        assertEquals(1600 to 1200, SizePicker.pickPreview(sizes))
    }

    @Test
    fun renderToneAndExposure() {
        assertEquals(1f, ExposureMath.evGain(0f), 1e-6f)
        assertEquals(2f, ExposureMath.evGain(1f), 1e-5f)
        // Pas AE de 1/3 EV : +1 EV -> indice 3, aucun reste numérique.
        assertEquals(3, ExposureMath.compensationIndex(1f, 1f / 3f, -12, 12))
        assertEquals(0f, ExposureMath.residualEv(1f, 1f / 3f, 3), 1e-5f)
        // Plage limitée : l'indice est borné, le reste passe en numérique.
        assertEquals(2, ExposureMath.compensationIndex(2f, 0.5f, -2, 2).coerceAtMost(2))
        assertEquals(0.5f, ExposureMath.residualEv(1.5f, 0.5f, 2), 1e-5f)
        // Capteur sans compensation (pas 0) : tout en numérique.
        assertEquals(0, ExposureMath.compensationIndex(1f, 0f, 0, 0))
        assertEquals(1f, ExposureMath.residualEv(1f, 0f, 0), 1e-6f)
        val tone = ExposureMath.tone(RenderLook.NATURAL, 0f)
        assertArrayEquals(floatArrayOf(1f, 0.35f, 1.10f, 0.35f, 1f, 0.35f, 0f, 0f, 0.9f), tone, 1e-6f)
        assertTrue(RenderLook.VIVID.saturation > RenderLook.NATURAL.saturation)
        assertTrue(RenderLook.SOFT.contrast < RenderLook.NATURAL.contrast)
    }

    @Test
    fun deferredFrameCountFollowsMemory() {
        val mb = 1024L * 1024L
        // Pleine résolution à 1x : ~25 Mo par trame -> 240 Mo permettent 9 trames.
        assertEquals(9, DeferredRules.frameCount(25 * mb, 240 * mb))
        // Zoom fort : trames petites -> plafond de 12.
        assertEquals(12, DeferredRules.frameCount(7 * mb, 240 * mb))
        // Mémoire serrée : jamais moins que le minimum de la rafale normale.
        assertEquals(6, DeferredRules.frameCount(60 * mb, 100 * mb))
        assertEquals(6, DeferredRules.frameCount(0L, 100 * mb))
        assertTrue(DeferredRules.DENOISE_DEFERRED > DeferredRules.DENOISE_IMMEDIATE)
        assertEquals(9, ExposureMath.tone(RenderLook.NATURAL, 0f, 0.8f).size)
        assertEquals(0.8f, ExposureMath.tone(RenderLook.NATURAL, 0f, 0.8f)[3], 1e-6f)
    }

    @Test
    fun noiseModelDrivesDenoise() {
        // Profil du banc de test (400 e- à saturation, lecture 3 e-) : S = 1/400, O = 9/400² -> sigma ≈ 6 sur 255 à x = 0,2.
        val s = NoiseModel.sigmaMid255(doubleArrayOf(0.0025, 5.625e-5, 0.0025, 5.625e-5))!!
        assertEquals(6.0, s, 0.1)
        // Plus il y a d'images, moins il faut de débruitage ; le mode différé en demande davantage.
        val few = NoiseModel.denoiseStrength(s, 2, false)
        val many = NoiseModel.denoiseStrength(s, 12, false)
        assertTrue(few > many)
        assertTrue(NoiseModel.denoiseStrength(s, 8, true) > NoiseModel.denoiseStrength(s, 8, false))
        assertEquals(0.40f, NoiseModel.denoiseStrength(s, 8, false), 0.02f)
        // Une scène peu bruitée (forte lumière) reste faiblement débruitée.
        val bright = NoiseModel.sigmaMid255(doubleArrayOf(1.0 / 6000, 9.0 / 36e6))!!
        assertTrue(NoiseModel.denoiseStrength(bright, 8, false) < 0.25f)
        // Profil absent ou invalide : valeurs par défaut.
        assertEquals(DeferredRules.DENOISE_IMMEDIATE, NoiseModel.denoiseStrength(null, 8, false), 1e-6f)
        assertEquals(null, NoiseModel.sigmaMid255(doubleArrayOf(1.0)))
        assertEquals(null, NoiseModel.sigmaMid255(doubleArrayOf(-1.0, -1.0)))
    }

    @Test
    fun sharpestFrameBecomesReference() {
        val w = 64
        val h = 64
        fun frame(contrast: Int) = ShortArray(w * h) { i -> (500 + if (((i % w) / 2 + (i / w) / 2) % 2 == 0) contrast else -contrast).toShort() }
        val blurry = frame(5)
        val sharp = frame(80)
        val mid = frame(30)
        assertTrue(FrameQuality.sharpness(sharp, w, h) > FrameQuality.sharpness(mid, w, h))
        assertEquals(1, FrameQuality.pickReference(listOf(blurry, sharp, mid), w, h))
        // Première image gardée quand le gain est négligeable.
        assertEquals(0, FrameQuality.pickReference(listOf(sharp, frame(79)), w, h))
        assertEquals(0, FrameQuality.pickReference(listOf(blurry), w, h))
    }

    @Test
    fun sharpnessAndNoiseProfile() {
        assertTrue(Sharpness.SOFT.amount < Sharpness.NATURAL.amount)
        assertTrue(Sharpness.NATURAL.amount < Sharpness.CRISP.amount)
        assertEquals(1.3f, ExposureMath.tone(RenderLook.NATURAL, 0f, 0.2f, Sharpness.CRISP.amount)[8], 1e-6f)
        val so = NoiseModel.meanSO(doubleArrayOf(0.002, 4e-5, 0.004, 6e-5, 0.004, 6e-5, 0.006, 8e-5))!!
        assertEquals(0.004f, so.first, 1e-6f)
        assertEquals(6e-5f, so.second, 1e-9f)
        assertEquals(null, NoiseModel.meanSO(doubleArrayOf(0.002)))
        assertEquals(null, NoiseModel.meanSO(doubleArrayOf(-0.002, 1e-5)))
        assertEquals(null, NoiseModel.meanSO(null))
        // Échelle aberrante (variance énorme) : ignorée.
        assertEquals(null, NoiseModel.meanSO(doubleArrayOf(5.0, 1.0)))
        assertEquals(null, NoiseModel.meanSO(doubleArrayOf(0.0, 0.0)))
    }
}
