package dev.styleforge.ui;

import dev.styleforge.style.Category;
import dev.styleforge.style.CustomStyles;
import dev.styleforge.style.PackSync;
import dev.styleforge.style.PixelImage;
import dev.styleforge.style.ShareIO;
import dev.styleforge.style.Style;
import dev.styleforge.style.StyleManager;
import dev.styleforge.style.StyleRegistry;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Styles-Menue im dunklen Violett-Design. Die Eingabe laeuft ueber normale Widgets (ButtonWidget,
 * TextFieldWidget); das Aussehen wird per DrawContext ueber die Widgets gezeichnet. Die 3D-Vorschau
 * besteht nur aus gefuellten Rechtecken (siehe Preview3D).
 */
public class StylesScreen extends Screen {
    private enum Tab { COLLECTIONS, EXTRAS }

    private enum View { NONE, ITEM, BLOCK, SHEET, CRYSTAL }

    /** Ein Button, dessen Aussehen selbst gezeichnet wird. flat = nur Text (Tabs), highlight = aktiver Tab. */
    private record Styled(ButtonWidget btn, int x, int y, int w, int h, String label, boolean flat, boolean highlight) {}

    // Farbsystem
    private static final int C_DIM = 0xE0050409;
    private static final int C_PANEL = 0xFF0F0C18;
    private static final int C_PANEL_TOP = 0xFF1A1436;
    private static final int C_SIDE = 0xFF0A0810;
    private static final int C_LINE = 0xFF2A2342;
    private static final int C_CARD = 0xFF161224;
    private static final int C_CARD_HOVER = 0xFF2A2248;
    private static final int C_CARD_OFF = 0xFF100E19;
    private static final int C_ROW_SEL = 0xFF1F1A3D;
    private static final int C_ROW_HOVER = 0xFF15112B;
    private static final int C_ACCENT = 0xFF9B6BFF;
    private static final int C_TEXT = 0xFFE8E8F2;
    private static final int C_MUTED = 0xFF8C88A8;
    private static final int C_DISABLED = 0xFF5A5575;
    private static final int C_GREEN = 0xFF4ADE80;
    private static final int C_GOLD = 0xFFF5C542;

    // Layout (in GUI-Pixeln)
    private static final int W = 440, H = 252;
    private static final int SIDE_W = 96, GAP = 6, CENTER_W = 220, INSP_W = 110;
    private static final int COLS = 4, ROWS = 3, PER_PAGE = COLS * ROWS;
    private static final int CELL_W = 52, CELL_H = 42, STRIDE_X = 55, STRIDE_Y = 44;
    private static final int VISIBLE_CATS = 12, ROW_H = 17, SIDE_TOP = 25;

    private final Screen parent;
    private Tab tab = Tab.COLLECTIONS;
    private Category category = Category.CRYSTAL;
    private Style selected;
    private String query = "";
    private boolean favoritesOnly;
    private int page;
    private int catScroll;

    private int left, top;
    private TextFieldWidget search;
    private ButtonWidget prevBtn, nextBtn;
    private final List<ButtonWidget> tileButtons = new ArrayList<>();
    private final List<Styled> styled = new ArrayList<>();
    private List<Style> visible = new ArrayList<>(); // Styles auf der aktuellen Seite
    private int filteredCount;
    private String status = "";
    private String notice = "";
    private String shareMsg = "";
    private int toolTarget = 0; // 0 = alle, sonst Index in PackSync.slotIds(category) + 1

    private final Map<Category, ItemStack> icons = new EnumMap<>(Category.class);
    private final Map<String, PixelImage> synthCache = new HashMap<>();

    public StylesScreen(Screen parent) {
        super(Text.literal("StyleForge"));
        this.parent = parent;
        for (Category c : Category.values()) {
            icons.put(c, new ItemStack(iconItem(c)));
        }
        CustomStyles.scan(); // neue PNGs im custom-Ordner sofort uebernehmen
    }

    private static Item iconItem(Category c) {
        return switch (c) {
            case CRYSTAL -> Items.END_CRYSTAL;
            case OBSIDIAN -> Items.OBSIDIAN;
            case ANCHOR -> Items.RESPAWN_ANCHOR;
            case TOOLS -> Items.NETHERITE_SWORD;
            case SHIELD -> Items.SHIELD;
            case GAPPLES -> Items.GOLDEN_APPLE;
            case COBWEBS -> Items.COBWEB;
            case TOTEMS -> Items.TOTEM_OF_UNDYING;
            case PEARLS -> Items.ENDER_PEARL;
            case SKY -> Items.CLOCK;
            case FIRE -> Items.FLINT_AND_STEEL;
            case INVENTORY -> Items.CHEST;
        };
    }

    private static View viewOf(Category c) {
        return switch (c) {
            case OBSIDIAN -> View.BLOCK;
            case TOOLS, TOTEMS, PEARLS, GAPPLES, COBWEBS -> View.ITEM;
            case CRYSTAL -> View.CRYSTAL;
            case SHIELD -> View.SHEET;
            default -> View.NONE;
        };
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }

    @Override
    public void removed() {
        // Einstellungen wie Groesse/Partikel/Lautstaerke werden erst beim Schliessen angewendet (ein Neuladen statt vieler).
        PackSync.flush();
    }

    // ---------------------------------------------------------------- Aufbau

    private ButtonWidget btn(int x, int y, int w, int h, String label, Runnable action) {
        return btn(x, y, w, h, label, false, false, action);
    }

    private ButtonWidget btn(int x, int y, int w, int h, String label, boolean flat, boolean highlight, Runnable action) {
        ButtonWidget b = ButtonWidget.builder(Text.literal(label), pressed -> action.run())
                .dimensions(x, y, w, h).build();
        addDrawableChild(b);
        styled.add(new Styled(b, x, y, w, h, label, flat, highlight));
        return b;
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        tileButtons.clear();
        styled.clear();

        if (selected == null || selected.category() != category) {
            selected = StyleManager.getActive(category);
        }

        // Tabs
        btn(left + SIDE_W + GAP, top + 4, 76, 16, "COLLECTIONS", true, tab == Tab.COLLECTIONS,
                () -> { tab = Tab.COLLECTIONS; clearAndInit(); });
        btn(left + SIDE_W + GAP + 80, top + 4, 50, 16, "EXTRAS", true, tab == Tab.EXTRAS,
                () -> { tab = Tab.EXTRAS; clearAndInit(); });

        // Fusszeile
        btn(left + 4, top + H - 20, 56, 16, "Reset", () -> {
            StyleManager.resetAll();
            selected = null;
            clearAndInit();
        });
        btn(left + 64, top + H - 20, 56, 16, "Rescan", () -> {
            CustomStyles.scan();
            selected = null;
            clearAndInit();
        });
        btn(left + 124, top + H - 20, 76, 16, "Open folder", () -> {
            Category c = tab == Tab.COLLECTIONS ? category : null;
            status = CustomStyles.openFolder(c)
                    ? "Folder opened - add PNGs, then Rescan"
                    : "Could not open: " + CustomStyles.root();
        });
        btn(left + W - 60, top + H - 20, 56, 16, "Close", this::close);

        if (tab == Tab.COLLECTIONS) {
            initCollections();
        } else {
            initExtras();
        }
    }

    private void initCollections() {
        Category[] cats = Category.values();
        int idx = category.ordinal();
        if (idx < catScroll) catScroll = idx;
        if (idx >= catScroll + VISIBLE_CATS) catScroll = idx - VISIBLE_CATS + 1;
        catScroll = Math.max(0, Math.min(catScroll, Math.max(0, cats.length - VISIBLE_CATS)));

        // Kategorie-Zeilen: unsichtbar bedienbar, das Aussehen zeichnet drawSidebar()
        for (int r = 0; r < VISIBLE_CATS && catScroll + r < cats.length; r++) {
            Category c = cats[catScroll + r];
            addDrawableChild(ButtonWidget.builder(Text.literal(c.displayName), b -> {
                category = c;
                toolTarget = 0;
                notice = "";
                selected = null;
                page = 0;
                clearAndInit();
            }).dimensions(left, top + SIDE_TOP + r * ROW_H, SIDE_W, ROW_H - 1).build());
        }

        int cx = left + SIDE_W + GAP;

        search = new TextFieldWidget(textRenderer, cx, top + 30, 110, 16, Text.literal("Search"));
        search.setPlaceholder(Text.literal("Search..."));
        search.setText(query);
        search.setChangedListener(s -> {
            query = s;
            page = 0;
            rebuildTiles();
        });
        addDrawableChild(search);

        btn(cx + 114, top + 30, 106, 16, favoritesOnly ? "* Favorites: ON" : "* Favorites: all",
                () -> { favoritesOnly = !favoritesOnly; page = 0; clearAndInit(); });

        int cy = top + 50;
        if (PackSync.hasHeldItem(category)) {
            btn(cx + 34, cy, 16, 16, "-", () -> changeScale(-0.1));
            btn(cx + 90, cy, 16, 16, "+", () -> changeScale(0.1));
        }
        if (PackSync.hasParticles(category)) {
            btn(cx + 112, cy, 108, 16, "Particles: " + (StyleManager.isParticles(category) ? "ON" : "OFF"), () -> {
                StyleManager.toggleParticles(category);
                PackSync.settingChanged(category);
                notice = "Applies when you close the menu.";
                clearAndInit();
            });
        }

        prevBtn = btn(cx, top + 206, 40, 16, "<", () -> { page--; rebuildTiles(); });
        nextBtn = btn(cx + CENTER_W - 40, top + 206, 40, 16, ">", () -> { page++; rebuildTiles(); });

        rebuildTiles();
        initInspector();
    }

    private List<Style> filtered() {
        String q = query.trim().toLowerCase(Locale.ROOT);
        List<Style> out = new ArrayList<>();
        for (Style s : StyleRegistry.all(category)) {
            if (favoritesOnly && !StyleManager.isFavorite(s)) continue;
            if (!q.isEmpty() && !s.name().toLowerCase(Locale.ROOT).contains(q)) continue;
            out.add(s);
        }
        return out;
    }

    private int pageCount() {
        return Math.max(1, (filteredCount + PER_PAGE - 1) / PER_PAGE);
    }

    private void rebuildTiles() {
        for (ButtonWidget b : tileButtons) {
            remove(b);
        }
        tileButtons.clear();

        List<Style> all = filtered();
        filteredCount = all.size();
        page = Math.max(0, Math.min(page, pageCount() - 1));
        int from = page * PER_PAGE;
        visible = new ArrayList<>(all.subList(from, Math.min(all.size(), from + PER_PAGE)));

        int gx = left + SIDE_W + GAP;
        int gy = top + 70;
        for (int i = 0; i < visible.size(); i++) {
            Style s = visible.get(i);
            int x = gx + (i % COLS) * STRIDE_X;
            int y = gy + (i / COLS) * STRIDE_Y;
            ButtonWidget b = ButtonWidget.builder(Text.literal(s.name()), btn -> {
                notice = "";
                selected = s;
                clearAndInit();
            }).dimensions(x, y, CELL_W, CELL_H).build();
            tileButtons.add(b);
            addDrawableChild(b);
        }

        if (prevBtn != null) prevBtn.active = page > 0;
        if (nextBtn != null) nextBtn.active = page < pageCount() - 1;
    }

    private void initInspector() {
        int ix = left + SIDE_W + GAP + CENTER_W + GAP;
        Style s = selected;
        if (s == null) return;

        boolean active = isActiveFor(s);
        String slot = slotId();

        List<String> slotIds = PackSync.slotIds(s.category());
        if (!slotIds.isEmpty()) {
            String name = PackSync.slotLabel(s.category(), slot);
            btn(ix + 4, top + 134, INSP_W - 8, 14, "For: " + name, () -> {
                toolTarget = (toolTarget + 1) % (slotIds.size() + 1);
                notice = "";
                clearAndInit();
            });
        }

        ButtonWidget fav = btn(ix + 4, top + 150, INSP_W - 8, 18,
                StyleManager.isFavorite(s) ? "Unfavorite" : "Favorite",
                () -> { StyleManager.toggleFavorite(s); clearAndInit(); });
        fav.active = !s.vanilla();

        String label;
        if (active) label = s.vanilla() ? "Default active" : "Deactivate";
        else label = s.vanilla() ? "Use default" : "Activate";
        ButtonWidget act = btn(ix + 4, top + 172, INSP_W - 8, 18, label, () -> {
            if (slot != null) {
                StyleManager.activateSlot(active ? StyleRegistry.vanilla(s.category()) : s, slot);
            } else if (active) {
                StyleManager.deactivate(s.category());
            } else {
                StyleManager.activate(s);
            }
            String m = PackSync.supports(s.category()) ? PackSync.lastMessage() : "";
            if (!m.isEmpty()) notice = m;
            else if (!active && s.custom()) {
                notice = PackSync.supports(s.category())
                        ? "Applying... pack " + PackSync.PACK_NAME + " is enabled automatically."
                        : "Preview only - not applied in game yet.";
            } else notice = "";
            clearAndInit();
        });
        act.active = !(active && s.vanilla());
    }

    private void initExtras() {
        int cx = left + SIDE_W + GAP;
        btn(cx + 130, top + 34, 16, 16, "-", () -> changeExplosion(-10));
        btn(cx + 190, top + 34, 16, 16, "+", () -> changeExplosion(10));
        for (int i = 0; i < StyleManager.EXTRAS.size(); i++) {
            StyleManager.Extra e = StyleManager.EXTRAS.get(i);
            btn(cx, top + 62 + i * 22, CENTER_W, 18,
                    e.label() + ": " + (StyleManager.getExtra(e) ? "ON" : "OFF"),
                    () -> { StyleManager.toggleExtra(e); clearAndInit(); });
        }
        btn(cx, top + 194, 106, 16, "Export ZIP", this::doExport);
        btn(cx + 114, top + 194, 106, 16, "Import ZIP", this::doImport);
    }

    private void doExport() {
        ShareIO.Result r = ShareIO.export();
        shareMsg = r.message();
        if (r.ok()) CustomStyles.openPath(ShareIO.exportDir());
    }

    private void doImport() {
        ShareIO.Result r = ShareIO.importAll();
        shareMsg = r.message();
        if (!r.ok() && r.message().startsWith("No ZIP")) CustomStyles.openPath(ShareIO.importDir());
        selected = null;
        clearAndInit();
    }

    private void changeExplosion(int delta) {
        StyleManager.setExplosionVolume(StyleManager.getExplosionVolume() + delta);
        PackSync.globalChanged();
        notice = "Applies when you close the menu.";
    }

    private void changeScale(double delta) {
        StyleManager.setScale(category, StyleManager.getScale(category) + delta);
        PackSync.settingChanged(category);
        notice = "Applies when you close the menu.";
    }

    /** Unterbereich, der gerade bearbeitet wird (Tools: Schwertsorte, Golden Apples: Apfelsorte), sonst null. */
    private String slotId() {
        List<String> ids = PackSync.slotIds(category);
        return ids.isEmpty() || toolTarget <= 0 || toolTarget > ids.size() ? null : ids.get(toolTarget - 1);
    }

    private boolean isActiveFor(Style s) {
        String slot = s.category() == category ? slotId() : null;
        return slot == null
                ? StyleManager.isActive(s)
                : StyleManager.getEffective(s.category(), slot).equals(s);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (tab == Tab.COLLECTIONS && mouseX >= left && mouseX < left + SIDE_W) {
            int max = Math.max(0, Category.values().length - VISIBLE_CATS);
            int next = Math.max(0, Math.min(max, catScroll + (verticalAmount < 0 ? 1 : -1)));
            if (next != catScroll) {
                catScroll = next;
                clearAndInit();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    // ------------------------------------------------------------- Rendering

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        // Eigener Hintergrund ohne Blur (verhindert doppeltes Blur-Rendering pro Frame).
        ctx.fill(0, 0, width, height, C_DIM);
        ctx.fill(left - 1, top - 1, left + W + 1, top + H + 1, C_LINE);
        ctx.fill(left, top, left + W, top + H, C_PANEL);
        ctx.fillGradient(left, top + 25, left + W, top + H - 24, C_PANEL_TOP, C_PANEL);
        ctx.fill(left, top, left + W, top + 24, C_SIDE);
        ctx.fill(left, top + 24, left + W, top + 25, C_LINE);
        if (tab == Tab.COLLECTIONS) {
            ctx.fill(left, top + 25, left + SIDE_W, top + H - 24, C_SIDE);
            ctx.fill(left + SIDE_W, top + 25, left + SIDE_W + 1, top + H - 24, C_LINE);
            int ix = left + SIDE_W + GAP + CENTER_W + GAP - 3;
            ctx.fill(ix, top + 26, ix + 1, top + H - 24, C_LINE);
        }
        ctx.fill(left, top + H - 24, left + W, top + H, C_SIDE);
        ctx.fill(left, top + H - 24, left + W, top + H - 23, C_LINE);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);

        drawStyledButtons(ctx, mouseX, mouseY);
        ctx.drawText(textRenderer, "STYLEFORGE", left + 8, top + 8, C_ACCENT, true);
        drawLoadout(ctx);

        if (tab == Tab.COLLECTIONS) {
            drawSidebar(ctx, mouseX, mouseY);
            drawTiles(ctx, mouseX, mouseY);
            drawControls(ctx);
            drawPager(ctx);
            drawInspector(ctx);
        } else {
            drawExtras(ctx);
        }

        String hint = !status.isEmpty() ? status
                : tab == Tab.COLLECTIONS ? "PNGs: custom/" + category.id
                : "Toggles are saved instantly";
        ctx.drawText(textRenderer, textRenderer.trimToWidth(hint, 172), left + 206, top + H - 16, C_MUTED, false);
    }

    private static boolean inside(int mx, int my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** Deckt die Standard-Buttons ab und zeichnet sie im eigenen Design (die Klicks bleiben beim Widget). */
    private void drawStyledButtons(DrawContext ctx, int mx, int my) {
        for (Styled s : styled) {
            boolean on = s.btn().active;
            boolean hover = on && inside(mx, my, s.x(), s.y(), s.w(), s.h());
            int tw = textRenderer.getWidth(s.label());
            int tx = s.x() + (s.w() - tw) / 2;
            int ty = s.y() + (s.h() - 8) / 2;
            if (s.flat()) {
                ctx.fill(s.x(), s.y(), s.x() + s.w(), s.y() + s.h(), C_SIDE);
                int col = s.highlight() ? C_ACCENT : hover ? C_TEXT : C_MUTED;
                ctx.drawText(textRenderer, s.label(), tx, ty, col, false);
                if (s.highlight()) {
                    ctx.fill(s.x() + 2, s.y() + s.h() - 2, s.x() + s.w() - 2, s.y() + s.h(), C_ACCENT);
                }
            } else {
                ctx.fill(s.x(), s.y(), s.x() + s.w(), s.y() + s.h(), hover ? C_ACCENT : C_LINE);
                ctx.fill(s.x() + 1, s.y() + 1, s.x() + s.w() - 1, s.y() + s.h() - 1,
                        !on ? C_CARD_OFF : hover ? C_CARD_HOVER : C_CARD);
                ctx.drawText(textRenderer, s.label(), tx, ty, on ? C_TEXT : C_DISABLED, false);
            }
        }
    }

    private void drawLoadout(DrawContext ctx) {
        List<Style> lo = StyleManager.loadout();
        int slot = 10, gap = 1, n = Category.values().length;
        int x0 = left + W - 6 - n * (slot + gap) + gap;
        ctx.drawText(textRenderer, "LOADOUT", x0 - 46, top + 8, C_MUTED, false);
        for (int i = 0; i < n; i++) {
            int x = x0 + i * (slot + gap);
            ctx.fill(x, top + 7, x + slot, top + 7 + slot, C_LINE);
            ctx.fill(x + 1, top + 8, x + slot - 1, top + 6 + slot,
                    i < lo.size() ? lo.get(i).argb() : C_CARD_OFF);
        }
    }

    private void drawSidebar(DrawContext ctx, int mx, int my) {
        Category[] cats = Category.values();
        for (int r = 0; r < VISIBLE_CATS && catScroll + r < cats.length; r++) {
            Category c = cats[catScroll + r];
            int y = top + SIDE_TOP + r * ROW_H;
            boolean sel = c == category;
            boolean hover = inside(mx, my, left, y, SIDE_W, ROW_H - 1);
            ctx.fill(left, y, left + SIDE_W, y + ROW_H - 1, sel ? C_ROW_SEL : hover ? C_ROW_HOVER : C_SIDE);
            if (sel) ctx.fill(left, y, left + 2, y + ROW_H - 1, C_ACCENT);
            ctx.drawItem(icons.get(c), left + 4, y + 1);
            if (StyleManager.hasAnyActive(c)) {
                ctx.fill(left + 3, y + 1, left + 7, y + 5, C_GREEN);
            }
            ctx.drawText(textRenderer, textRenderer.trimToWidth(c.displayName, SIDE_W - 44), left + 24, y + 5,
                    sel ? C_TEXT : C_MUTED, false);
            String n = String.valueOf(StyleRegistry.all(c).size());
            ctx.drawText(textRenderer, n, left + SIDE_W - 9 - textRenderer.getWidth(n), y + 5,
                    sel ? C_ACCENT : C_DISABLED, false);
        }
        int total = cats.length;
        if (total > VISIBLE_CATS) {
            int trackH = VISIBLE_CATS * ROW_H;
            int thumbH = Math.max(8, trackH * VISIBLE_CATS / total);
            int maxScroll = total - VISIBLE_CATS;
            int thumbY = top + SIDE_TOP + (trackH - thumbH) * catScroll / maxScroll;
            ctx.fill(left + SIDE_W - 3, top + SIDE_TOP, left + SIDE_W - 1, top + SIDE_TOP + trackH, C_LINE);
            ctx.fill(left + SIDE_W - 3, thumbY, left + SIDE_W - 1, thumbY + thumbH, C_ACCENT);
        }
    }

    private void drawControls(DrawContext ctx) {
        if (!PackSync.hasHeldItem(category)) return;
        int cx = left + SIDE_W + GAP;
        int cy = top + 50;
        ctx.drawText(textRenderer, "Scale", cx, cy + 4, C_MUTED, false);
        String v = String.format(Locale.ROOT, "%.1fx", StyleManager.getScale(category));
        ctx.drawText(textRenderer, v, cx + 50 + (40 - textRenderer.getWidth(v)) / 2, cy + 4, C_TEXT, false);
    }

    private void drawTiles(DrawContext ctx, int mx, int my) {
        int gx = left + SIDE_W + GAP;
        int gy = top + 70;
        for (int i = 0; i < visible.size(); i++) {
            Style s = visible.get(i);
            int x = gx + (i % COLS) * STRIDE_X;
            int y = gy + (i / COLS) * STRIDE_Y;
            boolean sel = s.equals(selected);
            boolean hover = inside(mx, my, x, y, CELL_W, CELL_H);

            ctx.fill(x, y, x + CELL_W, y + CELL_H, sel ? C_ACCENT : hover ? C_CARD_HOVER : C_LINE);
            ctx.fill(x + 1, y + 1, x + CELL_W - 1, y + CELL_H - 1, hover ? C_CARD_HOVER : C_CARD);

            if (s.vanilla()) {
                ctx.drawItem(icons.get(s.category()), x + (CELL_W - 16) / 2, y + 5);
            } else {
                drawPreview(ctx, s, x + 3, y + 3, x + CELL_W - 3, y + 25);
            }
            String name = textRenderer.trimToWidth(s.name(), CELL_W - 6);
            ctx.drawText(textRenderer, name, x + 3, y + 29, sel ? C_ACCENT : C_TEXT, false);

            if (isActiveFor(s)) {
                ctx.fill(x + 5, y + 5, x + 10, y + 10, C_GREEN);
            }
            if (StyleManager.isFavorite(s)) {
                ctx.drawText(textRenderer, "*", x + CELL_W - 10, y + 4, C_GOLD, true);
            }
        }
        if (visible.isEmpty()) {
            ctx.drawText(textRenderer, "No styles match.", gx + 4, gy + 6, C_MUTED, false);
        }
    }

    private void drawPager(DrawContext ctx) {
        int cx = left + SIDE_W + GAP;
        String txt = (page + 1) + " / " + pageCount() + "  (" + filteredCount + ")";
        int w = textRenderer.getWidth(txt);
        ctx.drawText(textRenderer, txt, cx + (CENTER_W - w) / 2, top + 210, C_MUTED, false);
    }

    private void drawInspector(DrawContext ctx) {
        int ix = left + SIDE_W + GAP + CENTER_W + GAP;
        Style s = selected;
        if (s == null) return;

        drawInspectorPreview(ctx, s, ix + 4, top + 30, ix + INSP_W - 4, top + 94);
        ctx.drawText(textRenderer, textRenderer.trimToWidth(s.name(), INSP_W - 8), ix + 4, top + 100, C_TEXT, true);
        ctx.drawText(textRenderer, s.category().displayName + (s.custom() ? " - custom" : ""), ix + 4, top + 112, C_MUTED, false);

        boolean active = isActiveFor(s);
        ctx.drawText(textRenderer, active ? "Active" : "Inactive", ix + 4, top + 124, active ? C_GREEN : C_MUTED, false);
        if (!notice.isEmpty()) {
            int ny = top + 194;
            for (var line : textRenderer.wrapLines(Text.literal(notice), INSP_W - 8)) {
                if (ny > top + 218) break;
                ctx.drawText(textRenderer, line, ix + 4, ny, C_GOLD, false);
                ny += 9;
            }
        }
        if (PackSync.slotIds(s.category()).isEmpty()) {
            Style now = StyleManager.getActive(s.category());
            ctx.drawText(textRenderer, "Now: " + textRenderer.trimToWidth(now.name(), INSP_W - 38), ix + 4, top + 136, C_MUTED, false);
        }
    }

    /** Grosse Vorschau: 3D (drehendes Item, Isometrie-Block) oder Textur-Blatt, sonst Farbflaeche. */
    private void drawInspectorPreview(DrawContext ctx, Style s, int x1, int y1, int x2, int y2) {
        ctx.fill(x1 - 1, y1 - 1, x2 + 1, y2 + 1, C_ACCENT);
        ctx.fill(x1, y1, x2, y2, C_SIDE);

        if (s.vanilla()) {
            int cx = (x1 + x2) / 2;
            ctx.drawItem(icons.get(s.category()), cx - 8, y1 + 16);
            String t = "Vanilla";
            ctx.drawText(textRenderer, t, cx - textRenderer.getWidth(t) / 2, y1 + 38, C_MUTED, false);
            return;
        }

        View view = viewOf(s.category());
        if (s.category() == Category.CRYSTAL && "item".equals(slotId())) {
            view = View.ITEM; // Kristall in der Hand: 16x16-Item
        }
        PixelImage img = null;
        if (view != View.NONE) {
            img = s.custom() ? CustomStyles.pixels(s) : synth(s, view);
        }
        if (img == null) {
            drawPreview(ctx, s, x1, y1, x2, y2);
            return;
        }
        switch (view) {
            case ITEM -> Preview3D.item(ctx, img, x1, y1, x2 - x1, y2 - y1);
            case BLOCK -> Preview3D.cube(ctx, img, x1, y1, x2 - x1, y2 - y1);
            case CRYSTAL -> Preview3D.crystal(ctx, img, x1, y1, x2 - x1, y2 - y1);
            default -> Preview3D.sheet(ctx, img, x1 + 2, y1 + 2, x2 - x1 - 4, y2 - y1 - 4);
        }
        ctx.drawText(textRenderer, view == View.SHEET ? "sheet" : "3D", x1 + 3, y2 - 10, C_DISABLED, false);
    }

    private PixelImage synth(Style s, View view) {
        return synthCache.computeIfAbsent(s.id() + "#" + view, k -> view == View.SHEET || view == View.CRYSTAL
                ? PixelImage.synth(s.color(), 64, 32)
                : PixelImage.synth(s.color(), 16, 16));
    }

    private void drawExtras(DrawContext ctx) {
        int cx = left + SIDE_W + GAP;
        ctx.drawText(textRenderer, "Explosion volume", cx, top + 38, C_TEXT, false);
        int vol = StyleManager.getExplosionVolume();
        String v = vol <= 0 ? "off" : vol + "%";
        ctx.drawText(textRenderer, v, cx + 146 + (44 - textRenderer.getWidth(v)) / 2, top + 38, vol >= 100 ? C_MUTED : C_ACCENT, false);

        if (!notice.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(notice, CENTER_W), cx, top + 52, C_GOLD, false);
        }
        int ny = top + 62 + StyleManager.EXTRAS.size() * 22 + 4;
        ctx.drawText(textRenderer, "Outline, glint and UI animation toggles are saved,", cx, ny, C_MUTED, false);
        ctx.drawText(textRenderer, "but not applied in game yet.", cx, ny + 10, C_GOLD, false);

        ctx.drawText(textRenderer, "Share textures with friends", cx, top + 182, C_TEXT, false);
        if (!shareMsg.isEmpty()) {
            ctx.drawText(textRenderer, textRenderer.trimToWidth(shareMsg, CENTER_W), cx, top + 214, C_GOLD, false);
        }
    }

    // --------------------------------------------------------------- Helfer

    /** Flache Vorschau: Pixelraster bei Custom-Styles, sonst Farbverlauf. */
    private static void drawPreview(DrawContext ctx, Style s, int x1, int y1, int x2, int y2) {
        int[] p = s.preview();
        if (p == null || p.length < Style.PW * Style.PH) {
            ctx.fillGradient(x1, y1, x2, y2, s.argb(), darken(s.argb()));
            return;
        }
        int w = x2 - x1, h = y2 - y1;
        for (int j = 0; j < Style.PH; j++) {
            int cy1 = y1 + j * h / Style.PH;
            int cy2 = y1 + (j + 1) * h / Style.PH;
            if (cy2 <= cy1) continue;
            int i = 0;
            while (i < Style.PW) {
                int color = p[j * Style.PW + i];
                int run = i + 1;
                while (run < Style.PW && p[j * Style.PW + run] == color) run++; // gleiche Pixel zusammenfassen
                int cx1 = x1 + i * w / Style.PW;
                int cx2 = x1 + run * w / Style.PW;
                if (cx2 > cx1) ctx.fill(cx1, cy1, cx2, cy2, color);
                i = run;
            }
        }
    }

    private static int darken(int argb) {
        int r = (int) (((argb >> 16) & 0xFF) * 0.45);
        int g = (int) (((argb >> 8) & 0xFF) * 0.45);
        int b = (int) ((argb & 0xFF) * 0.45);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
