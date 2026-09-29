package net.shurui.dev.sdu.compat.cnpc;

import com.dragonminez.common.init.entities.sagas.DBSagasEntity;
import com.goodbird.cnpcgeckoaddon.data.CustomModelData;
import com.goodbird.cnpcgeckoaddon.mixin.IDataDisplay;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.entity.SduDmzFighter;
import net.shurui.dev.sdu.registry.ModEntities;
import noppes.npcs.controllers.ServerCloneController;
import noppes.npcs.entity.EntityCustomNpc;

/**
 * Reads a stored Custom NPCs clone and materialises its {@code EntityCustomNpc} without adding it to the
 * world. Used by the saga-clone bridge (4.7): the DMZ spawn mixin substitutes this entity for the native
 * saga mob, then DMZ positions it, stamps quest NBT, and calls {@code addFreshEntity} itself.
 *
 * <p>Touches {@code noppes.npcs.*} directly, so classload only when {@link DmzCnpcCompat#cnpcAvailable()}
 * is true. Callers gate on that; every call is try/caught so a missing clone or CNPC API change degrades to
 * null (caller falls back to the DMZ entity).
 */
public final class CnpcCloneSpawner {

    private CnpcCloneSpawner() {
    }

    /** Parsed clone NBT cached per {@code "tab$name"}, invalidated when the backing JSON's lastModified changes. */
    private static final java.util.Map<String, CachedClone> CLONE_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** One cache slot: the file mtime it was read at, and the parsed (pre-{@code cleanTags}) NBT. */
    private static final class CachedClone {
        final long lastModified;
        final CompoundTag tag;

        CachedClone(long lastModified, CompoundTag tag) {
            this.lastModified = lastModified;
            this.tag = tag;
        }
    }

    /** Build the entity for clone {@code (tab, name)}, or null if unknown/unproducible. Returned detached (not in world). */
    public static Entity createClone(int tab, String name, Level level) {
        if (!(level instanceof ServerLevel serverLevel)) {
            return null;
        }
        try {
            ServerCloneController clones = ServerCloneController.Instance;
            if (clones == null) {
                return null;
            }
            // ONE parse per call (cached), not two. getCloneData reads and parses the clone JSON off disk every
            // time, and the old hasClone()+get() pair did that twice. We read once, then rebuild the entity from
            // the tag exactly as get() does (cleanTags -> EntityType.create on the same ServerLevel), skipping the
            // IEntity round-trip. cloneData hands back a COPY, so cleanTags mutating it can never poison the cache.
            CompoundTag tag = cloneData(clones, tab, name);
            if (tag == null) {
                DmzNpc.LOGGER.warn("[{}] saga clone '{}' (tab {}) not found in Custom NPCs storage; using DMZ entity.",
                        DmzNpc.MODID, name, tab);
                return null;
            }
            clones.cleanTags(tag);
            return EntityType.create(tag, serverLevel).orElse(null);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to build saga clone '{}' (tab {}): {}", DmzNpc.MODID, name, tab, t.toString());
            return null;
        }
    }

    /**
     * The parsed clone NBT for {@code (tab, name)}, or null if there is no such clone. Returns a COPY every time
     * so a caller (createClone runs cleanTags on it) can never mutate the cached instance. CNPC stores each clone
     * at {@code getDir()/<tab>/<name>.json}; we key the cache on that file's lastModified, so an edit or a fresh
     * save is picked up on the next call. When the path can't be resolved or the file is missing (lastModified 0)
     * we skip the cache and read straight through, so a stale entry can never outlive the file.
     */
    private static CompoundTag cloneData(ServerCloneController clones, int tab, String name) {
        long lastMod = 0L;
        try {
            java.io.File file = new java.io.File(new java.io.File(clones.getDir(), Integer.toString(tab)), name + ".json");
            lastMod = file.lastModified(); // 0 when the file is absent or the path can't be read
        } catch (Throwable ignored) {
            // fall through with lastMod 0: read through, do not cache
        }
        String key = tab + "$" + name;
        if (lastMod != 0L) {
            CachedClone cached = CLONE_CACHE.get(key);
            if (cached != null && cached.lastModified == lastMod) {
                return cached.tag.copy();
            }
        }
        CompoundTag fresh = clones.getCloneData(null, name, tab);
        if (fresh == null) {
            CLONE_CACHE.remove(key);
            return null;
        }
        if (lastMod == 0L) {
            return fresh; // uncached: this instance is ours alone, the caller may mutate it freely
        }
        CLONE_CACHE.put(key, new CachedClone(lastMod, fresh));
        return fresh.copy();
    }

    /**
     * Build a {@link SduDmzFighter} (real DMZ saga entity with saga AI) looking and fighting like the saved
     * clone {@code (tab, name)}: copies its model, texture, hair, and the SDU DMZ stats/ki. Returned detached.
     * Null if the clone is missing/unreadable, so the caller can fall back to DMZ's native entity.
     */
    public static Entity createFighterFromClone(int tab, String name, Level level) {
        try {
            Entity cloneEntity = createClone(tab, name, level);
            if (!(cloneEntity instanceof EntityCustomNpc npc)) {
                return null;
            }
            SduDmzFighter fighter = ModEntities.DMZ_FIGHTER.get().create(level);
            if (fighter == null) {
                return null;
            }
            configureFighter(fighter, npc);
            return fighter;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to build DMZ fighter from clone '{}' (tab {}): {}",
                    DmzNpc.MODID, name, tab, t.toString());
            return null;
        }
    }

    // copy a Custom NPC's DMZ model/skin/hair/stats onto a fresh fighter
    private static void configureFighter(SduDmzFighter fighter, EntityCustomNpc npc) {
        CustomModelData model = ((IDataDisplay) npc.display).getCustomModelData();
        // use the clone's full geo location (sdu/dragonminez/any), not a bare name
        String geo = model.getModel();
        if (geo != null && !geo.isBlank()) {
            fighter.setModelGeo(geo);
        }
        // CNPC name -> fighter name (shown above it, like a boss)
        String npcName = npc.display.getName();
        if (npcName != null && !npcName.isBlank()) {
            fighter.setCustomName(net.minecraft.network.chat.Component.literal(npcName));
            fighter.setCustomNameVisible(true);
        }
        // carry skin: texture (0), player name (1) or URL (2). player/URL resolve client-side.
        switch (npc.display.skinType) {
            case 1 -> nz(npc.display.getSkinPlayer(), pn -> fighter.setSkin(1, pn));
            case 2 -> nz(npc.display.getSkinUrl(), url -> fighter.setSkin(2, url));
            default -> nz(npc.display.getSkinTexture(), tex -> fighter.setSkin(0, tex));
        }
        SduHairHolder hair = (SduHairHolder) npc.display;
        fighter.setHairCode(hair.sdu$getHairCode());
        fighter.setHairColor(hair.sdu$getHairColor());
        applyStats(fighter, (SduNpcData) npc.display);
    }

    /** List every saved clone as {@code "tab$name"}. Touches {@code ServerCloneController}, so gate on {@link DmzCnpcCompat#cnpcAvailable()}. */
    public static java.util.List<String> listCloneTokens() {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            ServerCloneController clones = ServerCloneController.Instance;
            if (clones == null) {
                return out;
            }
            for (int tab = 0; tab < 16; tab++) {
                java.util.List<String> names = clones.getClones(tab);
                if (names == null) {
                    continue;
                }
                for (String name : names) {
                    out.add(tab + "$" + name);
                }
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to list Custom NPC clones: {}", DmzNpc.MODID, t.toString());
        }
        return out;
    }

    /** Build a fighter from a {@code "cnpc$tab$name"} ref (as stored by the quest editor). Null if malformed/unbuildable. */
    public static Entity fighterFromRef(String ref, Level level) {
        if (ref == null || !ref.startsWith("cnpc$")) {
            return null;
        }
        String[] p = ref.substring("cnpc$".length()).split("\\$", 2);
        if (p.length < 2) {
            return null;
        }
        try {
            return createFighterFromClone(Integer.parseInt(p[0]), p[1], level);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Configure an existing fighter (e.g. one DMZ already spawned) from a {@code "cnpc$tab$name"} ref. True on success. */
    public static boolean configureFighterFromRef(SduDmzFighter fighter, String ref) {
        if (ref == null || !ref.startsWith("cnpc$")) {
            return false;
        }
        String[] p = ref.substring("cnpc$".length()).split("\\$", 2);
        if (p.length < 2) {
            return false;
        }
        try {
            Entity cloneEntity = createClone(Integer.parseInt(p[0]), p[1], fighter.level());
            if (cloneEntity instanceof EntityCustomNpc npc) {
                configureFighter(fighter, npc);
                return true;
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to configure fighter from ref '{}': {}", DmzNpc.MODID, ref, t.toString());
        }
        return false;
    }

    /**
     * Read a saved clone's appearance (name/model/skin/hair) from a {@code "cnpc$tab$name"} ref without
     * building a fighter. Syncs the quest-GUI enemy preview so it renders the real clone.
     * {@link CnpcPreviewConfig#EMPTY} if malformed/unreadable.
     */
    public static CnpcPreviewConfig readConfig(String ref, Level level) {
        if (ref == null || !ref.startsWith("cnpc$")) {
            return CnpcPreviewConfig.EMPTY;
        }
        String[] p = ref.substring("cnpc$".length()).split("\\$", 2);
        if (p.length < 2) {
            return CnpcPreviewConfig.EMPTY;
        }
        try {
            Entity cloneEntity = createClone(Integer.parseInt(p[0]), p[1], level);
            if (!(cloneEntity instanceof EntityCustomNpc npc)) {
                return CnpcPreviewConfig.EMPTY;
            }
            CustomModelData model = ((IDataDisplay) npc.display).getCustomModelData();
            String geo = model.getModel();
            if (geo == null) {
                geo = "";
            }
            String name = npc.display.getName();
            if (name == null) {
                name = "";
            }
            int skinType = npc.display.skinType;
            String skinValue = switch (skinType) {
                case 1 -> npc.display.getSkinPlayer();
                case 2 -> npc.display.getSkinUrl();
                default -> npc.display.getSkinTexture();
            };
            if (skinValue == null) {
                skinValue = "";
            }
            SduHairHolder hair = (SduHairHolder) npc.display;
            String hairCode = hair.sdu$getHairCode();
            String hairColor = hair.sdu$getHairColor();
            return new CnpcPreviewConfig(name, geo, skinType, skinValue,
                    hairCode == null ? "" : hairCode, hairColor == null ? "" : hairColor);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Failed to read clone config from ref '{}': {}", DmzNpc.MODID, ref, t.toString());
            return CnpcPreviewConfig.EMPTY;
        }
    }

    /** {@code "dmz_ragnarok:geo/entity/sdu_wide.geo.json"} -> {@code "sdu_wide"}; falls back to the default model. */
    private static String modelNameFromGeo(String geo) {
        if (geo == null || geo.isBlank()) {
            return SduDmzFighter.DEFAULT_MODEL;
        }
        int slash = geo.lastIndexOf('/');
        String file = slash >= 0 ? geo.substring(slash + 1) : geo;
        int dot = file.indexOf('.');
        String namePart = dot >= 0 ? file.substring(0, dot) : file;
        return namePart.isBlank() ? SduDmzFighter.DEFAULT_MODEL : namePart;
    }

    private static void applyStats(SduDmzFighter fighter, SduNpcData s) {
        fighter.applyDmzStats(s.sdu$getBattlePower(), s.sdu$getHealth(), s.sdu$getKiBlastDamage(),
                s.sdu$getMoveSpeed(), s.sdu$getMeleeDamage(), s.sdu$getAiTier(), s.sdu$getKiMoves(),
                s.sdu$isNoRanged(), s.sdu$getDefense());
    }

    /** run {@code action} with {@code v} if non-null and non-blank */
    private static void nz(String v, java.util.function.Consumer<String> action) {
        if (v != null && !v.isBlank()) {
            action.accept(v);
        }
    }
}
