package com.pixr.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    @Test
    fun tilePlannerCoversImageExactly() {
        val tiles = TilePlanner.plan(1000, 600, 256)
        assertEquals(4 * 3, tiles.size)
        assertEquals(1000L * 600L, tiles.sumOf { it.w.toLong() * it.h })
        assertEquals(Tile(768, 512, 232, 88), tiles.last())
    }

    @Test
    fun tilePlannerSingleTile() {
        assertEquals(listOf(Tile(0, 0, 100, 50)), TilePlanner.plan(100, 50, 256))
    }

    @Test
    fun centerCropSquare() {
        assertEquals(Tile(500, 0, 1000, 1000), CropMath.centerCrop(2000, 1000, 1f, 1f))
    }

    @Test
    fun centerCropZoomHalvesSize() {
        val t = CropMath.centerCrop(2000, 1000, null, 2f)
        assertEquals(Tile(500, 250, 1000, 500), t)
    }

    @Test
    fun outputEstimates() {
        assertEquals(48.0, OutputEstimator.megapixels(4000, 3000, 2), 1e-9)
        assertEquals(144_000_000L * 4 / 4, OutputEstimator.outputPixels(4000, 3000, 2) * 3)
    }

    @Test
    fun safeSampleSizeKeepsOutputUnderLimit() {
        val s = OutputEstimator.safeSampleSize(8000, 6000, 4)
        assertTrue(s >= 2)
        assertTrue(OutputEstimator.outputPixels(8000 / s, 6000 / s, 4) <= OutputEstimator.MAX_OUTPUT_PIXELS)
        assertEquals(1, OutputEstimator.safeSampleSize(1000, 1000, 2))
    }

    @Test
    fun exifNote() {
        assertEquals("×2 fidèle, 0 % génératif", ExifPolicy.processingNote(2))
    }

    @Test
    fun fusionAcceptsThreeToSixImages() {
        assertFalse(FusionRules.isValidCount(2))
        for (n in 3..6) assertTrue(FusionRules.isValidCount(n))
        assertFalse(FusionRules.isValidCount(7))
        assertNull(FusionRules.countMessage(4))
        assertTrue(FusionRules.countMessage(2)!!.contains("au moins 3"))
        assertTrue(FusionRules.countMessage(7)!!.contains("Maximum 6"))
    }

    @Test
    fun fusionSimilarityAndInputLimit() {
        assertTrue(FusionRules.areSimilar(4000, 3000, 2000, 1500))
        assertFalse(FusionRules.areSimilar(4000, 3000, 3000, 4000))
        assertFalse(FusionRules.areSimilar(0, 3000, 4000, 3000))
        assertEquals(6_000_000L, FusionRules.maxInputPixels())
    }

    @Test
    fun batchCountRules() {
        assertFalse(BatchRules.isValidCount(1))
        assertTrue(BatchRules.isValidCount(2))
        assertTrue(BatchRules.isValidCount(20))
        assertFalse(BatchRules.isValidCount(21))
        assertNull(BatchRules.countMessage(10))
        assertTrue(BatchRules.countMessage(1)!!.contains("au moins 2"))
    }

    @Test
    fun batchPausesOnLowBatteryOrHeat() {
        assertNull(BatchRules.pauseReason(80, false, 0))
        assertNull(BatchRules.pauseReason(15, false, 2))
        assertEquals(PauseReason.LOW_BATTERY, BatchRules.pauseReason(14, false, 0))
        assertNull(BatchRules.pauseReason(5, true, 0))
        assertEquals(PauseReason.OVERHEAT, BatchRules.pauseReason(90, true, 3))
        assertEquals(PauseReason.OVERHEAT, BatchRules.pauseReason(5, false, 5))
    }

    @Test
    fun exportEstimateDependsOnFormat() {
        val jpeg = OutputEstimator.estimatedMegabytes(4000, 3000, 2, ExportFormat.JPEG98)
        val png = OutputEstimator.estimatedMegabytes(4000, 3000, 2, ExportFormat.PNG)
        assertEquals(48_000_000 * 0.45 / 1_000_000.0, jpeg, 1e-9)
        assertEquals(48_000_000 * 2.2 / 1_000_000.0, png, 1e-9)
        assertTrue(png > jpeg)
        assertEquals(jpeg, OutputEstimator.estimatedMegabytes(4000, 3000, 2), 1e-9)
    }

    @Test
    fun exportFormatsAndNotes() {
        assertEquals(98, ExportFormat.JPEG98.quality)
        assertEquals("image/png", ExportFormat.PNG.mime)
        assertEquals("Fusion de 4 photos ×2, 0 % génératif", ExifPolicy.fusionNote(4))
        assertTrue(ExifPolicy.exportStatus(ExportFormat.JPEG98, true).contains("EXIF conservés"))
        assertTrue(ExifPolicy.exportStatus(ExportFormat.PNG, false).contains("EXIF non écrits"))
    }
}
