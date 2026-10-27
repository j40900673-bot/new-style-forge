package dev.styleforge.style;

import com.google.gson.Gson;
import dev.styleforge.config.StyleConfig;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Texturen mit Freunden teilen: Export packt alle eigenen PNGs (und die Einstellungen) in eine ZIP,
 * Import liest ZIPs aus {@code styleforge/import} streng geprueft ein (nur PNGs in bekannten Ordnern).
 */
public final class ShareIO {
    private static final Logger LOG = LoggerFactory.getLogger("styleforge");
    private static final long MAX_PNG = 4L * 1024 * 1024;
    private static final int MAX_DIM = 2048;
    private static final int MAX_ENTRIES = 1000;
    private static final long MAX_TOTAL = 100L * 1024 * 1024;
    private static final int MAX_SETTINGS = 256 * 1024;
    private static final String SETTINGS_ENTRY = "settings/styleforge.json";
    private static final Pattern ENTRY =
            Pattern.compile("^custom/([a-z]+)/([^/\\\\:*?\"<>|]{1,80}\\.(?i:png))$");

    public record Result(boolean ok, String message) {}

    private ShareIO() {}

    private static Path base() {
        return FabricLoader.getInstance().getGameDir().resolve("styleforge");
    }

    public static Path exportDir() {
        return base().resolve("export");
    }

    public static Path importDir() {
        return base().resolve("import");
    }

    private static Path configFile() {
        return FabricLoader.getInstance().getConfigDir().resolve("styleforge.json");
    }

    // ---- Export ---------------------------------------------------------------------------

    public static Result export() {
        Path out = exportDir().resolve("styleforge-share.zip");
        Path tmp = exportDir().resolve("styleforge-share.zip.tmp");
        int count = 0;
        try {
            Files.createDirectories(exportDir());
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tmp))) {
                for (Category c : Category.values()) {
                    Path dir = CustomStyles.root().resolve(c.id);
                    if (!Files.isDirectory(dir)) continue;
                    List<Path> files;
                    try (Stream<Path> st = Files.list(dir)) {
                        files = st.filter(Files::isRegularFile)
                                .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                                .sorted()
                                .toList();
                    }
                    for (Path p : files) {
                        if (Files.size(p) > MAX_PNG) continue;
                        zos.putNextEntry(new ZipEntry("custom/" + c.id + "/" + p.getFileName()));
                        Files.copy(p, zos);
                        zos.closeEntry();
                        count++;
                    }
                }
                Path cfg = configFile();
                if (Files.exists(cfg)) {
                    zos.putNextEntry(new ZipEntry(SETTINGS_ENTRY));
                    Files.copy(cfg, zos);
                    zos.closeEntry();
                }
            }
            if (count == 0) {
                Files.deleteIfExists(tmp);
                return new Result(false, "Nothing to export - no PNGs yet.");
            }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING);
            return new Result(true, "Saved " + count + " PNGs: styleforge/export/styleforge-share.zip");
        } catch (IOException | RuntimeException e) {
            LOG.warn("Export fehlgeschlagen: {}", e.toString());
            return new Result(false, "Export failed, see log.");
        }
    }

    // ---- Import ---------------------------------------------------------------------------

    public static Result importAll() {
        Path dir = importDir();
        List<Path> zips;
        try {
            Files.createDirectories(dir);
            try (Stream<Path> st = Files.list(dir)) {
                zips = st.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip"))
                        .sorted()
                        .toList();
            }
        } catch (IOException e) {
            LOG.warn("Import-Ordner nicht lesbar: {}", e.toString());
            return new Result(false, "Import folder not readable.");
        }
        if (zips.isEmpty()) {
            return new Result(false, "No ZIP found. Put it into styleforge/import.");
        }

        int imported = 0;
        int skipped = 0;
        boolean settingsLoaded = false;
        boolean settingsKept = false;
        for (Path zip : zips) {
            try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(zip))) {
                ZipEntry e;
                int entries = 0;
                long total = 0;
                while ((e = zis.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    if (++entries > MAX_ENTRIES) break;
                    String name = e.getName();
                    boolean isSettings = name.equals(SETTINGS_ENTRY);
                    byte[] data = readLimited(zis, isSettings ? MAX_SETTINGS : MAX_PNG);
                    if (data == null) {
                        skipped++;
                        continue;
                    }
                    total += data.length;
                    if (total > MAX_TOTAL) break;

                    if (isSettings) {
                        if (Files.exists(configFile())) {
                            settingsKept = true;
                        } else if (writeSettings(data)) {
                            settingsLoaded = true;
                        } else {
                            skipped++;
                        }
                    } else if (importPng(name, data)) {
                        imported++;
                    } else {
                        skipped++;
                    }
                }
            } catch (IOException | RuntimeException ex) {
                LOG.warn("ZIP {} nicht lesbar: {}", zip.getFileName(), ex.toString());
                skipped++;
            }
        }

        if (imported > 0 || settingsLoaded) {
            StyleManager.reloadFromDisk();
        }
        StringBuilder msg = new StringBuilder("Imported " + imported + " PNGs");
        if (skipped > 0) msg.append(", skipped ").append(skipped);
        if (settingsLoaded) msg.append(", settings loaded");
        else if (settingsKept) msg.append(", kept your settings");
        return new Result(imported > 0 || settingsLoaded, msg.append('.').toString());
    }

    private static boolean importPng(String name, byte[] data) {
        Matcher m = ENTRY.matcher(name);
        if (!m.matches()) return false;
        Category cat = null;
        for (Category c : Category.values()) {
            if (c.id.equals(m.group(1))) cat = c;
        }
        String file = m.group(2);
        if (cat == null || file.contains("..")) return false;
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(data));
            if (img == null || img.getWidth() < 1 || img.getHeight() < 1
                    || img.getWidth() > MAX_DIM || img.getHeight() > MAX_DIM) {
                return false;
            }
            Path catDir = CustomStyles.root().resolve(cat.id).normalize();
            Path target = catDir.resolve(file).normalize();
            if (!target.startsWith(catDir)) return false;
            Files.createDirectories(catDir);
            Path tmp = catDir.resolve(file + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException e) {
            LOG.warn("PNG {} nicht importiert: {}", name, e.toString());
            return false;
        }
    }

    private static boolean writeSettings(byte[] data) {
        try {
            String json = new String(data, StandardCharsets.UTF_8);
            StyleConfig cfg = new Gson().fromJson(json, StyleConfig.class);
            if (cfg == null) return false;
            Files.createDirectories(configFile().getParent());
            Files.writeString(configFile(), json, StandardCharsets.UTF_8);
            return true;
        } catch (IOException | RuntimeException e) { // JsonParseException ist eine RuntimeException
            LOG.warn("Einstellungen nicht importiert: {}", e.toString());
            return false;
        }
    }

    /** Liest hoechstens {@code limit} Bytes; null, wenn der Eintrag groesser ist. */
    private static byte[] readLimited(InputStream in, long limit) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        long sum = 0;
        while ((n = in.read(buf)) > 0) {
            sum += n;
            if (sum > limit) {
                while (in.read(buf) > 0) { /* Rest verwerfen */ }
                return null;
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
