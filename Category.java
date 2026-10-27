package dev.styleforge.style;

public enum Category {
    CRYSTAL("crystal", "Crystal"),
    OBSIDIAN("obsidian", "Obsidian"),
    ANCHOR("anchor", "Anchor"),
    TOOLS("tools", "Tools"),
    SHIELD("shield", "Shield"),
    GAPPLES("gapples", "Golden Apples"),
    COBWEBS("cobwebs", "Cobwebs"),
    SKY("sky", "Sky"),
    TOTEMS("totems", "Totems"),
    PEARLS("pearls", "Pearls"),
    FIRE("fire", "Fire"),
    INVENTORY("inventory", "Inventory");

    public final String id;
    public final String displayName;

    Category(String id, String displayName) {
        this.id = id;
        this.displayName = displayName;
    }
}
