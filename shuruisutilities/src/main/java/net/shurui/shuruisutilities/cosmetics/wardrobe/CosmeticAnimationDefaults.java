package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;

/**
 * The five bundled Halloween 2026 TRIGGERED animations, as SEED catalogue entries.
 *
 * <p>SEED CONTENT, exactly like {@link CosmeticHalloweenDefaults} and {@link CosmeticEffectDefaults}:
 * {@link CosmeticCatalog#seedDefaults()} puts one {@link CosmeticDef} per id here into a catalogue that has never
 * held that id, once, stamped ZERO, and from that moment each is an ordinary admin-owned record, editable and
 * deletable in game and synced across shards. Changing a line here does NOT change a live server's copy, and a
 * deleted seed leaves a tombstone so it does not grow back.
 *
 * <h2>What each one is</h2>
 * Each entry lives on no worn slot: its {@link CosmeticDef#slot} is left {@link CosmeticSlot#JOIN_LEAVE} only as a
 * default, and a player equips it into either paired triggered slot ({@code JOIN_LEAVE} or {@code TELEPORT}). Its
 * {@link CosmeticDef#animation} is {@link CosmeticAnimation#STYLE_GEO}: it draws the pack's real animated RIG,
 * converted to GeckoLib under {@code geo/fx/cosmetic_anim/} (the {@code anim_in_<key>} / {@code anim_out_<key>}
 * models), plus the matching in / out sound bundled under {@code assets/dmz_ragnarok/sounds/cosmetic/}. Each keeps
 * a particle style as its fallback for when the rig cannot be drawn (see {@link CosmeticAnimation}).
 *
 * <ul>
 *   <li><b>Cadaver Curse</b>: a low necrotic-green vortex of rot, earthy dust that sinks. Grave decay.</li>
 *   <li><b>Hellgate</b>: a ring-gate of fire, red and amber glow flaring outward then in.</li>
 *   <li><b>Midnight Lord</b>: a regal violet column rising, dark and full-bright, a lord's entrance.</li>
 *   <li><b>Ragdoll Possession</b>: an eerie cyan spirit burst thrown outward, a body seized.</li>
 *   <li><b>Risen Dread</b>: a pale ghostly column rising slowly from the ground, cold and dreadful.</li>
 * </ul>
 */
public final class CosmeticAnimationDefaults
{
    /** The event these are grouped under, matching {@link CosmeticHalloweenDefaults#EVENT}. */
    public static final String COLLECTION = CosmeticHalloweenDefaults.EVENT;

    private CosmeticAnimationDefaults()
    {
    }

    /** Fresh instances every call. The catalogue takes ownership of what it is handed. */
    public static List<CosmeticDef> animations()
    {
        List<CosmeticDef> out = new ArrayList<>();
        // style GEO now: each points at its converted rig (anim_in_<key> / anim_out_<key> under geo/fx/cosmetic_anim),
        // and keeps a particle style as the fallback for when the rig cap is reached or geo drawing is off. The in /
        // out tick lengths are the actived clip lengths (seconds times twenty), so neither half is cut short.
        out.add(anim("fx_cadavercurse", "Cadaver Curse", "cadavercurse",
                CosmeticAnimation.STYLE_VORTEX, "#7FBF3F", "#3A2A10", false, 1.1F, 50, 60));
        out.add(anim("fx_hellgate", "Hellgate", "hellgate",
                CosmeticAnimation.STYLE_GATE, "#FF3A10", "#FFB020", true, 1.2F, 75, 46));
        out.add(anim("fx_midnightlord", "Midnight Lord", "midnightlord",
                CosmeticAnimation.STYLE_COLUMN, "#7A2CCB", "#2A0A3A", true, 1.0F, 40, 33));
        out.add(anim("fx_ragdollpossession", "Ragdoll Possession", "ragdollpossession",
                CosmeticAnimation.STYLE_BURST, "#38E0C0", "#0E1A18", true, 1.1F, 40, 30));
        out.add(anim("fx_risendread", "Risen Dread", "risendread",
                CosmeticAnimation.STYLE_COLUMN, "#BFE8FF", "#54707E", true, 0.9F, 40, 40));
        return out;
    }

    /**
     * @param id            the catalogue id, also the icon item id in {@link CosmeticContentItems}
     * @param name          the display name
     * @param key           the rig / sound stem: the rig is {@code anim_in_<key>} / {@code anim_out_<key>}, the
     *                      sound is {@code cosmetic_fx_<key>_in} / {@code _out}
     * @param fallbackStyle the particle style drawn when the rig cannot be shown
     * @param inTicks       the actived clip length of the in rig, in ticks
     * @param outTicks      the actived clip length of the out rig, in ticks
     */
    private static CosmeticDef anim(String id, String name, String key, String fallbackStyle,
            String primary, String secondary, boolean glow, float density, int inTicks, int outTicks)
    {
        // Home slot JOIN_LEAVE (a paired triggered slot) so a freshly seeded catalogue lands them on an enabled
        // slot. A triggered animation is equippable into either paired slot regardless of its home, so this is only
        // the default tab, not a restriction. Existing catalogues seeded on the retired JOIN slot are folded over
        // by CosmeticCatalog.migrateTriggeredSlotsToPaired().
        CosmeticDef d = new CosmeticDef(id, CosmeticSlot.JOIN_LEAVE);
        d.displayName = name;
        d.description = "Halloween triggered animation.";
        // Icon items are registered in CosmeticContentItems and added to the cosmetic_models tag.
        d.modelId = "dmz_ragnarok:" + id;
        d.modelPreviewId = "dmz_ragnarok:" + id;
        d.tradeable = false;
        d.event = COLLECTION;
        d.rarity = "";
        // Triggered animations are Normal only: there is no Magic effect roll on an animation, its identity is the
        // animation itself.
        d.allowedQualities = java.util.EnumSet.of(CosmeticQuality.NORMAL);
        CosmeticAnimation a = new CosmeticAnimation();
        a.style = CosmeticAnimation.STYLE_GEO;
        a.geoRig = key;
        a.geoScale = 1.0F;
        a.geoFallbackStyle = fallbackStyle;
        a.geoInTicks = inTicks;
        a.geoOutTicks = outTicks;
        a.colorPrimary = primary;
        a.colorSecondary = secondary;
        a.glow = glow;
        // The flat duration only matters if the rig is ever removed; keep it at the in-clip length so a downgraded
        // record still looks right as particles.
        a.durationTicks = inTicks;
        a.radius = CosmeticAnimation.DEFAULT_RADIUS;
        a.density = density;
        a.soundIn = "dmz_ragnarok:cosmetic_fx_" + key + "_in";
        a.soundOut = "dmz_ragnarok:cosmetic_fx_" + key + "_out";
        a.soundVolume = 1.0F;
        a.soundPitch = 1.0F;
        d.animation = a;
        return d.normalise();
    }
}
