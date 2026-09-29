package net.shurui.dev.sdu.mixin.cnpc;

import com.goodbird.cnpcgeckoaddon.mixin.IDataDisplay;
import net.minecraft.client.gui.screens.Screen;
import net.shurui.dev.sdu.client.gui.cnpc.DmzNpcSubGui;
import net.shurui.dev.sdu.client.gui.cnpc.RagnarokModelPicker;
import noppes.npcs.client.gui.model.GuiCreationEntities;
import noppes.npcs.client.gui.model.GuiCreationScreenInterface;
import noppes.npcs.shared.client.gui.components.GuiButtonNop;
import noppes.npcs.shared.client.gui.listeners.IGuiInterface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Injects a "DMZ NPC" button into the Custom NPCs model editor. The editor GUI has no public tab-registration
// API, so we mirror the CNPC-Gecko-Addon's approach: inject at GuiCreationEntities#init TAIL and add a button
// via the inherited addButton/setSubGui helpers (available because this mixin extends
// GuiCreationScreenInterface, the editor's real superclass). Button opens DmzNpcSubGui.
//
// Only applied when the CustomNPCs + Gecko stack is present (CnpcMixinPlugin guards sdu.cnpc.mixins.json).
// Remap left on (default): init descends from vanilla Screen and is SRG-obf in a shipped jar; the inherited
// addButton/setSubGui calls in the body are plain bytecode to Custom NPCs methods, which keep their names.
@Mixin(GuiCreationEntities.class)
public abstract class GuiCreationEntitiesTabMixin extends GuiCreationScreenInterface {

    // high ids to avoid clashing with the editor's / addon's own button ids
    private static final int SDU_DMZ_TAB_BUTTON = 63900;
    private static final int SDU_RAGNAROK_BUTTON = 63901;

    // the Gecko addon's own "Model:" button, whose label we refresh after a ragnarok pick so the row does not
    // keep showing the model that was replaced. Its id is fixed in the addon's GuiCreationEntities mixin.
    private static final int ADDON_MODEL_BUTTON = 202;

    // never executed (mixins don't instantiate), but javac needs a super() call to the real ctor
    private GuiCreationEntitiesTabMixin() {
        super(null);
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void sdu$addDmzNpcTab(CallbackInfo ci) {
        // The row the "DMZ NPC" button used to fill on its own is split so the ragnarok picker sits on the same
        // screen as the addon's raw "Model:" list rather than one tab deeper: that list is every baked geo of
        // every mod, by file path, which is not a place anyone finds a character in.
        this.addButton(new GuiButtonNop((IGuiInterface) this, SDU_DMZ_TAB_BUTTON,
                this.guiLeft + 124, this.guiTop + 82, 92, 20, "DMZ NPC",
                b -> this.setSubGui((Screen) new DmzNpcSubGui(this.npc))));

        // Absent when Shurui's Utilities is not installed, or when every entry is key-gated and this server has
        // no key: then there is nothing to browse and the button would only open an empty list.
        if (RagnarokModelPicker.hasEntries()) {
            this.addButton(new GuiButtonNop((IGuiInterface) this, SDU_RAGNAROK_BUTTON,
                    this.guiLeft + 219, this.guiTop + 82, 92, 20, "Ragnarok",
                    b -> sdu$openRagnarokPicker()));
        }
    }

    private void sdu$openRagnarokPicker() {
        Screen picker = RagnarokModelPicker.build(this, this.npc, this::sdu$refreshModelLabel);
        if (picker != null) {
            this.setSubGui(picker);
        }
    }

    private void sdu$refreshModelLabel() {
        try {
            GuiButtonNop model = this.getButton(ADDON_MODEL_BUTTON);
            if (model != null) {
                String geo = ((IDataDisplay) this.npc.display).getCustomModelData().getModel();
                int slash = geo == null ? -1 : geo.lastIndexOf('/');
                model.setDisplayText(slash >= 0 && slash < geo.length() - 1 ? geo.substring(slash + 1) : geo);
            }
        } catch (Throwable t) {
            // cosmetic only: the model itself is already applied, the row just keeps its old caption
        }
    }
}
