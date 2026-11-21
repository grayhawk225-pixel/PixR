package com.pixr.app

object NativeEngine {
    val available: Boolean = try {
        System.loadLibrary("pixr_engine")
        true
    } catch (_: UnsatisfiedLinkError) {
        false
    }

    /** Retourne les pixels ARGB agrandis (w*factor × h*factor), ou null en cas d'échec. */
    external fun upscale(
        src: IntArray, w: Int, h: Int, factor: Int, mode: Int, grainPercent: Int, tileSize: Int
    ): IntArray?

    /**
     * Fusion multi-photos (« rafale a posteriori ») : [frames] = 2 à 8 images ARGB de même taille w×h,
     * la première sert de référence. Retourne l'image fusionnée ×2 (2w × 2h), ou null en cas d'échec.
     */
    external fun fuse(frames: Array<IntArray>, w: Int, h: Int, grainPercent: Int): IntArray?

    /**
     * Fusion d'une rafale RAW_SENSOR (2 à 8 trames 16 bits, motif Bayer [cfa] : 0 RGGB, 1 GRBG, 2 GBRG, 3 BGGR).
     * [blackLevels] : 4 niveaux de noir (ordre raster du motif 2×2) ; [gains] : [R, Gr, Gb, B] ;
     * [ccm] : matrice 3×3 (ligne par ligne) caméra -> sRGB linéaire ; [factor] : 1 ou 2 ;
     * [cropRect] : (x0, y0, x1, y1) en pixels dans les trames ; [textMode] : accentuation douce fidèle.
     * Retourne les pixels ARGB sRGB ((x1-x0)*factor × (y1-y0)*factor), ou null en cas d'échec.
     */
    external fun fuseRawBurst(
        frames: Array<ShortArray>, w: Int, h: Int, cfa: Int, blackLevels: IntArray, whiteLevel: Int,
        gains: FloatArray, ccm: FloatArray, factor: Int, cropRect: IntArray, textMode: Boolean
    ): IntArray?
}
