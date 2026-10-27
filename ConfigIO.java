package dev.styleforge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;

public final class ConfigIO {
    private static final Logger LOG = LoggerFactory.getLogger("styleforge");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConfigIO() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("styleforge.json");
    }

    public static StyleConfig load() {
        Path p = path();
        if (!Files.exists(p)) {
            return new StyleConfig();
        }
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return normalize(GSON.fromJson(r, StyleConfig.class));
        } catch (IOException | JsonParseException e) {
            LOG.error("Konfiguration unlesbar, sichere Datei und starte mit Standardwerten", e);
            try {
                Files.move(p, p.resolveSibling("styleforge.json.broken"), StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                // Sicherung ist nur ein Bonus
            }
            return new StyleConfig();
        }
    }

    public static void save(StyleConfig cfg) {
        Path p = path();
        Path tmp = p.resolveSibling("styleforge.json.tmp");
        try {
            Files.createDirectories(p.getParent());
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(cfg, w);
            }
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            LOG.error("Konfiguration konnte nicht gespeichert werden", e);
        }
    }

    private static StyleConfig normalize(StyleConfig cfg) {
        if (cfg == null) cfg = new StyleConfig();
        if (cfg.active == null) cfg.active = new LinkedHashMap<>();
        if (cfg.favorites == null) cfg.favorites = new LinkedHashSet<>();
        if (cfg.extras == null) cfg.extras = new LinkedHashMap<>();
        if (cfg.scales == null) cfg.scales = new LinkedHashMap<>();
        if (cfg.particles == null) cfg.particles = new LinkedHashMap<>();
        return cfg;
    }
}
