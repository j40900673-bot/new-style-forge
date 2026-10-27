package dev.styleforge.style;

import java.nio.file.Path;

/**
 * Ein einzelner Style. {@code color} (0xRRGGBB) ist die Vorschaufarbe der Kachel.
 * Eigene (Custom-)Styles haben zusaetzlich eine Pixel-Vorschau und die Quelldatei.
 */
public record Style(String id, Category category, String name, int color, boolean vanilla,
                    int[] preview, Path source) {
    public static final int PW = 32, PH = 20;

    public Style(String id, Category category, String name, int color, boolean vanilla) {
        this(id, category, name, color, vanilla, null, null);
    }

    public int argb() {
        return 0xFF000000 | color;
    }

    public boolean custom() {
        return source != null;
    }

    // Gleichheit nur ueber die ID, damit neu eingelesene Custom-Styles gleich bleiben.
    @Override
    public boolean equals(Object o) {
        return o instanceof Style s && s.id.equals(id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }
}
