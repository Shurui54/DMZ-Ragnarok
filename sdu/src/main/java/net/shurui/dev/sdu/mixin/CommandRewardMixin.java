package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.rewards.CommandReward;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Readable name for our TECHNIQUE reward in the quest tree. It's stored as a DMZ COMMAND reward running
// `sdu givetechnique %player% <id>` (DMZ has no KI_TECHNIQUE reward type), whose default description is a
// generic "command" label. For our commands we return the technique's lang name (technique.dragonminez.<id>)
// so the tree reads e.g. "Kamehame Ha". remap=false (DMZ class).
@Mixin(value = CommandReward.class, remap = false)
public abstract class CommandRewardMixin {

    @Shadow
    private String command;

    @Inject(method = "getDescription", at = @At("HEAD"), cancellable = true, remap = false)
    private void sdu$techniqueName(CallbackInfoReturnable<Component> cir) {
        String cmd = this.command;
        if (cmd != null && cmd.startsWith("sdu givetechnique")) {
            String[] parts = cmd.trim().split("\\s+");
            String id = parts[parts.length - 1];
            cir.setReturnValue(Component.translatable("technique.dragonminez." + id));
        }
    }
}
