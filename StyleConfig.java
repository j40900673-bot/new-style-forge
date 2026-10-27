package dev.styleforge.config;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Persistierte Daten (JSON). Felder duerfen nach dem Laden nie null sein, siehe ConfigIO. */
public final class StyleConfig {
    /** Kategorie-ID -> aktive Style-ID. Fehlender Eintrag = Vanilla. */
    public Map<String, String> active = new LinkedHashMap<>();
    public Set<String> favorites = new LinkedHashSet<>();
    public Map<String, Boolean> extras = new LinkedHashMap<>();
    /** Kategorie-ID -> Groesse des Items in der Hand (1.0 = Standard, fehlt = 1.0). */
    public Map<String, Double> scales = new LinkedHashMap<>();
    /** Kategorie-ID -> false, wenn die Partikel dieser Kategorie ausgeschaltet sind (fehlt = an). */
    public Map<String, Boolean> particles = new LinkedHashMap<>();
    /** Lautstaerke der Explosionsgeraeusche in Prozent (0-100, Standard 100). */
    public int explosionVolume = 100;
}
