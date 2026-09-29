package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.events.DMZEvent;
import com.dragonminez.common.stats.character.Resources;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.shurui.dev.sdu.util.TpMath;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.Inject;

// Lift the per-reward Integer.MAX_VALUE cap for quest rewards ONLY (bug #970/#978).
//
// DMZ's addTrainingPoints(float, boolean) builds a TPGainEvent whose gain is an int and, after every listener
// has run, writes setTrainingPoints(oldValue + (float) event.getTpGain()). Because tpGain is an int, and DMZ's
// own calculateTPGain and each suite multiplier narrow to int, a story-quest reward worth tens of billions is
// pinned at Integer.MAX_VALUE (2,147,483,647), which the float write then rounds up to 2^31 = 2,147,483,648.
// Resources.trainingPoints is a float (ceiling Float.MAX_VALUE), so the total can legitimately hold billions;
// only the per-gain event was too narrow.
//
// TpsRewardOverflowMixin arms TpMath with the TRUE base*difficulty gain just before a TPSReward calls this
// method. When armed, we run the SAME event dispatch DMZ runs (so every listener still fires and can multiply),
// but we carry the value in a parallel double accumulator that DMZ's calculateTPGain (StatsDataTpSourceWide) and
// the suite listeners (via TpMath.scaleGain) multiply in double space, and we write that double as one float.
// The int event still carries the clamped mirror for foreign listeners and the client sync. Quest rewards call
// this with shareWithParty=false, so DMZ's party/fusion share (which would post nested gains) never runs here.
//
// Every OTHER TP source (kills, mining, travel, crafting, training minigames) leaves TpMath un-armed, so this
// injector returns immediately and DMZ's original method runs untouched: no behaviour change off the quest path.
//
// remap=false: Resources, the event and the fields are all DMZ's own. require=0 per house rule for DMZ targets.
@Mixin(value = Resources.class, remap = false)
public abstract class ResourcesTrainingPointsWideMixin {

    @Shadow private Player player;

    @Shadow public abstract void setTrainingPoints(float points);

    @Shadow public abstract float getTrainingPoints();

    @Inject(method = "addTrainingPoints(FZ)V", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void sdu$wideQuestReward(float amount, boolean shareWithParty, CallbackInfo ci) {
        if (!TpMath.isArmed()) {
            return; // not a quest reward: leave DMZ's plain int path exactly as it is
        }
        double base = TpMath.consumeArmed();
        if (base <= 0.0 || this.player == null) {
            // Mirror DMZ's own guard: no event, just add (a non-positive base only ever removes/keeps TP).
            this.setTrainingPoints(this.getTrainingPoints() + (float) base);
            ci.cancel();
            return;
        }
        float oldValue = this.getTrainingPoints();
        TpMath.beginWide(base);
        try {
            DMZEvent.TPGainEvent event =
                    new DMZEvent.TPGainEvent(this.player, (int) oldValue, TpMath.clampToInt(base), shareWithParty);
            if (!MinecraftForge.EVENT_BUS.post(event)) {
                // A listener that explicitly zeroed or negated the gain (e.g. a cancel) must be honoured; only
                // when the gain survived as positive do we write the uncapped double accumulator.
                double gain = event.getTpGain() <= 0 ? event.getTpGain() : TpMath.currentWide();
                this.setTrainingPoints(oldValue + (float) gain);
            }
        } finally {
            TpMath.endWide();
        }
        ci.cancel();
    }
}
