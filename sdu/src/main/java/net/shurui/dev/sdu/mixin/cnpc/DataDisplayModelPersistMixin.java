package net.shurui.dev.sdu.mixin.cnpc;

import com.goodbird.cnpcgeckoaddon.data.CustomModelData;
import com.goodbird.cnpcgeckoaddon.mixin.IDataDisplay;
import net.minecraft.nbt.CompoundTag;
import noppes.npcs.entity.data.DataDisplay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Makes a Custom NPC's chosen GeckoLib model survive being saved, closing the "DMZ model turns into a Steve
// after a chunk reload" report.
//
// The failure, verified from the CNPC-Gecko-Addon bytecode. The addon writes its CustomModelData block into
// the NPC's display NBT from an @Inject at the HEAD of DataDisplay.save, but only when hasCustomModel() is
// true, which is exactly "modelData.getEntity(npc) instanceof EntityCustomModel", i.e. the NPC is flipped onto
// the addon's render proxy at that instant. Its counterpart read (@Inject at readToNBT HEAD) is NOT gated and,
// worse, its CustomModelData.readFromNBT returns immediately when the "Model" key is absent, leaving the
// fields at the constructor default "cnpcgeckoaddon:geo/geo_npc.geo.json". So the moment an NPC is saved while
// its ModelData entity is not the proxy, the whole block is dropped, and on the next load getModel() reads back
// as that addon default. CnpcCitizenModels then dresses a default-model NPC in sdu_wide: the Steve.
//
// The model STRING itself is reliable even when the flip is not: the addon's ungated readToNBT copies it into
// the server-side CustomModelData whenever the editor's save round-trip delivers it, and it stays in memory for
// the whole session (which is why the NPC renders correctly until it is unloaded). The only thing that fails is
// persistence, because the addon refuses to WRITE it without the proxy flip. This is why the server-side
// repair added in 1.1.133 could not help: once the first gated save dropped the block, the very next load reset
// getModel() to the default, so the repair (which keys on getModel()) had nothing left to recognise.
//
// The fix, at the write. If getModel() holds a real model (not blank, not the addon's placeholder default) and
// the addon did not write its block this save (no "Model" key on the returned tag, meaning hasCustomModel() was
// false), write the block ourselves. That keeps the model on disk and in every sync regardless of the flip
// state, so on the next load getModel() is real again and CnpcCitizenModels.repairUnflippedModel can put the
// NPC back on the proxy, which makes hasCustomModel() true and every save after that correct. When the addon
// already wrote the block (proxy flip present) the "Model" key is there and we do nothing, so this never
// double-writes and never fights the addon's own injector for order.
//
// remap = false: DataDisplay.save is a Custom NPCs method, not a vanilla one. Runs both sides, because the loss
// is a server chunk-save and the block also has to ride the server-to-client sync (which goes through the same
// save). Guarded to apply only with the CustomNPCs + Gecko stack present by CnpcMixinPlugin.
@Mixin(value = DataDisplay.class, remap = false)
public class DataDisplayModelPersistMixin {

    // The Gecko addon's CustomModelData constructor default. A model equal to this means "no model chosen",
    // and must be treated the same as blank so we never persist a placeholder as if it were a real pick.
    private static final String SDU$ADDON_DEFAULT_GEO = "cnpcgeckoaddon:geo/geo_npc.geo.json";

    /**
     * Write the chosen model when the addon's own save declined to.
     *
     * <p>Silent on every path but a throw. This runs once per NPC display save, which on a populated world is
     * thousands of lines a minute: it carried diagnostics while the reverting-model cause was being found
     * (2026-09-01, the destructive {@code repairUnflippedModel} sweep) and they are gone now that it is.
     */
    @Inject(method = "save", at = @At("RETURN"))
    private void sdu$persistCustomModel(CompoundTag tag, CallbackInfoReturnable<CompoundTag> cir) {
        try {
            CompoundTag out = cir.getReturnValue();
            if (out == null) {
                return;
            }
            // Present already means the addon wrote it this save (hasCustomModel() was true): leave it alone.
            if (out.contains("Model")) {
                return;
            }
            CustomModelData model = ((IDataDisplay) (Object) this).getCustomModelData();
            if (model == null) {
                return;
            }
            String geo = model.getModel();
            if (geo == null || geo.isBlank() || geo.equals(SDU$ADDON_DEFAULT_GEO)) {
                return;
            }
            // A genuine model the addon refused to persist because the NPC was not on the proxy. Write the same
            // flat keys the addon's own CustomModelData.writeToNBT does, so its ungated readToNBT reads it back
            // unchanged on load.
            model.writeToNBT(out);
        } catch (Throwable t) {
            net.shurui.dev.sdu.DmzNpc.LOGGER.warn("[cnpc] Could not persist a Custom NPC model, leaving the "
                    + "addon's save untouched: {}", t.toString());
        }
    }
}
