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
}
