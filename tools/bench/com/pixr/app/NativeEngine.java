package com.pixr.app;

/** Façade Java du moteur natif, utilisée uniquement par le banc de test sur PC (même signature JNI que l'app). */
public class NativeEngine {
    public native int[] fuseRawBurst(short[][] frames, int w, int h, int cfa, int[] blackLevels, int whiteLevel,
                                     float[] gains, float[] ccm, int factor, int[] cropRect, boolean textMode,
                                     float[] tone);
}
