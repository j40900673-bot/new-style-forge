package dev.styleforge.style;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

/**
 * Liest eigene PNG-Dateien aus {@code <Spielordner>/styleforge/custom/<kategorie>/} ein
 * und macht daraus Styles. Defekte Dateien werden uebersprungen, nie ein Absturz.
 */
public final class CustomStyles {
    private static final Logger LOG = LoggerFactory.getLogger("styleforge");
    private static final long MAX_BYTES = 4L * 1024 * 1024;
    private static final int MAX_DIM = 2048;
    private static final int BG = 0x14141C; // Hintergrund fuer transparente Pixel

    private record Cached(long mtime, long size, Style style) {}

    private static final Map<Path, Cached> CACHE = new HashMap<>();

    private record CachedPix(long mtime, long size, PixelImage img) {}

    private static final Map<Path, CachedPix> PIX = new HashMap<>();

    private CustomStyles() {}

    public static Path root() {
        return FabricLoader.getInstance().getGameDir().resolve("styleforge").resolve("custom");
    }

    /**
     * Oeffnet den Ordner im Datei-Explorer des Betriebssystems (ohne Minecraft-API, damit es versionsunabhaengig bleibt).
     * Ohne Kategorie wird der Hauptordner geoeffnet.
     */
    public static boolean openFolder(Category c) {
        return openPath(c == null ? root() : root().resolve(c.id));
    }

    public static boolean openPath(Path dir) {
        try {
            Files.createDirectories(dir);
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String path = dir.toAbsolutePath().toString();
            ProcessBuilder pb;
            if (os.contains("win")) {
                pb = new ProcessBuilder("explorer.exe", path);
            } else if (os.contains("mac")) {
                pb = new ProcessBuilder("open", path);
            } else {
                pb = new ProcessBuilder("xdg-open", path);
            }
            pb.start();
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("Ordner konnte nicht geoeffnet werden: {} ({})", dir, e.toString());
            return false;
        }
    }

    /** Pixeldaten (max. 64 Pixel Kantenlaenge) fuer die 3D-Vorschau, oder null bei Fehlern. */
    public static PixelImage pixels(Style s) {
        if (!s.custom()) return null;
        Path p = s.source();
        long mtime;
        long size;
        try {
            mtime = Files.getLastModifiedTime(p).toMillis();
            size = Files.size(p);
        } catch (IOException e) {
            return null;
        }
        CachedPix c = PIX.get(p);
        if (c != null && c.mtime() == mtime && c.size() == size) {
            return c.img();
        }
        PixelImage img = readPixels(p);
        PIX.put(p, new CachedPix(mtime, size, img));
        return img;
    }

    private static PixelImage readPixels(Path p) {
        try {
            BufferedImage b = ImageIO.read(p.toFile());
            if (b == null) return null;
            int w = b.getWidth();
            int h = b.getHeight();
            int f = Math.max(1, (Math.max(w, h) + 63) / 64);
            int nw = Math.max(1, w / f);
            int nh = Math.max(1, h / f);
            int[] a = new int[nw * nh];
            for (int y = 0; y < nh; y++) {
                for (int x = 0; x < nw; x++) {
                    a[y * nw + x] = b.getRGB(Math.min(w - 1, x * f + f / 2), Math.min(h - 1, y * f + f / 2));
                }
            }
            return new PixelImage(nw, nh, a);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public static void scan() {
        List<Style> found = new ArrayList<>();
        Set<Path> seen = new HashSet<>();
        for (Category c : Category.values()) {
            Path dir = root().resolve(c.id);
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                LOG.warn("Ordner nicht anlegbar: {}", dir, e);
                continue;
            }
            List<Path> files;
            try (Stream<Path> st = Files.list(dir)) {
                files = st.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                        .sorted()
                        .toList();
            } catch (IOException e) {
                LOG.warn("Ordner nicht lesbar: {}", dir, e);
                continue;
            }
            for (Path p : files) {
                seen.add(p);
                Style s = loadCached(c, p);
                if (s != null) found.add(s);
            }
        }
        CACHE.keySet().retainAll(seen);
        StyleRegistry.replaceCustom(found);
    }

    private static Style loadCached(Category c, Path p) {
        long mtime;
        long size;
        try {
            mtime = Files.getLastModifiedTime(p).toMillis();
            size = Files.size(p);
        } catch (IOException e) {
            return null;
        }
        Cached cached = CACHE.get(p);
        if (cached != null && cached.mtime() == mtime && cached.size() == size) {
            return cached.style();
        }
        Style s = load(c, p, size);
        CACHE.put(p, new Cached(mtime, size, s)); // auch Fehlschlaege merken, damit das Log nicht flutet
        return s;
    }

    private static Style load(Category c, Path p, long size) {
        String file = p.getFileName().toString();
        if (size <= 0 || size > MAX_BYTES) {
            LOG.warn("Custom-Style {} uebersprungen: Datei leer oder groesser als 4 MB", file);
            return null;
        }
        try {
            BufferedImage img = ImageIO.read(p.toFile());
            if (img == null) {
                LOG.warn("Custom-Style {} uebersprungen: keine gueltige PNG-Datei", file);
                return null;
            }
            int w = img.getWidth();
            int h = img.getHeight();
            if (w < 1 || h < 1 || w > MAX_DIM || h > MAX_DIM) {
                LOG.warn("Custom-Style {} uebersprungen: Bildgroesse {}x{} nicht erlaubt (max {})", file, w, h, MAX_DIM);
                return null;
            }
            int[] preview = new int[Style.PW * Style.PH];
            long sr = 0, sg = 0, sb = 0;
            for (int j = 0; j < Style.PH; j++) {
                int y0 = j * h / Style.PH;
                int y1 = Math.max(y0 + 1, (j + 1) * h / Style.PH);
                for (int i = 0; i < Style.PW; i++) {
                    int x0 = i * w / Style.PW;
                    int x1 = Math.max(x0 + 1, (i + 1) * w / Style.PW);
                    double a = 0, r = 0, g = 0, b = 0;
                    int n = 0;
                    for (int y = y0; y < y1; y++) {
                        for (int x = x0; x < x1; x++) {
                            int argb = img.getRGB(x, y);
                            int al = argb >>> 24;
                            a += al;
                            r += ((argb >> 16) & 0xFF) * al;
                            g += ((argb >> 8) & 0xFF) * al;
                            b += (argb & 0xFF) * al;
                            n++;
                        }
                    }
                    double cover = n == 0 ? 0 : a / n / 255.0;
                    double cr = a > 0 ? r / a : 0, cg = a > 0 ? g / a : 0, cb = a > 0 ? b / a : 0;
                    int outR = (int) Math.round(cr * cover + ((BG >> 16) & 0xFF) * (1 - cover));
                    int outG = (int) Math.round(cg * cover + ((BG >> 8) & 0xFF) * (1 - cover));
                    int outB = (int) Math.round(cb * cover + (BG & 0xFF) * (1 - cover));
                    preview[j * Style.PW + i] = 0xFF000000 | (outR << 16) | (outG << 8) | outB;
                    sr += outR;
                    sg += outG;
                    sb += outB;
                }
            }
            int cells = Style.PW * Style.PH;
            int avg = ((int) (sr / cells) << 16) | ((int) (sg / cells) << 8) | (int) (sb / cells);
            String base = file.substring(0, file.length() - 4);
            String name = base.replace('_', ' ').trim();
            if (name.length() > 32) name = name.substring(0, 32);
            if (name.isEmpty()) name = "Custom";
            return new Style("custom/" + c.id + "/" + base, c, name, avg, false, preview, p);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Custom-Style {} uebersprungen: {}", file, e.toString());
            return null;
        }
    }
}
