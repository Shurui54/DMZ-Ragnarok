package net.shurui.dev.sdu.compat.cnpc;

import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.shurui.dev.sdu.DmzNpc;
import noppes.npcs.entity.EntityCustomNpc;

/**
 * Puts every Custom NPC onto SDU's own player-shaped GeckoLib model as it loads.
 *
 * <p>The model cannot be switched by writing NBT into the entity file (an earlier attempt did that). Three
 * things must happen and only one is a stored field:
 * <ol>
 *   <li>{@code modelData.setEntity(...)} makes ModelData REBUILD its render proxy as the addon's
 *       EntityCustomModel. Writing the stored {@code EntityName} skips the rebuild and the NPC renders as
 *       nothing at all: every civilian went invisible on the live server.</li>
 *   <li>The addon's {@link com.goodbird.cnpcgeckoaddon.data.CustomModelData} must be populated through its own
 *       setters so the addon sees the change.</li>
 *   <li>{@code updateClient()} must push the result, or the server knows and no client does.</li>
 * </ol>
 * {@link CnpcGeckoBridge#applyDmzModel} does all three, so this class only decides WHICH model and WHEN.
 *
 * <p>Wide or slim is chosen from the skin the NPC already wears, not a second stored field that could drift. A
 * texture path marked slim gets {@code sdu_slim}; everything else {@code sdu_wide}. Both declare a 64x64
 * texture, so an ordinary Minecraft skin maps onto them unchanged.
 *
 * <p>Server side only, gated on {@link DmzCnpcCompat#geckoAddonAvailable()} so the addon's classes are never
 * touched when absent, and wrapped so a CNPC/addon change degrades to "model not applied". An NPC already
 * carrying a custom model is left alone.
 */
public final class CnpcCitizenModels {

    /** SDU's player-shaped models. Bones match what DMZ's saga animation drives, and both are 64x64. */
    private static final String GEO_WIDE = "dmz_ragnarok:geo/entity/sdu_wide.geo.json";
    private static final String GEO_SLIM = "dmz_ragnarok:geo/entity/sdu_slim.geo.json";

    /**
     * What {@code CustomModelData} holds before anyone picks a model: the Gecko addon's placeholder geo, set in
     * its constructor. Not blank, and the ONLY state this class may write over on an NPC it has not already
     * dressed. See {@link #shouldApply}.
     */
    private static final String ADDON_DEFAULT_GEO = "cnpcgeckoaddon:geo/geo_npc.geo.json";

    /**
     * DragonMineZ's saga animation library, using the clips that FIT this rig.
     *
     * <p>saga_base carries 61 clips, not interchangeable. The numbered ones (idle4, walk4) are authored for
     * DMZ's full fighter skeleton and drive chest, shoulders, forearms, underlegs, feet, wings and capes; on a
     * simple biped they put shoulder rotations on whole arms and waist rotations on the whole body, folding
     * every NPC over double.
     *
     * <p>The UNNUMBERED clips match. walk drives exactly head, both arms, both legs, root and waist, which is
     * this rig. idle also references capa/capa2 (cape bones); a model without them ignores those channels, so
     * the body still animates correctly.
     */
    private static final String ANIM = "dragonminez:animations/entity/sagas/saga_base.animation.json";
    private static final String IDLE_CLIP = "idle";
    private static final String WALK_CLIP = "walk";

    /** Marks a skin drawn for the 3px (slim) arm. */
    private static final String SLIM_MARKER = "slim";

    /** Sweep cadence: 20 ticks is a second, so this is roughly every 15. */
    private static final int SWEEP_INTERVAL_TICKS = 300;

    private CnpcCitizenModels() {
    }

    @SubscribeEvent
    public static void onEntityJoin(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!(entity instanceof EntityCustomNpc npc)) {
            return;
        }
        if (!DmzCnpcCompat.geckoAddonAvailable()) {
            return;
        }
        if (shouldApply(npc)) {
            apply(npc);
        }
    }

    /**
     * Sweep for Custom NPCs the join event never saw.
     *
     * <p>{@link EntityJoinLevelEvent} fires only as an entity is ADDED to a level. An NPC in a chunk already
     * loaded when this handler registered is never announced, so it keeps the plain humanoid and vanilla arm
     * swing while neighbours animate. This catches those.
     *
     * <p>Runs a few times a minute, skipping any NPC that already carries a custom model.
     */
    private static int sweepTimer;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++sweepTimer < SWEEP_INTERVAL_TICKS) {
            return;
        }
        sweepTimer = 0;
        if (!DmzCnpcCompat.geckoAddonAvailable()) {
            return;
        }
        try {
            for (ServerLevel level : event.getServer().getAllLevels()) {
                for (Entity e : level.getAllEntities()) {
                    if (e instanceof EntityCustomNpc npc && shouldApply(npc)) {
                        apply(npc);
                    }
                }
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Custom NPC sweep failed: {}", DmzNpc.MODID, t.toString());
        }
    }

    /**
     * Whether this class should write a model onto {@code npc}. Two cases only. An NPC never given a model (the
     * Gecko addon's untouched constructor default, or nothing) gets the citizen model. An NPC already wearing
     * one of OUR two gets re-evaluated, so a citizen whose skin was swapped for a slim one moves onto the slim
     * rig. Anything else is somebody's decision and is never touched.
     *
     * <p>That last clause is the fix for models resetting themselves. The question used to be the narrower "is
     * it wearing one of ours", making every OTHER model a candidate: pick a ragnarok character in the editor and
     * the join handler overwrote it on the next chunk load, the sweep again fifteen seconds later. The on-disk
     * NBT was correct throughout and overwritten after load, which is why nothing looked wrong from outside.
     */
    private static boolean shouldApply(EntityCustomNpc npc) {
        try {
            String cur = ((com.goodbird.cnpcgeckoaddon.mixin.IDataDisplay) npc.display)
                    .getCustomModelData().getModel();
            if (cur == null || cur.isBlank() || cur.equals(ADDON_DEFAULT_GEO)) {
                return true;
            }
            if (cur.equals(GEO_WIDE) || cur.equals(GEO_SLIM)) {
                return !cur.equals(wantedGeo(npc));
            }
            return false;
        } catch (Throwable t) {
            // could not read it, so leave it alone: not touching an NPC is always the safe direction.
            return false;
        }
    }

    /** Which of our two rigs this NPC's current skin calls for. */
    private static String wantedGeo(EntityCustomNpc npc) {
        String texture = npc.display.getSkinTexture();  // setSkinTexture's counterpart
        if (texture == null) {
            texture = "";
        }
        return texture.toLowerCase().contains(SLIM_MARKER) ? GEO_SLIM : GEO_WIDE;
    }

    // Applying is best effort: a Custom NPC that fails to take the model must still load and behave.
    private static void apply(EntityCustomNpc npc) {
        try {
            CnpcGeckoBridge.applyDmzModel(npc, wantedGeo(npc), ANIM,
                    IDLE_CLIP, WALK_CLIP, "", "", null);

            npc.updateClient();
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not put Custom NPC {} on an SDU model: {}",
                    DmzNpc.MODID, npc.getId(), t.toString());
        }
    }
}
