package dev.styleforge.style;

import java.util.*;

/** Zentrale, unveraenderliche Liste aller eingebauten Styles. */
public final class StyleRegistry {
    private static final Map<Category, List<Style>> BY_CATEGORY = new EnumMap<>(Category.class);
    private static final Map<String, Style> BY_ID = new LinkedHashMap<>();

    static {
        // Nur "Vanilla" ist eingebaut. Alle anderen Styles kommen aus eigenen PNGs (siehe CustomStyles).
        for (Category c : Category.values()) {
            add(c, "vanilla", "Vanilla", 0x2A2A34, true);
        }
    }

    private StyleRegistry() {}

    private static void add(Category c, String key, String name, int color) {
        add(c, key, name, color, false);
    }

    private static void add(Category c, String key, String name, int color, boolean vanilla) {
        Style s = new Style(c.id + "/" + key, c, name, color, vanilla);
        BY_CATEGORY.computeIfAbsent(c, k -> new ArrayList<>()).add(s);
        BY_ID.put(s.id(), s);
    }

    private static final Set<String> CUSTOM_IDS = new HashSet<>();

    /** Ersetzt alle Custom-Styles durch die uebergebene Liste (eingebaute Styles bleiben). */
    public static void replaceCustom(List<Style> styles) {
        for (String id : CUSTOM_IDS) {
            Style old = BY_ID.remove(id);
            if (old != null) {
                BY_CATEGORY.get(old.category()).remove(old);
            }
        }
        CUSTOM_IDS.clear();
        for (Style s : styles) {
            if (BY_ID.containsKey(s.id())) continue;
            BY_CATEGORY.get(s.category()).add(s);
            BY_ID.put(s.id(), s);
            CUSTOM_IDS.add(s.id());
        }
    }

    public static List<Style> all(Category c) {
        return Collections.unmodifiableList(BY_CATEGORY.getOrDefault(c, List.of()));
    }

    public static Optional<Style> byId(String id) {
        return Optional.ofNullable(id == null ? null : BY_ID.get(id));
    }

    public static Style vanilla(Category c) {
        return BY_CATEGORY.get(c).get(0);
    }
}
