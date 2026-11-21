import com.pixr.app.NativeEngine;
import java.util.Random;

/**
 * Banc de test du moteur de fusion RAW : scène synthétique connue, bruit capteur réaliste (Poisson + lecture),
 * décalages sous-pixel de la main, objet en mouvement. Mesures objectives, sans appareil.
 * Référence = le même moteur appliqué à la même rafale SANS bruit : on mesure donc ce que la fusion retire.
 */
public class Bench {
    static final int W = 512, H = 384, BLACK = 64, WHITE = 1023;

    // Mode « herbe » : texture fine partout (pas de zones plates), très peu de bruit, léger mouvement dû au vent.
    static boolean TEXTURE = false;
    static double WIND = 0.0;
    static final double[][] TEX = new double[28][5];
    static {
        Random r = new Random(99);
        for (double[] t : TEX) {
            double period = 2.6 + r.nextDouble() * 12.0, ang = r.nextDouble() * Math.PI;
            t[0] = Math.cos(ang) / period; t[1] = Math.sin(ang) / period;       // fréquences spatiales (cycles/px)
            t[2] = r.nextDouble() * 2 * Math.PI;                                   // phase
            t[3] = 0.9 / Math.sqrt(period);                                        // amplitude ~ 1/sqrt(période)
            t[4] = r.nextDouble();                                                 // teinte
        }
    }

    static void textureScene(double x, double y, int k, double[] out) {
        if (k > 0 && WIND > 0) {   // le vent déplace localement les brins d'herbe, différemment à chaque image
            double wx = WIND * Math.sin(0.045 * x + 0.06 * y + k * 1.7), wy = WIND * Math.cos(0.05 * x - 0.04 * y + k * 2.3);
            x += wx; y += wy;
        }
        double s = 0, sr = 0, sb = 0;
        for (double[] t : TEX) {
            double v = t[3] * Math.sin(2 * Math.PI * (t[0] * x + t[1] * y) + t[2]);
            s += v; if (t[4] > 0.5) sr += v; else sb += v;
        }
        double base = 0.20;
        out[0] = Math.max(0.01, base * (1 + 0.16 * (s + 0.4 * sr)));
        out[1] = Math.max(0.01, base * 1.15 * (1 + 0.16 * s));
        out[2] = Math.max(0.01, base * 0.55 * (1 + 0.16 * (s + 0.4 * sb)));
    }

    // Scène (lumière linéaire 0..1, 3 canaux) évaluée en un point continu (x, y), pour l'image k.
    static void scene(double x, double y, int k, double[] out) {
        if (TEXTURE) { textureScene(x, y, k, out); return; }
        double r = 0.10 + 0.05 * (x / W), g = 0.10 + 0.05 * (y / H), b = 0.09;
        if (x > W / 2.0 + 0.37) { r = 0.32; g = 0.30; b = 0.26; } else { r = 0.05; g = 0.05; b = 0.06; }   // contour franc
        // réseau de lignes fines (haute fréquence) dans le quart haut-gauche
        if (x > 40 && x < 200 && y > 40 && y < 160) {
            double v = 0.5 + 0.5 * Math.sin(2 * Math.PI * x / 5.0);
            r = 0.04 + 0.22 * v; g = r; b = r;
        }
        // texte simulé : barres de largeur variable (bas gauche)
        if (x > 40 && x < 230 && y > 230 && y < 330) {
            int bar = (int) ((x - 40) / 3.0);
            boolean on = ((bar * 7 + (int) ((y - 230) / 20)) % 3) == 0;
            r = on ? 0.03 : 0.28; g = r; b = r;
        }
        // disque coloré fixe
        double dx = x - 380, dy = y - 110;
        if (dx * dx + dy * dy < 45 * 45) { r = 0.40; g = 0.12; b = 0.10; }
        // objet en mouvement (se déplace de 4 px par image)
        double mx = 120 + 4.0 * k, my = 280;
        if ((x - mx) * (x - mx) + (y - my) * (y - my) < 22 * 22) { r = 0.10; g = 0.35; b = 0.12; }
        out[0] = r; out[1] = g; out[2] = b;
    }

    static final int[][] CFA_RGGB = {{0, 1}, {1, 2}}; // [y&1][x&1] -> 0 R, 1 G, 2 B

    static short[] makeFrame(int k, double sx, double sy, boolean noisy, double fullWell, double readE, Random rng, boolean moving) {
        short[] f = new short[W * H];
        double[] c = new double[3];
        for (int y = 0; y < H; y++)
            for (int x = 0; x < W; x++) {
                int col = CFA_RGGB[y & 1][x & 1];
                double acc = 0;
                for (int j = 0; j < 3; j++)
                    for (int i = 0; i < 3; i++) {
                        scene(x + sx + (i + 0.5) / 3.0 - 0.5, y + sy + (j + 0.5) / 3.0 - 0.5, moving ? k : 0, c);
                        acc += c[col];
                    }
                double lin = acc / 9.0;
                double dn;
                if (noisy) {
                    double e = lin * fullWell;
                    double n = rng.nextGaussian() * Math.sqrt(Math.max(e, 0) + readE * readE);
                    dn = BLACK + (e + n) / fullWell * (WHITE - BLACK);
                } else dn = BLACK + lin * (WHITE - BLACK);
                f[y * W + x] = (short) Math.max(0, Math.min(1023, Math.round(dn)));
            }
        return f;
    }

    static int srgb(double lin) {
        lin = Math.max(0, Math.min(1, lin));
        double v = lin <= 0.0031308 ? 12.92 * lin : 1.055 * Math.pow(lin, 1 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0, Math.min(1, v)) * 255);
    }

    /** Vérité terrain : la scène exacte (objets fixes) à la grille de l'image 0, intégrée par pixel, avec le même gamma. */
    static int[] truth(int factor) {
        int ow = W * factor, oh = H * factor;
        int[] t = new int[ow * oh];
        double[] c = new double[3];
        int ss = 3;
        for (int y = 0; y < oh; y++)
            for (int x = 0; x < ow; x++) {
                double[] acc = new double[3];
                for (int j = 0; j < ss; j++)
                    for (int i = 0; i < ss; i++) {
                        scene((x + (i + 0.5) / ss) / factor - 0.5, (y + (j + 0.5) / ss) / factor - 0.5, 0, c);
                        acc[0] += c[0]; acc[1] += c[1]; acc[2] += c[2];
                    }
                t[y * ow + x] = (srgb(acc[0] / (ss * ss)) << 16) | (srgb(acc[1] / (ss * ss)) << 8) | srgb(acc[2] / (ss * ss));
            }
        return t;
    }

    // Profil de bruit du capteur simulé (variance = S·x + O, x normalisé) et accentuation de sortie, passés au moteur.
    static double curS = 0, curO = 0;
    static float curSharp = 0f;

    static int[] fuse(NativeEngine e, short[][] fr, int factor, float denoise) {
        float[] tone = curS > 0
            ? new float[]{1f, 0f, 1f, denoise, 0f, 0f, (float) curS, (float) curO, curSharp}
            : new float[]{1f, 0f, 1f, denoise, 0f};   // sans exposition auto : sortie = gamma(lumière linéaire)
        return e.fuseRawBurst(fr, W, H, 0, new int[]{BLACK, BLACK, BLACK, BLACK}, WHITE, new float[]{1, 1, 1, 1},
                new float[]{1, 0, 0, 0, 1, 0, 0, 0, 1}, factor, new int[]{0, 0, W, H}, false, tone);
    }

    static double psnr(int[] a, int[] b, int ow, int oh, int margin, int x0, int x1, int y0, int y1) {
        double se = 0; long n = 0;
        for (int y = Math.max(margin, y0); y < Math.min(oh - margin, y1); y++)
            for (int x = Math.max(margin, x0); x < Math.min(ow - margin, x1); x++) {
                int p = a[y * ow + x], q = b[y * ow + x];
                for (int s = 0; s <= 16; s += 8) { double d = ((p >> s) & 255) - ((q >> s) & 255); se += d * d; n++; }
            }
        return 10 * Math.log10(255.0 * 255.0 / Math.max(se / n, 1e-9));
    }

    /** Largeur de montée 10-90 % du contour vertical central (en pixels de sortie), moyennée sur les lignes. */
    static double edgeWidth(int[] img, int ow, int oh, int factor) {
        int cx = (W / 2) * factor, x0 = cx - 12 * factor, x1 = cx + 12 * factor;
        double sum = 0; int rows = 0;
        for (int y = 40 * factor; y < 150 * factor && y < oh; y += 3) {
            double lo = 0, hi = 0;
            for (int x = x0 - 8; x < x0; x++) lo += (img[y * ow + x] >> 8) & 255;
            for (int x = x1; x < x1 + 8; x++) hi += (img[y * ow + x] >> 8) & 255;
            lo /= 8; hi /= 8;
            if (hi - lo < 20) continue;
            double t10 = lo + 0.1 * (hi - lo), t90 = lo + 0.9 * (hi - lo);
            double p10 = -1, p90 = -1;
            for (int x = x0; x < x1; x++) {
                double v = (img[y * ow + x] >> 8) & 255, v2 = (img[y * ow + x + 1] >> 8) & 255;
                if (p10 < 0 && v < t10 && v2 >= t10) p10 = x + (t10 - v) / (v2 - v + 1e-9);
                if (p90 < 0 && v < t90 && v2 >= t90) p90 = x + (t90 - v) / (v2 - v + 1e-9);
            }
            if (p10 >= 0 && p90 >= 0) { sum += p90 - p10; rows++; }
        }
        return rows > 0 ? sum / rows : Double.NaN;
    }

    /** Mode balayage rapide : 8 images bruitées, un seul réglage, métriques fines/contour/plat à ×1 et ×2. */
    static void quick(NativeEngine e, double fullWell) {
        curS = 1.0 / fullWell; curO = 9.0 / (fullWell * fullWell);
        Random sh = new Random(7);
        double[][] shifts = new double[16][2];
        for (int i = 1; i < 16; i++) { shifts[i][0] = sh.nextGaussian() * 0.8; shifts[i][1] = sh.nextGaussian() * 0.8; }
        Random rn = new Random(508);
        short[][] noisy = new short[8][], clean = new short[8][];
        for (int k = 0; k < 8; k++) {
            noisy[k] = makeFrame(k, shifts[k][0], shifts[k][1], true, fullWell, 3.0, rn, false);
            clean[k] = makeFrame(k, shifts[k][0], shifts[k][1], false, fullWell, 3.0, rn, false);
        }
        int[] o1 = fuse(e, noisy, 1, 0.35f), t1 = truth(1), o2 = fuse(e, noisy, 2, 0.35f), t2 = truth(2);
        int[] c1 = fuse(e, clean, 1, 0f), c2 = fuse(e, clean, 2, 0f);
        System.out.printf("x1 bruite: fine %.2f contour %.2f plat %.2f | x1 propre: fine %.2f contour %.2f | x2 bruite: fine %.2f contour %.2f plat %.2f | x2 propre: fine %.2f contour %.2f%n",
            psnr(o1, t1, W, H, 0, 50, 190, 50, 150), psnr(o1, t1, W, H, 0, 236, 276, 50, 150), psnr(o1, t1, W, H, 0, 260, 350, 200, 360),
            psnr(c1, t1, W, H, 0, 50, 190, 50, 150), psnr(c1, t1, W, H, 0, 236, 276, 50, 150),
            psnr(o2, t2, W * 2, H * 2, 0, 100, 380, 100, 300), psnr(o2, t2, W * 2, H * 2, 0, 470, 560, 100, 300), psnr(o2, t2, W * 2, H * 2, 0, 520, 700, 400, 720),
            psnr(c2, t2, W * 2, H * 2, 0, 100, 380, 100, 300), psnr(c2, t2, W * 2, H * 2, 0, 470, 560, 100, 300));
    }

    /** Rapport d'énergie du Laplacien (sortie / vérité) : 1 = même netteté, < 1 = flou, > 1 = sur-accentué ou bruité. */
    static double lapRatio(int[] o, int[] t, int ow, int oh, int x0, int x1, int y0, int y1) {
        double eo = 0, et = 0;
        for (int y = Math.max(2, y0); y < Math.min(oh - 2, y1); y++)
            for (int x = Math.max(2, x0); x < Math.min(ow - 2, x1); x++) {
                int p = (o[y * ow + x] >> 8) & 255, q = (t[y * ow + x] >> 8) & 255;
                double lo = 4 * p - ((o[y * ow + x - 1] >> 8) & 255) - ((o[y * ow + x + 1] >> 8) & 255) - ((o[(y - 1) * ow + x] >> 8) & 255) - ((o[(y + 1) * ow + x] >> 8) & 255);
                double lt = 4 * q - ((t[y * ow + x - 1] >> 8) & 255) - ((t[y * ow + x + 1] >> 8) & 255) - ((t[(y - 1) * ow + x] >> 8) & 255) - ((t[(y + 1) * ow + x] >> 8) & 255);
                eo += lo * lo; et += lt * lt;
            }
        return eo / Math.max(et, 1e-9);
    }

    /** Scène « herbe » photographiée de jour (ISO bas) avec un peu de vent : c'est le cas qui paraît flou sur le téléphone. */
    static void textureBench(NativeEngine e, double fullWell, double wind, float denoise) {
        TEXTURE = true; WIND = wind;
        curS = 1.0 / fullWell; curO = 9.0 / (fullWell * fullWell);
        Random sh = new Random(7);
        double[][] shifts = new double[16][2];
        for (int i = 1; i < 16; i++) { shifts[i][0] = sh.nextGaussian() * 0.8; shifts[i][1] = sh.nextGaussian() * 0.8; }
        int[] t1 = truth(1), t2 = truth(2);
        System.out.printf("Scène herbe : capteur %.0f e-, vent %.2f px, débruitage demandé %.2f%n", fullWell, wind, denoise);
        System.out.println(" images | x1 : PSNR  netteté | x2 : PSNR  netteté   (netteté 1,00 = comme la vraie scène)");
        for (int n : new int[]{1 + 1, 6, 12}) {
            Random rn = new Random(40 + n);
            short[][] noisy = new short[n][];
            for (int k = 0; k < n; k++) noisy[k] = makeFrame(k, shifts[k][0], shifts[k][1], true, fullWell, 3.0, rn, true);
            int[] o1 = fuse(e, noisy, 1, denoise), o2 = fuse(e, noisy, 2, denoise);
            System.out.printf("   %2d   |   %6.2f   %5.2f    |   %6.2f   %5.2f%n", n,
                psnr(o1, t1, W, H, 20, 0, W, 0, H), lapRatio(o1, t1, W, H, 20, W - 20, 20, H - 20),
                psnr(o2, t2, W * 2, H * 2, 40, 0, W * 2, 0, H * 2), lapRatio(o2, t2, W * 2, H * 2, 40, W * 2 - 40, 40, H * 2 - 40));
        }
        TEXTURE = false; WIND = 0; curS = 0; curO = 0;
    }

    public static void main(String[] a) {
        if (a.length > 2 && a[2].equals("texture")) {
            System.load(a[0]);
            NativeEngine e = new NativeEngine();
            double fw = Double.parseDouble(a[1]);
            float dn = a.length > 3 ? Float.parseFloat(a[3]) : 0.2f;
            for (float sharp : new float[]{0f, 0.7f, 1.2f}) {
                curSharp = sharp;
                System.out.printf("%n#### accentuation %.1f ####%n", sharp);
                textureBench(e, fw, 0.35, dn);
            }
            return;
        }
        if (a.length > 2 && a[2].equals("quick")) { System.load(a[0]); quick(new NativeEngine(), Double.parseDouble(a[1])); return; }
        System.load(a[0]);
        NativeEngine e = new NativeEngine();
        double fullWell = a.length > 1 ? Double.parseDouble(a[1]) : 400;   // électrons à saturation : petit = peu de lumière
        double readE = 3.0;
        curS = 1.0 / fullWell; curO = readE * readE / (fullWell * fullWell);
        System.out.printf("Scène %dx%d, capteur : %.0f e- à saturation, bruit de lecture %.1f e- (faible lumière)%n", W, H, fullWell, readE);
        int[] counts = {2, 4, 6, 8, 12};
        // Décalages de main : gaussien sigma 0,8 px (+ une image de référence immobile)
        Random sh = new Random(7);
        double[][] shifts = new double[16][2];
        for (int i = 1; i < 16; i++) { shifts[i][0] = sh.nextGaussian() * 0.8; shifts[i][1] = sh.nextGaussian() * 0.8; }

        System.out.println("\n== 1. Fidélité à la vraie scène (PSNR vs vérité terrain, plus haut = mieux) ==");
        {
            Random rn = new Random(1);
            short[][] clean = new short[8][];
            for (int k = 0; k < 8; k++) clean[k] = makeFrame(k, shifts[k][0], shifts[k][1], false, fullWell, readE, rn, false);
            System.out.printf(" plafond du moteur (8 images SANS bruit) : %.2f dB%n", psnr(fuse(e, clean, 1, 0f), truth(1), W, H, 40, 0, W, 0, H));
        }
        System.out.println(" images | zone PLATE (bruit)        | zone FINE (résolution)");
        System.out.println("        | déb.0   déb.0,35  déb.0,8 | déb.0   déb.0,35  déb.0,8");
        for (int n : counts) {
            Random rn = new Random(100 + n);
            short[][] noisy = new short[n][];
            for (int k = 0; k < n; k++) noisy[k] = makeFrame(k, shifts[k][0], shifts[k][1], true, fullWell, readE, rn, false);
            int[] ref = truth(1);
            float[] ds = {0f, 0.35f, 0.8f};
            double[] fl = new double[3], fi = new double[3];
            for (int i = 0; i < 3; i++) {
                int[] o = fuse(e, noisy, 1, ds[i]);
                fl[i] = psnr(o, ref, W, H, 0, 260, 350, 200, 360);
                fi[i] = psnr(o, ref, W, H, 0, 50, 190, 50, 150);
            }
            System.out.printf("   %2d   | %6.2f  %6.2f  %6.2f   | %6.2f  %6.2f  %6.2f%n", n, fl[0], fl[1], fl[2], fi[0], fi[1], fi[2]);
        }

        System.out.println("\n== 2. Super-résolution ×2 : fidélité à la vraie scène à ×2 (PSNR, plus haut = mieux) ==");
        System.out.println(" images | zone FINE | contour | zone PLATE   (8 images bruitées = ligne 8 ; débruitage 0,35)");
        for (int n : new int[]{2, 4, 8, 12}) {
            Random rn = new Random(500 + n);
            short[][] noisy = new short[n][];
            for (int k = 0; k < n; k++) noisy[k] = makeFrame(k, shifts[k][0], shifts[k][1], true, fullWell, readE, rn, false);
            int[] o = fuse(e, noisy, 2, 0.35f);
            int[] t = truth(2);
            System.out.printf("   %2d   |  %6.2f   | %6.2f  | %6.2f%n", n,
                psnr(o, t, W * 2, H * 2, 0, 100, 380, 100, 300),
                psnr(o, t, W * 2, H * 2, 0, 470, 560, 100, 300),
                psnr(o, t, W * 2, H * 2, 0, 520, 700, 400, 720));
        }
        {
            short[][] clean = new short[8][];
            Random rn = new Random(1);
            for (int k = 0; k < 8; k++) clean[k] = makeFrame(k, shifts[k][0], shifts[k][1], false, fullWell, readE, rn, false);
            int[] o = fuse(e, clean, 2, 0f), t = truth(2);
            System.out.printf(" plafond ×2 (8 images sans bruit) : fine %.2f | contour %.2f | plate %.2f%n",
                psnr(o, t, W * 2, H * 2, 0, 100, 380, 100, 300), psnr(o, t, W * 2, H * 2, 0, 470, 560, 100, 300),
                psnr(o, t, W * 2, H * 2, 0, 520, 700, 400, 720));
        }

        System.out.println("\n== 3. Objet en mouvement (zone autour de l'objet ; fantômes = PSNR bas) ==");
        for (int n : new int[]{6, 8}) {
            Random rn = new Random(300 + n);
            short[][] noisy = new short[n][], clean = new short[n][], still = new short[n][];
            for (int k = 0; k < n; k++) {
                noisy[k] = makeFrame(k, shifts[k][0], shifts[k][1], true, fullWell, readE, rn, true);
                clean[k] = makeFrame(0, shifts[k][0], shifts[k][1], false, fullWell, readE, rn, false);
            }
            // référence : image 0 seule dupliquée sans bruit (vérité de l'instant de référence)
            short[][] truth = new short[n][];
            for (int k = 0; k < n; k++) truth[k] = makeFrame(0, 0, 0, false, fullWell, readE, rn, true);
            int[] ref = truth(1);
            int[] out = fuse(e, noisy, 1, 0.35f);
            System.out.printf(" %d images : zone mobile PSNR %.2f dB | zone fixe PSNR %.2f dB%n", n,
                psnr(out, ref, W, H, 0, 90, 220, 240, 330), psnr(out, ref, W, H, 40, 250, 480, 170, 360));
        }
    }
}
