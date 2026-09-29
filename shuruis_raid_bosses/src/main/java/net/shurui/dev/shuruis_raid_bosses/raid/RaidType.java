package net.shurui.dev.shuruis_raid_bosses.raid;

/**
 * STANDARD: one scaled boss. PARALLEL_QUEST: a wave of assorted enemies, winner is MVP scored by
 * damage and kills. BOSS_RUSH: bosses spawned one after another with a configurable delay.
 */
public enum RaidType {
    STANDARD,
    PARALLEL_QUEST,
    BOSS_RUSH;

    public static RaidType byOrdinal(int i) {
        RaidType[] v = values();
        return (i >= 0 && i < v.length) ? v[i] : STANDARD;
    }

    public static RaidType byName(String name) {
        if (name == null) return STANDARD;
        try {
            return valueOf(name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return STANDARD;
        }
    }

    /** human label for GUIs */
    public String label() {
        return switch (this) {
            case STANDARD -> "Standard";
            case PARALLEL_QUEST -> "Parallel Quest";
            case BOSS_RUSH -> "Boss Rush";
        };
    }

    public String displayKey() {
        return switch (this) {
            case STANDARD -> "raidtype.dmz_ragnarok.raid.standard";
            case PARALLEL_QUEST -> "raidtype.dmz_ragnarok.raid.parallel_quest";
            case BOSS_RUSH -> "raidtype.dmz_ragnarok.raid.boss_rush";
        };
    }

    public net.minecraft.network.chat.Component display() {
        return net.minecraft.network.chat.Component.translatable(displayKey());
    }
}
