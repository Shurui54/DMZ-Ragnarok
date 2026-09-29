package net.shurui.dev.sdu.compat.cnpc;

import java.util.List;

/**
 * Built-in {@link DmzModelPreset}s for the {@code DMZ NPC} tab. Two SDU-owned base humanoids, {@code sdu_slim}
 * (3px arms) and {@code sdu_wide} (4px arms), rigged to DMZ's saga skeleton (bones
 * {@code root/waist/head/body/left_arm/right_arm/left_leg/right_leg}) so they play the full saga set
 * ({@code animations/entity/sagas/saga_base}). Standard player-skin UV, so the NPC wears whatever skin the
 * editor picks (default / player-name / URL).
 *
 * <p>{@code texture} is left blank so the NPC keeps its own skin. Geo is an {@code sdu:} asset, animation a
 * {@code dragonminez:} asset, both referenced by resource location (GeckoLib bakes each at load); nothing is copied.</p>
 */
public final class DmzModelPresets {

    /** DMZ's shared saga animation library - the "full saga NPC animation style". */
    private static final String SAGA_ANIM = "dragonminez:animations/entity/sagas/saga_base.animation.json";

    private static final List<DmzModelPreset> PRESETS = List.of(
            new DmzModelPreset(
                    "sdu_slim", "SDU Slim (DMZ saga anim)",
                    "dmz_ragnarok:geo/entity/sdu_slim.geo.json", SAGA_ANIM,
                    "idle", "walk", "attack1_1", "",
                    ""),
            new DmzModelPreset(
                    "sdu_wide", "SDU Wide (DMZ saga anim)",
                    "dmz_ragnarok:geo/entity/sdu_wide.geo.json", SAGA_ANIM,
                    "idle", "walk", "attack1_1", "",
                    "")
    );

    private DmzModelPresets() {
    }

    public static List<DmzModelPreset> all() {
        return PRESETS;
    }

    /** Preset at list index {@code i}, or {@code null} if out of range. */
    public static DmzModelPreset byIndex(int i) {
        return i >= 0 && i < PRESETS.size() ? PRESETS.get(i) : null;
    }

    /** Preset whose display name equals {@code name}, or {@code null}. */
    public static DmzModelPreset byDisplayName(String name) {
        for (DmzModelPreset p : PRESETS) {
            if (p.displayName().equals(name)) {
                return p;
            }
        }
        return null;
    }
}
