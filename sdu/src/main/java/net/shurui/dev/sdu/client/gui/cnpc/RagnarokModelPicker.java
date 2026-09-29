package net.shurui.dev.sdu.client.gui.cnpc;

import com.goodbird.cnpcgeckoaddon.client.gui.GuiStringSelection;
import com.goodbird.cnpcgeckoaddon.data.CustomModelData;
import com.goodbird.cnpcgeckoaddon.mixin.IDataDisplay;
import net.minecraft.client.gui.screens.Screen;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.cnpc.CnpcGeckoBridge;
import net.shurui.dev.sdu.compat.cnpc.RagnarokModelCatalog;
import noppes.npcs.entity.EntityCustomNpc;
import noppes.npcs.entity.EntityNPCInterface;

import java.util.List;

/**
 * "Ragnarok" model picker: browse ragnarok characters by name and put one on a Custom NPC in one click.
 * Shared by both entry points (model editor's Entities tab, DMZ NPC tab) so there is one apply path.
 *
 * <p>A pick sets four things, because a geo alone is not a usable model here: these rigs do not use the
 * player-skin UV, so without their own texture they render the Steve skin stretched over the wrong map, and
 * without their baked box they keep the addon's default 0.7 x 2.0 hitbox whatever the model's height.
 * Animations come from DMZ's saga library ({@code RgNpcModel} plays them with the same one), so the bones the
 * models were retargeted onto are the bones being driven.
 */
public final class RagnarokModelPicker {

    /** DMZ's saga animation library. These models were retargeted onto exactly this rig. */
    private static final String ANIM = "dragonminez:animations/entity/sagas/saga_base.animation.json";
    private static final String IDLE_CLIP = "idle";
    private static final String WALK_CLIP = "walk";

    private RagnarokModelPicker() {
    }

    /**
     * Selection screen for {@code npc}, or {@code null} when there is nothing to offer (Shurui's Utilities
     * absent, or every entry key-gated on a keyless server). Callers hand the result to {@code setSubGui} and
     * skip the button when it is null.
     *
     * @param after run on the client thread after a successful apply, e.g. to refresh a button label
     */
    public static Screen build(Screen parent, EntityNPCInterface npc, Runnable after) {
        List<String> ids = RagnarokModelCatalog.availableIds();
        if (ids.isEmpty()) {
            return null;
        }
        return new GuiStringSelection(parent, "Select a Ragnarok model:", ids, id -> {
            apply(npc, id);
            if (after != null) {
                after.run();
            }
        });
    }

    /** True when there is at least one entry the current key state allows. */
    public static boolean hasEntries() {
        return !RagnarokModelCatalog.availableIds().isEmpty();
    }

    /**
     * Put entry {@code id} on {@code npc}: geo + its texture + baked collision box + saga clips. Best effort
     * like every bridge call here, so an addon change degrades to "nothing applied".
     */
    public static void apply(EntityNPCInterface npc, String id) {
        RagnarokModelCatalog.Entry entry = RagnarokModelCatalog.byId(id);
        if (entry == null || !(npc instanceof EntityCustomNpc customNpc)) {
            return;
        }
        try {
            CnpcGeckoBridge.applyDmzModel(customNpc, entry.geo(), ANIM,
                    IDLE_CLIP, WALK_CLIP, "", "", entry.texture());

            // box is baked per geo (see RgNpcModelSizes); without it a 2.8-block model keeps the addon's
            // default 2.0 and stands with its head through the ceiling of its own hitbox.
            CustomModelData data = ((IDataDisplay) customNpc.display).getCustomModelData();
            data.setWidth(entry.width());
            data.setHeight(entry.height());
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Could not apply ragnarok model '{}': {}", DmzNpc.MODID, id, t.toString());
        }
    }
}
