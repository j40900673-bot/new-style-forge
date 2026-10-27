package dev.styleforge.style;

import dev.styleforge.config.ConfigIO;
import dev.styleforge.config.StyleConfig;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Einziger Zugriffspunkt auf den Zustand (aktive Styles, Favoriten, Extras).
 * Renderer registrieren sich spaeter ueber {@link #addListener} und bekommen jede Aenderung.
 */
public final class StyleManager {
    public record Extra(String id, String label, boolean defaultValue) {}

    public static final List<Extra> EXTRAS = List.of(
            new Extra("outline", "Outline effect", false),
            new Extra("glint", "Custom enchant glint", false),
            new Extra("reduced_animations", "Reduced UI animations", false));

    private static StyleConfig config = new StyleConfig();
    private static final List<Consumer<Style>> LISTENERS = new CopyOnWriteArrayList<>();

    private StyleManager() {}

    public static void init() {
        config = ConfigIO.load();
        CustomStyles.scan(); // vor dem Bereinigen, sonst gingen aktive Custom-Styles verloren
        sanitize();
    }

    /** Liest Einstellungen und PNG-Ordner neu ein (z.B. nach einem Import) und gleicht das Ressourcenpaket ab. */
    public static void reloadFromDisk() {
        config = ConfigIO.load();
        CustomStyles.scan();
        sanitize();
        for (Category c : Category.values()) {
            Style v = StyleRegistry.vanilla(c);
            LISTENERS.forEach(l -> safeNotify(l, v));
        }
    }

    private static void sanitize() {
        // Unbekannte IDs (z.B. aus aelteren Versionen) entfernen, damit nichts abstuerzt.
        config.active.values().removeIf(id -> StyleRegistry.byId(id).isEmpty());
        config.favorites.removeIf(id -> StyleRegistry.byId(id).isEmpty());
        config.scales.replaceAll((k, v) -> v == null ? 1.0 : Math.max(0.5, Math.min(2.0, v)));
        config.particles.values().removeIf(v -> v == null || v);
        config.explosionVolume = Math.max(0, Math.min(100, config.explosionVolume));
    }

    public static void addListener(Consumer<Style> l) {
        LISTENERS.add(l);
    }

    public static Style getActive(Category c) {
        String id = config.active.get(c.id);
        return StyleRegistry.byId(id).filter(s -> s.category() == c).orElse(StyleRegistry.vanilla(c));
    }

    public static boolean isActive(Style s) {
        return getActive(s.category()).equals(s);
    }

    /**
     * Style fuer einen Unterbereich (z.B. nur das Diamantschwert in Tools). Ohne eigenen Eintrag gilt der
     * Style der ganzen Kategorie.
     */
    public static Style getEffective(Category c, String slot) {
        if (slot != null) {
            String id = config.active.get(c.id + "@" + slot);
            Style own = StyleRegistry.byId(id).filter(x -> x.category() == c).orElse(null);
            if (own != null) return own;
            if (!PackSync.inheritsAll(c)) return StyleRegistry.vanilla(c);
        }
        return getActive(c);
    }

    /** Setzt einen Style nur fuer einen Unterbereich; "Vanilla" wird ausdruecklich gespeichert. */
    public static void activateSlot(Style s, String slot) {
        config.active.put(s.category().id + "@" + slot, s.id());
        ConfigIO.save(config);
        LISTENERS.forEach(l -> safeNotify(l, s));
    }

    /** Ist in der Kategorie irgendetwas ausser Vanilla aktiv (auch in Unterbereichen)? */
    public static boolean hasAnyActive(Category c) {
        if (!getActive(c).vanilla()) return true;
        for (var e : config.active.entrySet()) {
            if (e.getKey().startsWith(c.id + "@")) {
                var st = StyleRegistry.byId(e.getValue());
                if (st.isPresent() && !st.get().vanilla()) return true;
            }
        }
        return false;
    }

    public static void activate(Style s) {
        // Ein Style fuer die ganze Kategorie ersetzt alle Einzel-Einstellungen
        if (PackSync.inheritsAll(s.category())) {
            config.active.keySet().removeIf(k -> k.startsWith(s.category().id + "@"));
        }
        if (s.vanilla()) {
            config.active.remove(s.category().id);
        } else {
            config.active.put(s.category().id, s.id());
        }
        ConfigIO.save(config);
        LISTENERS.forEach(l -> safeNotify(l, s));
    }

    public static void deactivate(Category c) {
        activate(StyleRegistry.vanilla(c));
    }

    public static boolean isFavorite(Style s) {
        return config.favorites.contains(s.id());
    }

    public static void toggleFavorite(Style s) {
        if (!config.favorites.remove(s.id())) {
            config.favorites.add(s.id());
        }
        ConfigIO.save(config);
    }

    public static int favoriteCount() {
        return config.favorites.size();
    }

    /** Aktive Nicht-Vanilla-Styles (fuer die Loadout-Leiste). */
    public static List<Style> loadout() {
        List<Style> out = new ArrayList<>();
        for (Category c : Category.values()) {
            Style s = getActive(c);
            if (!s.vanilla()) out.add(s);
        }
        return out;
    }

    /** Groesse des Items in der Hand: 0.5 bis 2.0, Standard 1.0. */
    public static double getScale(Category c) {
        Double v = config.scales.get(c.id);
        return v == null ? 1.0 : v;
    }

    public static void setScale(Category c, double value) {
        double v = Math.max(0.5, Math.min(2.0, Math.round(value * 10) / 10.0));
        if (Math.abs(v - 1.0) < 1e-6) {
            config.scales.remove(c.id);
        } else {
            config.scales.put(c.id, v);
        }
        ConfigIO.save(config);
    }

    public static boolean isParticles(Category c) {
        return config.particles.getOrDefault(c.id, true);
    }

    public static void toggleParticles(Category c) {
        if (isParticles(c)) {
            config.particles.put(c.id, false);
        } else {
            config.particles.remove(c.id);
        }
        ConfigIO.save(config);
    }

    /** Lautstaerke der Explosionsgeraeusche in Prozent, 0 bis 100 in 10er-Schritten. */
    public static int getExplosionVolume() {
        return config.explosionVolume;
    }

    public static void setExplosionVolume(int percent) {
        config.explosionVolume = Math.max(0, Math.min(100, Math.round(percent / 10f) * 10));
        ConfigIO.save(config);
    }

    public static boolean getExtra(Extra e) {
        return config.extras.getOrDefault(e.id(), e.defaultValue());
    }

    public static void toggleExtra(Extra e) {
        config.extras.put(e.id(), !getExtra(e));
        ConfigIO.save(config);
    }

    public static void resetAll() {
        config = new StyleConfig();
        ConfigIO.save(config);
        for (Category c : Category.values()) {
            Style v = StyleRegistry.vanilla(c);
            LISTENERS.forEach(l -> safeNotify(l, v));
        }
    }

    private static void safeNotify(Consumer<Style> l, Style s) {
        try {
            l.accept(s);
        } catch (RuntimeException e) {
            LoggerFactory.getLogger("styleforge").error("Style-Listener fehlgeschlagen", e);
        }
    }
}
