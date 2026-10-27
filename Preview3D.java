package dev.styleforge.ui;

import dev.styleforge.style.PixelImage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * 3D-Vorschau nur mit Rechtecken (DrawContext.fill). Fuer scharfe Bilder wird in "Zellen" gerechnet, die
 * kleiner als ein GUI-Pixel sind (die Zellgroesse ist immer eine ganze Zahl Bildschirmpixel). Die fertigen
 * Bilder werden zwischengespeichert, gleiche Rechtecke werden zu groesseren zusammengefasst.
 */
final class Preview3D {
    private Preview3D() {}

    // ------------------------------------------------------------------ Aufloesung

    /** Zellen pro GUI-Pixel. Beispiel: GUI-Skalierung 4 -> 2 Zellen, jede Zelle 2x2 Bildschirmpixel. */
    static int res() {
        int s = (int) Math.round(MinecraftClient.getInstance().getWindow().getScaleFactor());
        if (s <= 1) return 1;
        if (s % 3 == 0) return 3;
        if (s % 2 == 0) return 2;
        return 1;
    }

    private static void begin(DrawContext ctx, int ox, int oy, int r) {
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate((float) ox, (float) oy);
        m.scale(1f / r, 1f / r);
    }

    private static void end(DrawContext ctx) {
        ctx.getMatrices().popMatrix();
    }

    // ------------------------------------------------------------------ Farben und Bilder

    static int shade(int argb, double f) {
        int r = (int) Math.min(255, ((argb >> 16) & 0xFF) * f);
        int g = (int) Math.min(255, ((argb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (argb & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int px(PixelImage img, int x, int y) {
        return img.argb()[y * img.w() + x];
    }

    /** Zeichnet das Bild auf die Flaeche (x, y, w, h); gleiche Pixel einer Zeile werden zusammengefasst. */
    private static void image(DrawContext ctx, PixelImage img, int x, int y, int w, int h, double shade, boolean flip) {
        int iw = img.w();
        int ih = img.h();
        for (int r = 0; r < ih; r++) {
            int y1 = y + r * h / ih;
            int y2 = y + (r + 1) * h / ih;
            if (y2 <= y1) continue;
            int c = 0;
            while (c < iw) {
                int col = px(img, flip ? iw - 1 - c : c, r);
                int run = c + 1;
                while (run < iw && px(img, flip ? iw - 1 - run : run, r) == col) run++;
                if ((col >>> 24) >= 128) {
                    int x1 = x + c * w / iw;
                    int x2 = x + run * w / iw;
                    if (x2 > x1) {
                        ctx.fill(x1, y1, x2, y2, shade == 1.0 ? (col | 0xFF000000) : shade(col, shade));
                    }
                }
                c = run;
            }
        }
    }

    /** Fertiges Pixelraster (n x n Zellen) zeichnen; gleiche Rechtecke ueber mehrere Zeilen werden verbunden. */
    private static void drawGrid(DrawContext ctx, int[] fb, int n, int ox, int oy, int r) {
        begin(ctx, ox, oy, r);
        int[] pX0 = new int[n], pX1 = new int[n], pC = new int[n], pY = new int[n];
        int[] cX0 = new int[n], cX1 = new int[n], cC = new int[n], cY = new int[n];
        boolean[] pMatched = new boolean[n];
        int pn = 0;
        for (int y = 0; y <= n; y++) {
            int cn = 0;
            if (y < n) {
                int x = 0;
                while (x < n) {
                    int col = fb[y * n + x];
                    int run = x + 1;
                    while (run < n && fb[y * n + run] == col) run++;
                    if (col != 0) {
                        cX0[cn] = x;
                        cX1[cn] = run;
                        cC[cn] = col;
                        cY[cn] = y;
                        cn++;
                    }
                    x = run;
                }
            }
            for (int k = 0; k < pn; k++) pMatched[k] = false;
            int j = 0;
            for (int i = 0; i < cn; i++) {
                while (j < pn && pX0[j] < cX0[i]) j++;
                if (j < pn && pX0[j] == cX0[i] && pX1[j] == cX1[i] && pC[j] == cC[i]) {
                    cY[i] = pY[j];
                    pMatched[j] = true;
                }
            }
            for (int k = 0; k < pn; k++) {
                if (!pMatched[k]) ctx.fill(pX0[k], pY[k], pX1[k], y, pC[k]);
            }
            int[] t;
            t = pX0; pX0 = cX0; cX0 = t;
            t = pX1; pX1 = cX1; cX1 = t;
            t = pC; pC = cC; cC = t;
            t = pY; pY = cY; cY = t;
            pn = cn;
        }
        end(ctx);
    }

    /** Merkt sich das zuletzt berechnete Bild, solange Textur, Groesse und Zeitschritt gleich bleiben. */
    private static final class Cache {
        PixelImage img;
        int n, r, bucket = -1;
        int[] fb;

        int[] get(PixelImage i, int n2, int r2, int b) {
            return (fb != null && img == i && n == n2 && r == r2 && bucket == b) ? fb : null;
        }

        void put(PixelImage i, int n2, int r2, int b, int[] data) {
            img = i;
            n = n2;
            r = r2;
            bucket = b;
            fb = data;
        }
    }

    private static final Cache CUBE_CACHE = new Cache();
    private static final Cache CRYSTAL_CACHE = new Cache();

    // ------------------------------------------------------------------ Textur-Blatt und Item

    /** Textur-Blatt (z.B. Schild), so gross wie moeglich. */
    static void sheet(DrawContext ctx, PixelImage img, int ax, int ay, int aw, int ah) {
        int r = res();
        begin(ctx, ax, ay, r);
        int cw = aw * r, ch = ah * r;
        double s = Math.min(cw / (double) img.w(), ch / (double) img.h());
        int dw = Math.max(1, (int) (img.w() * s));
        int dh = Math.max(1, (int) (img.h() * s));
        image(ctx, img, (cw - dw) / 2, (ch - dh) / 2, dw, dh, 1.0, false);
        end(ctx);
    }

    /** Drehendes Item: Breite folgt dem Kosinus, die Dicke wird durch dunklere Ebenen dahinter gezeigt. */
    static void item(DrawContext ctx, PixelImage img, int ax, int ay, int aw, int ah) {
        int r = res();
        begin(ctx, ax, ay, r);
        int cw = aw * r, ch = ah * r;
        double ang = (System.currentTimeMillis() % 5000L) / 5000.0 * Math.PI * 2;
        double cos = Math.cos(ang);
        double sin = Math.sin(ang);
        int s = Math.max(1, Math.min(cw / img.w(), ch / img.h()));
        int fullW = img.w() * s;
        int fullH = img.h() * s;
        int dw = Math.max(2, (int) Math.round(fullW * Math.max(0.12, Math.abs(cos))));
        int cx = cw / 2;
        int y = (ch - fullH) / 2;
        boolean flip = cos < 0;
        int depth = 5;
        for (int l = depth; l >= 1; l--) {
            int off = (int) Math.round(sin * l * 0.8 * r);
            image(ctx, img, cx - dw / 2 + off, y, dw, fullH, 0.45 + 0.02 * (depth - l), flip);
        }
        image(ctx, img, cx - dw / 2, y, dw, fullH, 1.0, flip);
        end(ctx);
    }

    // ------------------------------------------------------------------ Mathe (ohne Objekte pro Pixel)

    private static double[] rotY(double a) {
        double c = Math.cos(a), s = Math.sin(a);
        return new double[]{c, 0, s, 0, 1, 0, -s, 0, c};
    }

    private static double[] axisAngle(double x, double y, double z, double a) {
        double len = Math.sqrt(x * x + y * y + z * z);
        x /= len;
        y /= len;
        z /= len;
        double c = Math.cos(a), s = Math.sin(a), t = 1 - c;
        return new double[]{
                t * x * x + c, t * x * y - s * z, t * x * z + s * y,
                t * x * y + s * z, t * y * y + c, t * y * z - s * x,
                t * x * z - s * y, t * y * z + s * x, t * z * z + c};
    }

    private static double[] mul(double[] a, double[] b) {
        double[] r = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                r[i * 3 + j] = a[i * 3] * b[j] + a[i * 3 + 1] * b[3 + j] + a[i * 3 + 2] * b[6 + j];
            }
        }
        return r;
    }

    /** out = (transponierte Matrix * Vektor) * k  (Welt -> Objekt bei reiner Drehung). */
    private static void mulTInto(double[] m, double x, double y, double z, double[] out, double k) {
        out[0] = (m[0] * x + m[3] * y + m[6] * z) * k;
        out[1] = (m[1] * x + m[4] * y + m[7] * z) * k;
        out[2] = (m[2] * x + m[5] * y + m[8] * z) * k;
    }

    private static final double[] SO = new double[3]; // Strahl-Ursprung (Zwischenspeicher)
    private static final double[] SD = new double[3]; // Strahl-Richtung (Zwischenspeicher)

    // ------------------------------------------------------------------ Block (Inventar-Ansicht)

    /**
     * Block wie im Inventar: gleiche Kameradrehung wie im Spiel (30 Grad nach vorn, 225 Grad gedreht),
     * pro Zelle per Strahl berechnet, Textur ohne Weichzeichnen. Helligkeit: oben 100 %, links 80 %, rechts 60 %.
     */
    static void cube(DrawContext ctx, PixelImage img, int ax, int ay, int aw, int ah) {
        int r = res();
        int size = Math.max(16, Math.min(aw, ah));
        int n = size * r;
        int ox = ax + (aw - size) / 2;
        int oy = ay + (ah - size) / 2;
        int[] fb = CUBE_CACHE.get(img, n, r, 0);
        if (fb == null) {
            fb = renderCube(img, n);
            CUBE_CACHE.put(img, n, r, 0, fb);
        }
        drawGrid(ctx, fb, n, ox, oy, r);
    }

    private static int[] renderCube(PixelImage img, int n) {
        double[] m = mul(axisAngle(1, 0, 0, Math.toRadians(30)), rotY(Math.toRadians(225)));
        double scale = n / 3.4;
        SD[0] = -m[6];
        SD[1] = -m[7];
        SD[2] = -m[8];
        int[] fb = new int[n * n];
        for (int py = 0; py < n; py++) {
            double sy = (n / 2.0 - py - 0.5) / scale;
            for (int pxl = 0; pxl < n; pxl++) {
                double sx = (pxl + 0.5 - n / 2.0) / scale;
                SO[0] = m[0] * sx + m[3] * sy + m[6] * 10;
                SO[1] = m[1] * sx + m[4] * sy + m[7] * 10;
                SO[2] = m[2] * sx + m[5] * sy + m[8] * 10;
                fb[py * n + pxl] = castBlock(img, m);
            }
        }
        return fb;
    }

    private static int castBlock(PixelImage img, double[] m) {
        double tNear = -1e18, tFar = 1e18;
        int aNear = 0, aFar = 0;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(SD[a]) < 1e-9) {
                if (Math.abs(SO[a]) > 1) return 0;
                continue;
            }
            double t1 = (-1 - SO[a]) / SD[a];
            double t2 = (1 - SO[a]) / SD[a];
            double lo = Math.min(t1, t2), hi = Math.max(t1, t2);
            if (lo > tNear) { tNear = lo; aNear = a; }
            if (hi < tFar) { tFar = hi; aFar = a; }
        }
        if (tNear > tFar) return 0;
        int c = blockFace(img, m, tNear, aNear, true);
        if (c == 0) {
            c = blockFace(img, m, tFar, aFar, false); // Innenseite hinten, falls die Vorderseite durchsichtig ist
        }
        return c;
    }

    private static int blockFace(PixelImage img, double[] m, double t, int axis, boolean entry) {
        double px = SO[0] + SD[0] * t, py = SO[1] + SD[1] * t, pz = SO[2] + SD[2] * t;
        int sign = (SD[axis] > 0) == entry ? -1 : 1;
        double u, v;
        if (axis == 1) {
            u = (px + 1) / 2;
            v = sign > 0 ? (pz + 1) / 2 : (1 - pz) / 2;
        } else if (axis == 0) {
            u = sign > 0 ? (1 - pz) / 2 : (pz + 1) / 2;
            v = (1 - py) / 2;
        } else {
            u = sign > 0 ? (px + 1) / 2 : (1 - px) / 2;
            v = (1 - py) / 2;
        }
        int ix = Math.max(0, Math.min(img.w() - 1, (int) (u * img.w())));
        int iy = Math.max(0, Math.min(img.h() - 1, (int) (v * img.h())));
        int c = px(img, ix, iy);
        if ((c >>> 24) < 128) return 0;

        double nx = axis == 0 ? sign : 0, ny = axis == 1 ? sign : 0, nz = axis == 2 ? sign : 0;
        double wx = m[0] * nx + m[1] * ny + m[2] * nz;
        double wy = m[3] * nx + m[4] * ny + m[5] * nz;
        double light;
        if (wy > 0.5) light = 1.0;
        else if (wy < -0.5) light = 0.5;
        else light = wx < 0 ? 0.8 : 0.6;
        if (!entry) light *= 0.85;
        return shade(c, light);
    }

    // ------------------------------------------------------------------ End-Kristall

    /** Ein Quader im Kristall-Modell mit allem, was pro Bildpunkt gebraucht wird. */
    private static final class Obj {
        double[] rot;
        double hx, hy, hz;
        int u, v, w, h, d;
        final double[] a = new double[3], b = new double[3], c0 = new double[3], dd = new double[3];
    }

    private static Obj obj(double[] rot, double scale, double cx, double cy, double cz,
                           double hx, double hy, double hz, int u, int v, int w, int h, int d,
                           double[] f, double[] up) {
        Obj o = new Obj();
        o.rot = rot;
        o.hx = hx;
        o.hy = hy;
        o.hz = hz;
        o.u = u;
        o.v = v;
        o.w = w;
        o.h = h;
        o.d = d;
        double inv = 1.0 / scale;
        mulTInto(rot, 1, 0, 0, o.a, inv);
        mulTInto(rot, up[0], up[1], up[2], o.b, inv);
        mulTInto(rot, -100 * f[0] - cx, -100 * f[1] - cy, -100 * f[2] - cz, o.c0, inv);
        mulTInto(rot, f[0], f[1], f[2], o.dd, inv);
        return o;
    }

    private static final double[] CP = new double[3]; // Strahl-Ursprung im Objekt
    private static final double[] CH = new double[3]; // Trefferpunkt im Objekt
    private static double bestT;
    private static int bestColor;

    /** Drehender End-Kristall mit Sockel, gerendert aus der 64x32-Entity-Textur. */
    static void crystal(DrawContext ctx, PixelImage img, int ax, int ay, int aw, int ah) {
        int r = res();
        int size = Math.max(16, Math.min(aw, ah));
        int n = size * r;
        int ox = ax + (aw - size) / 2;
        int oy = ay + (ah - size) / 2;
        int bucket = (int) (System.currentTimeMillis() / 50L); // 20 neue Bilder pro Sekunde
        int[] fb = CRYSTAL_CACHE.get(img, n, r, bucket);
        if (fb == null) {
            fb = renderCrystal(img, n, bucket * 0.05);
            CRYSTAL_CACHE.put(img, n, r, bucket, fb);
        }
        drawGrid(ctx, fb, n, ox, oy, r);
    }

    private static int[] renderCrystal(PixelImage img, int n, double secs) {
        double k = Math.toRadians((secs * 60.0) % 360.0);
        double bob = Math.sin(secs * 3.0) * 0.8;
        double shift = 1.5;

        double[] q = axisAngle(Math.sqrt(0.5), 0, Math.sqrt(0.5), Math.toRadians(60));
        double[] step = mul(q, rotY(k));
        double[] m1 = mul(rotY(k), q);
        double[] m2 = mul(m1, step);
        double[] m3 = mul(m2, step);
        double[] id = {1, 0, 0, 0, 1, 0, 0, 0, 1};

        double phi = Math.toRadians(18);
        double[] f = {0, -Math.sin(phi), -Math.cos(phi)};
        double[] up = {0, Math.cos(phi), -Math.sin(phi)};

        Obj[] objs = {
                obj(id, 1.0, 0, -10 + shift, 0, 6, 2, 6, 0, 16, 12, 4, 12, f, up),
                obj(m3, 0.765625, 0, 2 + bob + shift, 0, 4, 4, 4, 32, 0, 8, 8, 8, f, up),
                obj(m2, 0.875, 0, 2 + bob + shift, 0, 4, 4, 4, 0, 0, 8, 8, 8, f, up),
                obj(m1, 1.0, 0, 2 + bob + shift, 0, 4, 4, 4, 0, 0, 8, 8, 8, f, up)
        };

        double scale = n / 34.0;
        int[] fb = new int[n * n];
        for (int py = 0; py < n; py++) {
            double sy = (n / 2.0 - py - 0.5) / scale;
            for (int pxl = 0; pxl < n; pxl++) {
                double sx = (pxl + 0.5 - n / 2.0) / scale;
                bestT = 1e18;
                bestColor = 0;
                for (Obj o : objs) {
                    traceObj(img, o, sx, sy);
                }
                fb[py * n + pxl] = bestColor;
            }
        }
        return fb;
    }

    private static void traceObj(PixelImage img, Obj o, double sx, double sy) {
        for (int i = 0; i < 3; i++) {
            CP[i] = o.c0[i] + sx * o.a[i] + sy * o.b[i];
        }
        double tNear = -1e18, tFar = 1e18;
        int aNear = 0, aFar = 0;
        for (int a = 0; a < 3; a++) {
            double half = a == 0 ? o.hx : a == 1 ? o.hy : o.hz;
            double d = o.dd[a];
            if (Math.abs(d) < 1e-9) {
                if (Math.abs(CP[a]) > half) return;
                continue;
            }
            double t1 = (-half - CP[a]) / d;
            double t2 = (half - CP[a]) / d;
            double lo = Math.min(t1, t2), hi = Math.max(t1, t2);
            if (lo > tNear) { tNear = lo; aNear = a; }
            if (hi < tFar) { tFar = hi; aFar = a; }
        }
        if (tNear > tFar) return;
        crystalFace(img, o, tNear, aNear, true);
        crystalFace(img, o, tFar, aFar, false);
    }

    private static void crystalFace(PixelImage img, Obj o, double t, int axis, boolean entry) {
        if (t >= bestT) return;
        CH[0] = CP[0] + o.dd[0] * t;
        CH[1] = CP[1] + o.dd[1] * t;
        CH[2] = CP[2] + o.dd[2] * t;
        int sign = (o.dd[axis] > 0) == entry ? -1 : 1;
        int c = sampleObj(img, o, axis, sign);
        if ((c >>> 24) < 128) return;
        double nx = axis == 0 ? sign : 0, ny = axis == 1 ? sign : 0, nz = axis == 2 ? sign : 0;
        double[] rr = o.rot;
        double wx = rr[0] * nx + rr[1] * ny + rr[2] * nz;
        double wy = rr[3] * nx + rr[4] * ny + rr[5] * nz;
        double wz = rr[6] * nx + rr[7] * ny + rr[8] * nz;
        double light = 0.62 + 0.38 * Math.max(0, 0.25 * wx + 0.9 * wy + 0.35 * wz);
        bestT = t;
        bestColor = shade(c, light);
    }

    /** Farbe aus der 64x32-Textur nach dem Minecraft-Quader-Layout (oben, unten, links, vorn, rechts, hinten). */
    private static int sampleObj(PixelImage img, Obj o, int axis, int sign) {
        double fw = 2 * o.hx, fh = 2 * o.hy, fd = 2 * o.hz;
        int ru, rv, rw, rh;
        double s, t;
        if (axis == 1) {
            rw = o.w;
            rh = o.d;
            rv = o.v;
            ru = sign > 0 ? o.u + o.d : o.u + o.d + o.w;
            s = (CH[0] + o.hx) / fw;
            t = (CH[2] + o.hz) / fd;
        } else if (axis == 0) {
            rw = o.d;
            rh = o.h;
            rv = o.v + o.d;
            ru = sign < 0 ? o.u : o.u + o.d + o.w;
            s = (CH[2] + o.hz) / fd;
            t = 1 - (CH[1] + o.hy) / fh;
        } else {
            rw = o.w;
            rh = o.h;
            rv = o.v + o.d;
            ru = sign < 0 ? o.u + o.d : o.u + 2 * o.d + o.w;
            s = (CH[0] + o.hx) / fw;
            t = 1 - (CH[1] + o.hy) / fh;
        }
        int tx = ru + Math.max(0, Math.min(rw - 1, (int) (s * rw)));
        int ty = rv + Math.max(0, Math.min(rh - 1, (int) (t * rh)));
        int ix = Math.min(img.w() - 1, tx * img.w() / 64);
        int iy = Math.min(img.h() - 1, ty * img.h() / 32);
        return img.argb()[iy * img.w() + ix];
    }
}
