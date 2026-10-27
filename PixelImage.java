package dev.styleforge.style;

/** Kleine Pixelgrafik (ARGB) fuer die 3D-Vorschau. */
public record PixelImage(int w, int h, int[] argb) {

    /** Erzeugt eine einfache Platzhalter-Textur aus einer Farbe (fuer eingebaute Styles ohne PNG). */
    public static PixelImage synth(int rgb, int w, int h) {
        int[] a = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double f = 1.12 - 0.42 * y / (double) h;
                boolean edge = x == 0 || y == 0 || x == w - 1 || y == h - 1;
                if (edge) {
                    f = 0.55;
                } else if (((x + y) & 3) == 0) {
                    f += 0.07;
                }
                a[y * w + x] = scale(rgb, f);
            }
        }
        return new PixelImage(w, h, a);
    }

    private static int scale(int rgb, double f) {
        int r = (int) Math.max(0, Math.min(255, ((rgb >> 16) & 0xFF) * f));
        int g = (int) Math.max(0, Math.min(255, ((rgb >> 8) & 0xFF) * f));
        int b = (int) Math.max(0, Math.min(255, (rgb & 0xFF) * f));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
