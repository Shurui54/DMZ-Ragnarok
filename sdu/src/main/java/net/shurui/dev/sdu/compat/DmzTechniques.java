package net.shurui.dev.sdu.compat;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ProgressionSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.techniques.KiAttackData;
import com.dragonminez.common.stats.techniques.PredefinedTechniques;
import com.dragonminez.common.stats.techniques.StrikeAttackData;
import com.dragonminez.common.stats.techniques.TechniqueData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.shurui.dev.sdu.DmzNpc;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grants DMZ techniques (ki attacks like kamehameha/galick_gun + strike attacks). DMZ's quest-reward
 * parser has no {@code KI_TECHNIQUE} type, so a SKILL reward can't give a usable attack; instead the editor
 * writes a COMMAND reward running {@code /rg npc givetechnique}, which lands here to unlock from
 * {@link PredefinedTechniques}. Direct DMZ refs (mandatory dep), guarded so a rename/absence no-ops.
 */
public final class DmzTechniques {

    /** custom ki technique: a copy of DMZ's big_bang with a custom firing sound */
    public static final String BIDEN_BLAST_ID = "biden_blast";
    private static final String BIDEN_BLAST_SOURCE = "big_bang";

    /**
     * Shurui's Hakai: admin ki technique cloned from DMZ's {@code big_bang}, re-id'd/renamed/recoloured
     * purple. Never fired as a normal projectile: {@code TechniqueDispatcherMixin} intercepts the cast and
     * runs the erase sequence. Only operators ({@code hasPermissions(2)}) get it, and the cast re-checks that.
     */
    public static final String SHURUIS_HAKAI_ID = "shuruis_hakai";
    private static final String SHURUIS_HAKAI_SOURCE = "big_bang";
    private static final int HAKAI_INTERIOR = 0xAA00FF;
    private static final int HAKAI_EXTERIOR = 0xCC66FF;

    private DmzTechniques() {
    }

    /** all grantable technique ids (DMZ ki + strike attacks, plus custom biden_blast) */
    public static List<String> techniqueIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            if (PredefinedTechniques.REGISTRY != null) {
                ids.addAll(PredefinedTechniques.REGISTRY.keySet());
            }
            if (PredefinedTechniques.STRIKE_REGISTRY != null) {
                ids.addAll(PredefinedTechniques.STRIKE_REGISTRY.keySet());
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DMZ techniques: {}", DmzNpc.MODID, t.toString());
        }
        List<String> list = new ArrayList<>(ids);
        list.sort(String::compareTo);
        // obtainable ONLY via /rg npc givetechnique (not in any DMZ unlock path)
        list.add(BIDEN_BLAST_ID);
        return list;
    }

    /**
     * The {@code DBSagasEntity.KiSkillType} names a saga fighter can fire (KAMEHAMEHA, GALICK_GUN, ...),
     * fed to {@code addKiSkill} by {@link net.shurui.dev.sdu.entity.SduDmzFighter} like the raid boss mod
     * configures its saga bosses. Read off the enum so a DMZ update stays in sync.
     */
    public static List<String> kiSkillTypeNames() {
        List<String> list = new ArrayList<>();
        try {
            for (com.dragonminez.common.init.entities.sagas.DBSagasEntity.KiSkillType t
                    : com.dragonminez.common.init.entities.sagas.DBSagasEntity.KiSkillType.values()) {
                list.add(t.name());
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DBSagasEntity.KiSkillType: {}", DmzNpc.MODID, t.toString());
        }
        return list;
    }

    /** ki-blast technique ids only (no strike attacks): selectable ki attacks for a DMZ-style NPC */
    public static List<String> kiBlastIds() {
        Set<String> ids = new LinkedHashSet<>();
        try {
            if (PredefinedTechniques.REGISTRY != null) {
                ids.addAll(PredefinedTechniques.REGISTRY.keySet());
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DMZ ki techniques: {}", DmzNpc.MODID, t.toString());
        }
        List<String> list = new ArrayList<>(ids);
        list.sort(String::compareTo);
        list.add(BIDEN_BLAST_ID);
        return list;
    }

    /** Unlock a predefined technique for the player. Returns true if it was granted. */
    public static boolean grant(Player player, String techniqueId) {
        if (player == null || techniqueId == null || techniqueId.isBlank()) {
            return false;
        }
        try {
            StatsData stats = DmzForms.stats(player);
            if (stats == null) {
                return false;
            }
            TechniqueData proto = null;
            if (SHURUIS_HAKAI_ID.equals(techniqueId)) {
                // private (the Ragnarok Key runs the sequence): never granted keyless
                if (!net.shurui.dev.sdu.api.key.HakaiHooks.available()) {
                    return false;
                }
                // operator-gated: refuse hakai to a non-operator so it can't leak to someone whose cast
                // wouldn't be re-gated to the erase
                if (!player.hasPermissions(2)) {
                    DmzNpc.LOGGER.warn("[{}] Refused to grant '{}' to non-operator {}.",
                            DmzNpc.MODID, SHURUIS_HAKAI_ID, player.getName().getString());
                    return false;
                }
                proto = buildShuruisHakai();
            } else if (BIDEN_BLAST_ID.equals(techniqueId)) {
                proto = buildBidenBlast();
            } else if (PredefinedTechniques.REGISTRY != null && PredefinedTechniques.REGISTRY.containsKey(techniqueId)) {
                KiAttackData k = new KiAttackData();
                k.load(PredefinedTechniques.REGISTRY.get(techniqueId).save());
                proto = k;
            } else if (PredefinedTechniques.STRIKE_REGISTRY != null && PredefinedTechniques.STRIKE_REGISTRY.containsKey(techniqueId)) {
                StrikeAttackData s = new StrikeAttackData();
                s.load(PredefinedTechniques.STRIKE_REGISTRY.get(techniqueId).save());
                proto = s;
            }
            if (proto == null) {
                DmzNpc.LOGGER.warn("[{}] Unknown technique id '{}' (not a predefined technique).", DmzNpc.MODID, techniqueId);
                return false;
            }
            stats.getTechniques().unlockTechnique(proto);
            // push it to the client's SkillsMenu now. unlockTechnique() only mutates the server-side map;
            // without this it wouldn't show until relog (when DMZ's login sync resends it).
            syncTechniques(player);
            return true;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to grant technique '{}': {}", DmzNpc.MODID, techniqueId, t.toString());
            return false;
        }
    }

    /**
     * Resync a player's progression (includes {@code Techniques.save()}) so a just-unlocked technique shows
     * in the SkillsMenu without a relog. Mirrors DMZ's {@code FormsCommand} resync. No-op off-server.
     */
    private static void syncTechniques(Player player) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        try {
            NetworkHandler.sendToTrackingEntityAndSelf(new ProgressionSyncS2C(serverPlayer), serverPlayer);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Technique progression sync failed for {}: {}",
                    DmzNpc.MODID, serverPlayer.getName().getString(), t.toString());
        }
    }

    // clone of DMZ's big_bang ki attack re-id'd to biden_blast so our projectile mixin can swap the firing
    // sound. null if big_bang isn't found.
    private static KiAttackData buildBidenBlast() {
        if (PredefinedTechniques.REGISTRY == null || !PredefinedTechniques.REGISTRY.containsKey(BIDEN_BLAST_SOURCE)) {
            DmzNpc.LOGGER.warn("[{}] Cannot build biden_blast: DMZ '{}' technique not found.", DmzNpc.MODID, BIDEN_BLAST_SOURCE);
            return null;
        }
        KiAttackData k = new KiAttackData();
        k.load(PredefinedTechniques.REGISTRY.get(BIDEN_BLAST_SOURCE).save());
        k.setId(BIDEN_BLAST_ID);
        k.setName("Biden Blast");
        return k;
    }

    /**
     * Clone of DMZ's {@code big_bang} (a MEDIUM_BALL on the {@code ki.bigbang} charge anim), re-id'd, given
     * a literal name (no lang key) and recoloured purple. Cloning the saved NBT inherits DMZ's derived combat
     * values; re-run {@link KiAttackData#calculateDerivedValues()} so the colour/name edits stay consistent.
     * Null if the source isn't found.
     */
    public static KiAttackData buildShuruisHakai() {
        try {
            if (PredefinedTechniques.REGISTRY == null || !PredefinedTechniques.REGISTRY.containsKey(SHURUIS_HAKAI_SOURCE)) {
                DmzNpc.LOGGER.warn("[{}] Cannot build shuruis_hakai: DMZ '{}' technique not found.",
                        DmzNpc.MODID, SHURUIS_HAKAI_SOURCE);
                return null;
            }
            KiAttackData k = new KiAttackData();
            k.load(PredefinedTechniques.REGISTRY.get(SHURUIS_HAKAI_SOURCE).save());
            k.setId(SHURUIS_HAKAI_ID);
            k.setName("Shurui's Hakai"); // literal, no dot -> DMZ renders it verbatim, no lang entry required
            k.setKiType(KiAttackData.KiType.MEDIUM_BALL);
            k.setAnimation("ki.bigbang");
            k.setColorInterior(HAKAI_INTERIOR);
            k.setColorExterior(HAKAI_EXTERIOR);
            if (!k.getAllowedRaces().contains("ALL")) {
                k.getAllowedRaces().add("ALL");
            }
            k.calculateDerivedValues();
            return k;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Could not build shuruis_hakai: {}", DmzNpc.MODID, t.toString());
            return null;
        }
    }

    /**
     * Remove a previously unlocked technique from the player's unlocked-techniques map and resync so it
     * drops out of the SkillsMenu without a relog. Returns true only if the player actually had it. No-op
     * off-server or when DMZ is unavailable / the player has no stats.
     */
    public static boolean revoke(Player player, String techniqueId) {
        if (player == null || techniqueId == null || techniqueId.isBlank()) {
            return false;
        }
        try {
            StatsData stats = DmzForms.stats(player);
            if (stats == null) {
                return false;
            }
            if (!stats.getTechniques().getUnlockedTechniques().containsKey(techniqueId)) {
                return false; // nothing to remove
            }
            stats.getTechniques().removeTechnique(techniqueId);
            // mirror grant(): push the updated progression so the client's SkillsMenu drops it now.
            syncTechniques(player);
            return true;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to revoke technique '{}': {}", DmzNpc.MODID, techniqueId, t.toString());
            return false;
        }
    }
}
