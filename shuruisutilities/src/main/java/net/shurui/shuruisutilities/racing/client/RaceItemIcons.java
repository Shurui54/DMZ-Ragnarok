package net.shurui.shuruisutilities.racing.client;

import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.racing.physics.PowerupKind;

/**
 * The HUD icon and theme colour for each race {@link PowerupKind}, used by the item-slot roulette. Each kind maps to
 * a 16x16 icon under {@code assets/dmz_ragnarok/textures/gui/race/items/}; these are simple own icons for now and can
 * be refined (or swapped for runtime-tinted DMZ textures) in R11. The theme colour tints the slot frame and is the
 * fallback fill if an icon is ever missing, so the slot always reads.
 */
public final class RaceItemIcons
{
    private RaceItemIcons() {}

    private static final String BASE = "textures/gui/race/items/";

    /** The file base name per kind ordinal (0..15), matching the shipped PNGs. NONE has no icon. */
    private static final String[] NAMES = {
            "destroyer_aura", "senzu", "kaioken_x3", "kaioken_x20", "ki_blast", "hellzone", "spirit_bomb", "ki_mine",
            "saibaman", "fake_ball", "gravity_crush", "solar_flare", "nimbus", "afterimage", "kiai", "zeni"
    };

    /** A theme colour per kind ordinal (0..15), for the slot frame tint / icon fallback fill. */
    private static final int[] COLOURS = {
            0x9B30FF, // destroyer aura
            0x4CE24C, // senzu
            0xFF4040, // kaioken x3
            0xFFC542, // kaioken x20
            0x3CFF3C, // ki blast
            0xFF2A2A, // hellzone
            0x9FE7FF, // spirit bomb
            0xFFD23F, // ki mine
            0x6FBF4C, // saibaman
            0xFF5A3C, // fake ball
            0xE23C3C, // gravity crush
            0xFFF3A0, // solar flare
            0xFFF0C0, // nimbus
            0xC0C6FF, // afterimage
            0xF0F0F0, // kiai
            0xFFC631  // zeni
    };

    private static final ResourceLocation[] ICONS = new ResourceLocation[NAMES.length];

    static
    {
        for (int i = 0; i < NAMES.length; i++)
            ICONS[i] = new ResourceLocation("dmz_ragnarok", BASE + NAMES[i] + ".png");
    }

    /** The 16x16 icon for a kind, or null for {@link PowerupKind#NONE}. */
    public static ResourceLocation icon(PowerupKind kind)
    {
        int o = kind.ordinal();
        return o >= 0 && o < ICONS.length ? ICONS[o] : null;
    }

    /** The icon for a raw ordinal 0..15, or null out of range. */
    public static ResourceLocation icon(int ordinal)
    {
        return ordinal >= 0 && ordinal < ICONS.length ? ICONS[ordinal] : null;
    }

    /** The ARGB theme colour for a kind (opaque), white for {@link PowerupKind#NONE}. */
    public static int colour(PowerupKind kind)
    {
        int o = kind.ordinal();
        return 0xFF000000 | (o >= 0 && o < COLOURS.length ? COLOURS[o] : 0xFFFFFF);
    }
}
