package net.shurui.dev.sdu.combat;

import net.minecraft.world.entity.LivingEntity;

// tiny public API for the NPC-defense system. sdu owns the mitigation curve (NpcDefenseCurve); everyone else
// just reads/writes one persistent-data key. FIXED contract (siblings depend on this exact name):
//   KEY = a double on getPersistentData(), on the SAME scale as a player's StatsData.getDefense() so the curve
//   produces player-matching mitigation. 0 or absent = no mitigation.
// siblings can call set() (compileOnly on sdu) or write the raw NBT key, equivalent.
public final class NpcDefense {

    // read/mitigation happens ONLY in sdu.
    public static final String KEY = "dmz_npc_defense";

    private NpcDefense() {
    }

    // defense <= 0 (or NaN) removes the key, so absent/zero uniformly means "no mitigation".
    public static void set(LivingEntity e, double defense) {
        if (e == null) {
            return;
        }
        if (!(defense > 0.0)) { // also false for NaN
            e.getPersistentData().remove(KEY);
            return;
        }
        e.getPersistentData().putDouble(KEY, defense);
    }

    // 0.0 if unset.
    public static double get(LivingEntity e) {
        if (e == null) {
            return 0.0;
        }
        return e.getPersistentData().contains(KEY) ? e.getPersistentData().getDouble(KEY) : 0.0;
    }
}
