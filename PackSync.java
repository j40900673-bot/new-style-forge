package dev.styleforge.style;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ResourcePackManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bringt Einstellungen ins Spiel ueber ein normales Ressourcenpaket
 * ({@code resourcepacks/StyleForge_Active}), das die Mod selbst pflegt und aktiviert.
 *
 * Texturen: Crystal (platziert + Hand), Obsidian, Tools (alle Schwerter), Totems, Pearls, Shield, Golden Apples, Cobwebs.
 * Groesse in der Hand: Crystal, Obsidian, Anchor, Tools, Totems, Pearls (ueber eigene Item-Modelle).
 * Partikel aus: Crystal/Anchor (Explosion), Tools, Totems, Pearls (ueber leere Partikel-Texturen).
 */
public final class PackSync {
    private static final Logger LOG = LoggerFactory.getLogger("styleforge");

    public static final String PACK_NAME = "StyleForge_Active";
    private static final String MCMETA =
            "{\"pack\":{\"pack_format\":75,\"min_format\":75,\"max_format\":999,"
                    + "\"description\":\"StyleForge - wird automatisch verwaltet\"}}";

    /** Schwert-Sorten, jede kann einen eigenen Style bekommen. */
    public static final String[] SWORD_IDS = {"wooden", "stone", "iron", "golden", "diamond", "netherite", "copper"};

    public static String swordLabel(String id) {
        return switch (id) {
            case "wooden" -> "Wood";
            case "golden" -> "Gold";
            default -> Character.toUpperCase(id.charAt(0)) + id.substring(1);
        };
    }

    private record Slot(String id, List<String> targets) {}

    private static final String[] APPLE_IDS = {"golden_apple", "enchanted_golden_apple"};

    /** Unterbereiche, die einen eigenen Style bekommen koennen (leer = die Kategorie hat nur einen). */
    public static List<String> slotIds(Category c) {
        return switch (c) {
            case TOOLS -> Arrays.asList(SWORD_IDS);
            case GAPPLES -> Arrays.asList(APPLE_IDS);
            case CRYSTAL -> List.of("item"); // zusaetzlich zum platzierten Kristall (Kategorie selbst)
            default -> List.of();
        };
    }

    /**
     * Gilt der Style der ganzen Kategorie auch fuer die Unterbereiche? Beim Kristall nicht, weil der
     * platzierte Kristall (64x32) und das Item in der Hand (16x16) verschiedene Texturen brauchen.
     */
    public static boolean inheritsAll(Category c) {
        return c != Category.CRYSTAL;
    }

    /** Anzeigename eines Unterbereichs; id = null steht fuer "alle". */
    public static String slotLabel(Category c, String id) {
        if (c == Category.CRYSTAL) {
            return id == null ? "Crystal" : "Hand item";
        }
        if (c == Category.GAPPLES) {
            if (id == null) return "All apples";
            return id.startsWith("enchanted") ? "Enchanted" : "Golden";
        }
        return id == null ? "All swords" : swordLabel(id);
    }
    // Leder bleibt unberuehrt (hat eine eigene Faerbe-Ebene)
    // Nur noch zum Aufraeumen: fruehere Versionen kopierten hier Ruestungs-Texturen hinein
    private static final String[] LEGACY_ARMOR_MATERIALS = {"chainmail", "iron", "gold", "diamond", "netherite", "copper"};
    private static final String ASSETS = "assets/minecraft/";
    private static final String TEX = ASSETS + "textures/";
    private static final String BLANK_TEXTURE = TEX + "particle/styleforge_blank.png";

    private enum Base { GENERATED, HANDHELD, BLOCK }

    private record Held(String item, String model, Base base) {}

    private static final AtomicBoolean RELOAD_PENDING = new AtomicBoolean(false);
    private static final AtomicBoolean CHANGED_SINCE_RELOAD = new AtomicBoolean(false);
    private static boolean pendingFlush = false;
    private static String lastMessage = "";

    private PackSync() {}

    // ---- Faehigkeiten je Kategorie ----------------------------------------------------------

    /** Kategorie, deren eigene PNGs im Spiel als Textur angewendet werden. */
    public static boolean supports(Category c) {
        return !mainTargets(c).isEmpty();
    }

    public static boolean hasHeldItem(Category c) {
        return !heldItems(c).isEmpty();
    }

    public static boolean hasParticles(Category c) {
        return !particleNames(c).isEmpty();
    }

    /** Kurze Rueckmeldung der letzten Synchronisierung (leer = alles ok). */
    public static String lastMessage() {
        return lastMessage;
    }

    public static Path packDir() {
        return FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(PACK_NAME);
    }

    public static void init() {
        StyleManager.addListener(s -> requestApply(sync(s.category())));
        cleanupLegacy();
        // Beim Start nur Dateien abgleichen, das Spiel laedt die Ressourcen danach sowieso.
        for (Category c : Category.values()) {
            sync(c);
        }
    }

    /** Einstellung (Groesse, Partikel) geaendert: Dateien sofort, Neuladen erst bei {@link #flush()}. */
    public static void settingChanged(Category c) {
        if (sync(c)) {
            pendingFlush = true;
        }
    }

    /** Globale Einstellung (Explosionslautstaerke) geaendert: Dateien sofort, Neuladen bei {@link #flush()}. */
    public static void globalChanged() {
        if (sync(Category.INVENTORY)) { // INVENTORY hat selbst keine Dateien, es laufen nur die globalen Abgleiche
            pendingFlush = true;
        }
    }

    public static void flush() {
        if (pendingFlush) {
            pendingFlush = false;
            requestApply(true);
        }
    }

    // ---- Ziele je Kategorie ---------------------------------------------------------------

    /** Texturdateien, die aus der Haupt-PNG gespeist werden. */
    private static List<String> mainTargets(Category c) {
        List<String> out = new ArrayList<>();
        switch (c) {
            case CRYSTAL -> out.add(TEX + "entity/end_crystal/end_crystal.png");
            case OBSIDIAN -> out.add(TEX + "block/obsidian.png");
            case TOOLS -> {
                for (String m : SWORD_IDS) out.add(TEX + "item/" + m + "_sword.png");
            }
            case TOTEMS -> out.add(TEX + "item/totem_of_undying.png");
            case PEARLS -> out.add(TEX + "item/ender_pearl.png");
            case COBWEBS -> out.add(TEX + "block/cobweb.png");
            case GAPPLES -> {
                for (String a : APPLE_IDS) out.add(TEX + "item/" + a + ".png");
            }
            case SHIELD -> {
                // Der genaue Pfad der Schildtextur ist je Version verschieden; unbekannte Pfade schaden nicht.
                out.add(TEX + "entity/shield_base_nopattern.png");
                out.add(TEX + "entity/shield/shield_base_nopattern.png");
                out.add(TEX + "entity/shield/base_nopattern.png");
            }
            default -> { }
        }
        return out;
    }

    /** Unterbereiche einer Kategorie: bei Tools eine Sorte je Schwert, sonst ein einziger (id = null). */
    private static List<Slot> slots(Category c) {
        List<Slot> out = new ArrayList<>();
        if (c == Category.CRYSTAL) {
            out.add(new Slot(null, mainTargets(c)));
            out.add(new Slot("item", List.of(TEX + "item/end_crystal.png")));
            return out;
        }
        List<String> ids = slotIds(c);
        if (!ids.isEmpty()) {
            for (String id : ids) {
                String file = c == Category.TOOLS ? id + "_sword" : id;
                out.add(new Slot(id, List.of(TEX + "item/" + file + ".png")));
            }
        } else {
            List<String> t = mainTargets(c);
            if (!t.isEmpty()) out.add(new Slot(null, t));
        }
        return out;
    }

    private static int[] expectedSize(Category c, String slot) {
        if (c == Category.CRYSTAL && "item".equals(slot)) {
            return new int[]{16, 16};
        }
        return expectedSize(c);
    }

    private static int[] expectedSize(Category c) {
        return switch (c) {
            case CRYSTAL -> new int[]{64, 32};
            case SHIELD -> new int[]{64, 64};
            default -> new int[]{16, 16};
        };
    }

    private static List<Held> heldItems(Category c) {
        List<Held> out = new ArrayList<>();
        switch (c) {
            case CRYSTAL -> out.add(new Held("end_crystal", "minecraft:item/end_crystal", Base.GENERATED));
            case OBSIDIAN -> out.add(new Held("obsidian", "minecraft:block/obsidian", Base.BLOCK));
            case ANCHOR -> out.add(new Held("respawn_anchor", "minecraft:block/respawn_anchor_0", Base.BLOCK));
            case TOOLS -> {
                for (String m : SWORD_IDS) {
                    out.add(new Held(m + "_sword", "minecraft:item/" + m + "_sword", Base.HANDHELD));
                }
            }
            case TOTEMS -> out.add(new Held("totem_of_undying", "minecraft:item/totem_of_undying", Base.GENERATED));
            case PEARLS -> out.add(new Held("ender_pearl", "minecraft:item/ender_pearl", Base.GENERATED));
            case COBWEBS -> out.add(new Held("cobweb", "minecraft:item/cobweb", Base.GENERATED));
            case GAPPLES -> {
                for (String a : APPLE_IDS) out.add(new Held(a, "minecraft:item/" + a, Base.GENERATED));
            }
            default -> { }
        }
        return out;
    }

    private static List<String> particleNames(Category c) {
        return switch (c) {
            case CRYSTAL, ANCHOR -> List.of("explosion");
            case TOOLS -> List.of("sweep_attack", "crit", "enchanted_hit");
            case TOTEMS -> List.of("totem_of_undying");
            case PEARLS -> List.of("portal"); // betrifft auch Netherportale
            default -> List.of();
        };
    }

    // ---- Abgleich -------------------------------------------------------------------------

    /** Gleicht das Paket fuer eine Kategorie ab. Gibt true zurueck, wenn sich Dateien geaendert haben. */
    private static boolean sync(Category c) {
        lastMessage = "";
        boolean changed = false;
        try {
            Path pack = packDir();
            Path mcmeta = pack.resolve("pack.mcmeta");
            if (!Files.exists(mcmeta)) {
                Files.createDirectories(pack);
                Files.writeString(mcmeta, MCMETA, StandardCharsets.UTF_8);
            }
            changed |= syncTexture(pack, c);
            changed |= syncScale(pack, c);
            changed |= syncParticles(pack);
            changed |= syncSounds(pack);
        } catch (IOException | RuntimeException e) {
            LOG.warn("Ressourcenpaket konnte nicht aktualisiert werden: {}", e.toString());
            lastMessage = "Could not update pack, see log.";
            return false;
        }
        return changed;
    }

    private static boolean syncTexture(Path pack, Category c) throws IOException {
        boolean changed = false;
        for (Slot slot : slots(c)) {
            Style s = StyleManager.getEffective(c, slot.id());
            if (s.custom()) {
                Path src = s.source();
                if (!checkImage(src, expectedSize(c, slot.id()))) {
                    continue;
                }
                for (String t : slot.targets()) {
                    changed |= copyIfDifferent(src, pack.resolve(t));
                }
            } else {
                for (String t : slot.targets()) changed |= Files.deleteIfExists(pack.resolve(t));
            }
        }
        return changed;
    }

    /** Entfernt Dateien, die frueher fuer inzwischen gestrichene Kategorien (Armor) angelegt wurden. */
    private static void cleanupLegacy() {
        try {
            Path pack = packDir();
            for (String m : LEGACY_ARMOR_MATERIALS) {
                Files.deleteIfExists(pack.resolve(TEX + "entity/equipment/humanoid/" + m + ".png"));
                Files.deleteIfExists(pack.resolve(TEX + "entity/equipment/humanoid_leggings/" + m + ".png"));
            }
        } catch (IOException | RuntimeException e) {
            LOG.warn("Aufraeumen fehlgeschlagen: {}", e.toString());
        }
    }

    /** Groesse in der Hand: eigenes Item-Modell mit angepasster "display"-Skalierung. Bei 1.0x bleibt alles vanilla. */
    private static boolean syncScale(Path pack, Category c) throws IOException {
        List<Held> items = heldItems(c);
        if (items.isEmpty()) return false;
        double scale = StyleManager.getScale(c);
        boolean changed = false;
        for (Held h : items) {
            Path def = pack.resolve(ASSETS + "items/" + h.item() + ".json");
            Path model = pack.resolve("assets/styleforge/models/item/scaled/" + h.item() + ".json");
            if (Math.abs(scale - 1.0) < 1e-6) {
                changed |= Files.deleteIfExists(def);
                changed |= Files.deleteIfExists(model);
            } else {
                changed |= writeIfDifferent(def,
                        "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"styleforge:item/scaled/"
                                + h.item() + "\"}}");
                changed |= writeIfDifferent(model, modelJson(h, scale));
            }
        }
        return changed;
    }

    /** Schaltet Partikel aus, indem ihre Textur durch ein transparentes Pixel ersetzt wird. */
    private static boolean syncParticles(Path pack) throws IOException {
        Set<String> managed = new LinkedHashSet<>();
        Set<String> disabled = new LinkedHashSet<>();
        for (Category cat : Category.values()) {
            for (String n : particleNames(cat)) {
                managed.add(n);
                if (!StyleManager.isParticles(cat)) disabled.add(n);
            }
        }
        boolean changed = false;
        for (String n : managed) {
            Path f = pack.resolve(ASSETS + "particles/" + n + ".json");
            if (disabled.contains(n)) {
                changed |= writeIfDifferent(f, "{\"textures\":[\"minecraft:styleforge_blank\"]}");
            } else {
                changed |= Files.deleteIfExists(f);
            }
        }
        if (!disabled.isEmpty()) {
            Path blank = pack.resolve(BLANK_TEXTURE);
            if (!Files.exists(blank)) {
                Files.createDirectories(blank.getParent());
                ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", blank.toFile());
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Explosionslautstaerke: eigene sounds.json, die das Explosionsgeraeusch mit kleinerer Lautstaerke ersetzt.
     * Bei 100% wird die Datei geloescht, dann bleibt alles vanilla.
     */
    private static boolean syncSounds(Path pack) throws IOException {
        Path f = pack.resolve(ASSETS + "sounds.json");
        int v = StyleManager.getExplosionVolume();
        if (v >= 100) {
            return Files.deleteIfExists(f);
        }
        String sounds;
        if (v <= 0) {
            sounds = "[]";
        } else {
            String vol = String.format(Locale.ROOT, "%.2f", v / 100.0);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 1; i <= 4; i++) {
                if (i > 1) sb.append(',');
                sb.append("{\"name\":\"random/explode").append(i).append("\",\"volume\":").append(vol).append('}');
            }
            sounds = sb.append(']').toString();
        }
        return writeIfDifferent(f, "{\"entity.generic.explode\":{\"replace\":true,"
                + "\"subtitle\":\"subtitles.entity.generic.explode\",\"sounds\":" + sounds + "}}");
    }

    // ---- Item-Modelle ---------------------------------------------------------------------

    // Vanilla-Werte der Eltern-Modelle: {rotX, rotY, rotZ, transX, transY, transZ, scale}
    private static double[] tpRight(Base b) {
        return switch (b) {
            case GENERATED -> new double[]{0, 0, 0, 0, 3, 1, 0.55};
            case HANDHELD -> new double[]{0, -90, 55, 0, 4, 0.5, 0.85};
            case BLOCK -> new double[]{75, 45, 0, 0, 2.5, 0, 0.375};
        };
    }

    private static double[] tpLeft(Base b) {
        return switch (b) {
            case GENERATED -> new double[]{0, 0, 0, 0, 3, 1, 0.55};
            case HANDHELD -> new double[]{0, 90, -55, 0, 4, 0.5, 0.85};
            case BLOCK -> new double[]{75, 45, 0, 0, 2.5, 0, 0.375};
        };
    }

    private static double[] fpRight(Base b) {
        return switch (b) {
            case GENERATED, HANDHELD -> new double[]{0, -90, 25, 1.13, 3.2, 1.13, 0.68};
            case BLOCK -> new double[]{0, 45, 0, 0, 0, 0, 0.4};
        };
    }

    private static double[] fpLeft(Base b) {
        return switch (b) {
            case GENERATED, HANDHELD -> new double[]{0, 90, -25, 1.13, 3.2, 1.13, 0.68};
            case BLOCK -> new double[]{0, 225, 0, 0, 0, 0, 0.4};
        };
    }

    private static String modelJson(Held h, double s) {
        return "{\"parent\":\"" + h.model() + "\",\"display\":{"
                + transform("thirdperson_righthand", tpRight(h.base()), s) + ","
                + transform("thirdperson_lefthand", tpLeft(h.base()), s) + ","
                + transform("firstperson_righthand", fpRight(h.base()), s) + ","
                + transform("firstperson_lefthand", fpLeft(h.base()), s)
                + "}}";
    }

    private static String transform(String key, double[] v, double s) {
        double sc = v[6] * s;
        return String.format(Locale.ROOT,
                "\"%s\":{\"rotation\":[%s,%s,%s],\"translation\":[%s,%s,%s],\"scale\":[%s,%s,%s]}",
                key, n(v[0]), n(v[1]), n(v[2]), n(v[3]), n(v[4]), n(v[5]), n(sc), n(sc), n(sc));
    }

    private static String n(double d) {
        return String.format(Locale.ROOT, "%.4f", d);
    }

    // ---- Dateihilfen ----------------------------------------------------------------------

    /** Prueft Lesbarkeit und Groesse. Warnung bei falscher Groesse, false nur wenn die Datei unbrauchbar ist. */
    private static boolean checkImage(Path png, int[] size) {
        if (!Files.exists(png)) return false;
        try {
            BufferedImage img = ImageIO.read(png.toFile());
            if (img == null) {
                lastMessage = "PNG not readable.";
                return false;
            }
            if (img.getWidth() != size[0] || img.getHeight() != size[1]) {
                lastMessage = "PNG should be " + size[0] + "x" + size[1]
                        + " (is " + img.getWidth() + "x" + img.getHeight() + ").";
            }
            return true;
        } catch (IOException | RuntimeException e) {
            lastMessage = "PNG not readable.";
            return false;
        }
    }

    private static boolean copyIfDifferent(Path src, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        if (!Files.exists(target) || Files.mismatch(src, target) != -1L) {
            Files.copy(src, target, StandardCopyOption.REPLACE_EXISTING);
            LOG.info("StyleForge: {} -> {}", src.getFileName(), target);
            return true;
        }
        return false;
    }

    private static boolean writeIfDifferent(Path target, String content) throws IOException {
        if (Files.exists(target) && content.equals(Files.readString(target, StandardCharsets.UTF_8))) {
            return false;
        }
        Files.createDirectories(target.getParent());
        Files.writeString(target, content, StandardCharsets.UTF_8);
        return true;
    }

    // ---- Anwenden -------------------------------------------------------------------------

    /**
     * Aktiviert das Paket automatisch (falls noetig) und laedt die Ressourcen neu.
     * Mehrere Aufrufe kurz hintereinander (z.B. Reset) werden zu einem einzigen Lauf zusammengefasst.
     */
    private static void requestApply(boolean filesChanged) {
        if (filesChanged) {
            CHANGED_SINCE_RELOAD.set(true);
        }
        if (RELOAD_PENDING.compareAndSet(false, true)) {
            MinecraftClient mc = MinecraftClient.getInstance();
            mc.execute(() -> {
                RELOAD_PENDING.set(false);
                boolean changed = CHANGED_SINCE_RELOAD.getAndSet(false);
                try {
                    ResourcePackManager manager = mc.getResourcePackManager();
                    manager.scanPacks();
                    // enable() liefert true, wenn das Paket neu eingeschaltet wurde; refreshResourcePacks
                    // speichert die Auswahl und laedt dabei selbst neu.
                    if (manager.enable("file/" + PACK_NAME)) {
                        mc.options.refreshResourcePacks(manager);
                        LOG.info("Ressourcenpaket {} wurde automatisch aktiviert", PACK_NAME);
                        return;
                    }
                } catch (RuntimeException e) {
                    LOG.warn("Paket konnte nicht automatisch aktiviert werden: {}", e.toString());
                }
                if (changed) {
                    mc.reloadResources();
                }
            });
        }
    }
}
