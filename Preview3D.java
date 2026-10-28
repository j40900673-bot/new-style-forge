package dev.styleforge.ui;

import dev.styleforge.style.PixelImage;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * 3D-Vorschau in voller Bildschirmaufloesung: Ein kleiner Raytracer rechnet auf einem Hintergrund-Thread
 * ein Bild (1 Pixel pro Bildschirmpixel). Das Bild wird als eigene Textur hochgeladen und mit einem einzigen
 * Aufruf gezeichnet. Dadurch ist die Vorschau scharf und das Menue bleibt fluessig.
 */
final class Preview3D {
    private static final Logger LOG = LoggerFactory.getLogger("styleforge");

    private Preview3D() {}

    // ------------------------------------------------------------------ Textur-Flaeche

    private record Result(byte[] png, int n, Object key) {}

    private static final class Surface {
        final Identifier id;
        final String name;
        volatile Result result;      // vom Hintergrund-Thread geschrieben
        volatile boolean busy;
        long lastStart;              // ab hier nur Render-Thread
        Object requestedKey;
        int requestedN;
        Result shown;
        NativeImageBackedTexture tex;
        boolean broken;

        Surface(String path) {
            this.id = Identifier.of("styleforge", path);
            this.name = "styleforge_" + path;
        }
    }

    private static final Surface CUBE = new Surface("preview_cube");
    private static final Surface CRYSTAL = new Surface("preview_crystal");
    private static final Surface ITEM = new Surface("preview_item");
    private static final Surface SHEET = new Surface("preview_sheet");

    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "StyleForge-Preview");
        t.setDaemon(true);
        return t;
    });

    /** Bildpunkte fuer eine Flaeche von guiSize GUI-Pixeln: genau ein Bildpunkt je Bildschirmpixel (max. Faktor 4). */
    private static int pixels(int guiSize) {
        int sf = (int) Math.round(MinecraftClient.getInstance().getWindow().getScaleFactor());
        return guiSize * Math.max(1, Math.min(sf, 4));
    }

    private static double secs() {
        return (System.currentTimeMillis() % 600000L) / 1000.0;
    }

    private static void show(DrawContext ctx, Surface s, Object key, int n, long intervalMs,
                             Supplier<int[]> job, int x, int y, int size) {
        if (s.broken) return;
        long now = System.currentTimeMillis();
        boolean stale = s.requestedKey != key || s.requestedN != n || (intervalMs > 0 && now - s.lastStart >= intervalMs);
        if (stale && !s.busy) {
            s.busy = true;
            s.lastStart = now;
            s.requestedKey = key;
            s.requestedN = n;
            POOL.execute(() -> {
                try {
                    int[] fb = job.get();
                    s.result = new Result(encode(fb, n), n, key);
                } catch (Throwable t) {
                    LOG.warn("3D-Vorschau fehlgeschlagen: {}", t.toString());
                } finally {
                    s.busy = false;
                }
            });
        }
        Result r = s.result;
        if (r != null && r != s.shown) {
            upload(s, r);
        }
        if (s.tex != null && s.shown != null && s.shown.key() == key && s.shown.n() == n) {
            // Reihenfolge: x, y, u, v, Breite, Hoehe, Ausschnitt-Breite/-Hoehe, Textur-Breite/-Hoehe
            ctx.drawTexture(RenderPipelines.GUI_TEXTURED, s.id, x, y, 0f, 0f, size, size, n, n, n, n);
        }
    }

    private static byte[] encode(int[] fb, int n) throws IOException {
        BufferedImage bi = new BufferedImage(n, n, BufferedImage.TYPE_INT_ARGB);
        bi.setRGB(0, 0, n, n, fb, 0, n);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(n * n);
        ImageIO.write(bi, "png", bos);
        return bos.toByteArray();
    }

    private static void upload(Surface s, Result r) {
        try {
            NativeImage image = NativeImage.read(new ByteArrayInputStream(r.png()));
            TextureManager tm = MinecraftClient.getInstance().getTextureManager();
            tm.destroyTexture(s.id);
            NativeImageBackedTexture fresh = new NativeImageBackedTexture(() -> s.name, image);
            tm.registerTexture(s.id, fresh);
            s.tex = fresh;
            s.shown = r;
        } catch (IOException | RuntimeException e) {
            LOG.warn("3D-Vorschau konnte nicht hochgeladen werden: {}", e.toString());
            s.broken = true;
        }
    }

    // ------------------------------------------------------------------ Oeffentliche Ansichten

    /** Block wie im Inventar (30 Grad geneigt, 225 Grad gedreht). */
    static void cube(DrawContext ctx, PixelImage img, int ax, int ay, int aw, int ah) {
        int size = Math.max(16, Math.min(aw, ah));
        int n = pixels(size);
        show(ctx, CUBE, img, n, 0, () -> renderCube(img, n), ax + (aw - size) / 2, ay + (ah - size) / 2, size);
    }

    /** Drehender End-Kristall mit Sockel. */
    static void crystal(DrawContext ctx, PixelImage img, int ax, int ay, int aw, int ah) {
        int size = Math.max(16, Math.min(aw, ah));
        int n = pixels(size);
        show(ctx, CRYSTAL, img, n, 60, () -> renderCrystal(img, n, secs()), ax + (aw - size) / 2, ay + (ah - size) / 2, size);
    }

    /** Drehendes Item mit echter Dicke (jedes Pixel ist ein Wuerfel, wie bei Minecraft-Items). */
    static void item(DrawContext ctx, PixelImage img, int ax, int ay, int aw, int ah) {
        int size = Math.max(16, Math.min(aw, ah));
        int n = pixels(size);
        show(ctx, ITEM, img, n, 60, () -> renderItem(img, n, secs()), ax + (aw - size) / 2, ay + (ah - size) / 2, size);
    }

    /** Textur-Blatt (z.B. Schild), so gross wie moeglich. */
    static void sheet(DrawContext ctx, PixelImage img, int ax, int ay, int aw, int ah) {
        int size = Math.max(16, Math.min(aw, ah));
        int n = pixels(size);
        show(ctx, SHEET, img, n, 0, () -> renderSheet(img, n), ax + (aw - size) / 2, ay + (ah - size) / 2, size);
    }

    // ------------------------------------------------------------------ Mathe

    static int shade(int argb, double f) {
        int r = (int) Math.min(255, ((argb >> 16) & 0xFF) * f);
        int g = (int) Math.min(255, ((argb >> 8) & 0xFF) * f);
        int b = (int) Math.min(255, (argb & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int px(PixelImage img, int x, int y) {
        return img.argb()[y * img.w() + x];
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

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

    // Zwischenspeicher: werden nur vom einen Hintergrund-Thread benutzt
    private static final double[] SO = new double[3];
    private static final double[] SD = new double[3];

    // ------------------------------------------------------------------ Textur-Blatt

    private static int[] renderSheet(PixelImage img, int n) {
        int[] fb = new int[n * n];
        double s = Math.min((double) n / img.w(), (double) n / img.h());
        int dw = Math.max(1, (int) (img.w() * s));
        int dh = Math.max(1, (int) (img.h() * s));
        int ox = (n - dw) / 2;
        int oy = (n - dh) / 2;
        for (int y = 0; y < dh; y++) {
            int iy = clamp(y * img.h() / dh, 0, img.h() - 1);
            for (int x = 0; x < dw; x++) {
                int ix = clamp(x * img.w() / dw, 0, img.w() - 1);
                int c = px(img, ix, iy);
                if ((c >>> 24) >= 128) fb[(oy + y) * n + ox + x] = c | 0xFF000000;
            }
        }
        return fb;
    }

    // ------------------------------------------------------------------ Block (Inventar-Ansicht)

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
            c = blockFace(img, m, tFar, aFar, false);
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
        int ix = clamp((int) (u * img.w()), 0, img.w() - 1);
        int iy = clamp((int) (v * img.h()), 0, img.h() - 1);
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

    // ------------------------------------------------------------------ Item (Voxel, wie in Minecraft)

    private static int[] renderItem(PixelImage img, int n, double secs) {
        int w = img.w(), h = img.h();
        double yaw = Math.toRadians((secs * 72.0) % 360.0);
        double[] rot = mul(axisAngle(1, 0, 0, Math.toRadians(-18)), rotY(yaw));
        double unit = n / (Math.max(w, h) * 1.5);
        double[] o = new double[3];
        double[] d = {-rot[6], -rot[7], -rot[8]};
        int[] fb = new int[n * n];
        for (int py = 0; py < n; py++) {
            double sy = (n / 2.0 - py - 0.5) / unit;
            for (int pxl = 0; pxl < n; pxl++) {
                double sx = (pxl + 0.5 - n / 2.0) / unit;
                o[0] = rot[0] * sx + rot[3] * sy + rot[6] * 40 + w / 2.0;
                o[1] = rot[1] * sx + rot[4] * sy + rot[7] * 40 + h / 2.0;
                o[2] = rot[2] * sx + rot[5] * sy + rot[8] * 40 + 0.5;
                fb[py * n + pxl] = traceVoxel(img, rot, o, d);
            }
        }
        return fb;
    }

    /** Strahl durch ein Gitter aus w x h x 1 Wuerfeln; jeder nicht durchsichtige Bildpunkt ist ein Wuerfel. */
    private static int traceVoxel(PixelImage img, double[] rot, double[] o, double[] d) {
        int w = img.w(), h = img.h();
        double[] max = {w, h, 1};
        double tEnter = -1e18, tExit = 1e18;
        int axis = 0;
        for (int a = 0; a < 3; a++) {
            if (Math.abs(d[a]) < 1e-12) {
                if (o[a] < 0 || o[a] > max[a]) return 0;
                continue;
            }
            double t1 = -o[a] / d[a];
            double t2 = (max[a] - o[a]) / d[a];
            double lo = Math.min(t1, t2), hi = Math.max(t1, t2);
            if (lo > tEnter) { tEnter = lo; axis = a; }
            if (hi < tExit) tExit = hi;
        }
        if (tEnter > tExit) return 0;
        double t = tEnter + 1e-7;
        int ix = clamp((int) Math.floor(o[0] + d[0] * t), 0, w - 1);
        int iy = clamp((int) Math.floor(o[1] + d[1] * t), 0, h - 1);
        int iz = 0;
        int sx = d[0] > 0 ? 1 : -1, sy = d[1] > 0 ? 1 : -1, sz = d[2] > 0 ? 1 : -1;
        double tdx = Math.abs(d[0]) < 1e-12 ? 1e18 : Math.abs(1.0 / d[0]);
        double tdy = Math.abs(d[1]) < 1e-12 ? 1e18 : Math.abs(1.0 / d[1]);
        double tdz = Math.abs(d[2]) < 1e-12 ? 1e18 : Math.abs(1.0 / d[2]);
        double tmx = Math.abs(d[0]) < 1e-12 ? 1e18 : ((sx > 0 ? ix + 1 : ix) - o[0]) / d[0];
        double tmy = Math.abs(d[1]) < 1e-12 ? 1e18 : ((sy > 0 ? iy + 1 : iy) - o[1]) / d[1];
        double tmz = Math.abs(d[2]) < 1e-12 ? 1e18 : ((sz > 0 ? iz + 1 : iz) - o[2]) / d[2];
        int face = axis;
        int fsign = d[axis] > 0 ? -1 : 1;
        for (int i = 0; i < w + h + 6; i++) {
            int c = px(img, ix, h - 1 - iy);
            if ((c >>> 24) >= 128) {
                return shadeVoxel(c, rot, face, fsign);
            }
            if (tmx <= tmy && tmx <= tmz) {
                ix += sx;
                tmx += tdx;
                face = 0;
                fsign = -sx;
            } else if (tmy <= tmz) {
                iy += sy;
                tmy += tdy;
                face = 1;
                fsign = -sy;
            } else {
                iz += sz;
                tmz += tdz;
                face = 2;
                fsign = -sz;
            }
            if (ix < 0 || ix >= w || iy < 0 || iy >= h || iz < 0 || iz >= 1) return 0;
        }
        return 0;
    }

    private static int shadeVoxel(int c, double[] rot, int face, int fsign) {
        double nx = face == 0 ? fsign : 0, ny = face == 1 ? fsign : 0, nz = face == 2 ? fsign : 0;
        double wx = rot[0] * nx + rot[1] * ny + rot[2] * nz;
        double wy = rot[3] * nx + rot[4] * ny + rot[5] * nz;
        double wz = rot[6] * nx + rot[7] * ny + rot[8] * nz;
        double light = 0.5 + 0.5 * Math.max(0, 0.3 * wx + 0.75 * wy + 0.6 * wz);
        return shade(c, light);
    }

    // ------------------------------------------------------------------ End-Kristall

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

    private static final double[] CP = new double[3];
    private static final double[] CH = new double[3];
    private static double bestT;
    private static int bestColor;

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
            if (sy < -13.5 || sy > 12.0) continue; // ausserhalb des Kristalls
            for (int pxl = 0; pxl < n; pxl++) {
                double sx = (pxl + 0.5 - n / 2.0) / scale;
                if (Math.abs(sx) > 7.6) continue;
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

    /** Farbe aus der 64x32-Textur nach dem Minecraft-Quader-Layout. */
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
        int tx = ru + clamp((int) (s * rw), 0, rw - 1);
        int ty = rv + clamp((int) (t * rh), 0, rh - 1);
        int ix = Math.min(img.w() - 1, tx * img.w() / 64);
        int iy = Math.min(img.h() - 1, ty * img.h() / 32);
        return img.argb()[iy * img.w() + ix];
    }
}
