package net.shurui.dev.sdu.mixin;

import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Dimension-equivalence for quest DIMENSION requirements and objectives (bug 678c). DMZ's
 * {@code QuestLocationHelper.isInDimension} does an EXACT resource-location equals, so a saga step keyed on
 * {@code dragonminez:namek} is not satisfied by the open-world Namek island {@code dmz_ragnarok:namekow}, and a
 * step keyed on {@code dragonminez:sacredkaiplanet} is not satisfied by its OW island
 * {@code dmz_ragnarok:kaiow}. The island dims ARE those planets on the OW shards, so we treat each pair as one
 * place.
 *
 * <p>We only widen a NEGATIVE result: if DMZ already matched, we leave it alone. If it did not, and the player's
 * actual dimension is the OW/planet counterpart of the requested one, we return true. Equivalence is symmetric,
 * so a step authored for either id is satisfied from either dimension. Everything fails to the original result on
 * any surprise, so a mismatch never changes DMZ behaviour.
 *
 * <p>{@code remap = false}: the target is a DMZ class with official names in the prod jar. {@code require = 0} so
 * a mapping drift no-ops instead of crashing; a green build does not prove the binding, so launch-test it.
 */
@Mixin(value = com.dragonminez.common.quest.QuestLocationHelper.class, remap = false)
public abstract class QuestLocationHelperMixin {

    private static final String NAMEK = "dragonminez:namek";
    private static final String NAMEKOW = "dmz_ragnarok:namekow";
    private static final String SACRED_KAI = "dragonminez:sacredkaiplanet";
    private static final String KAIOW = "dmz_ragnarok:kaiow";

    @Inject(
            method = "isInDimension(Lnet/minecraft/world/level/Level;Ljava/lang/String;)Z",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            remap = false)
    private static void sdu$dimensionEquivalence(Level level, String targetDimension,
                                                 CallbackInfoReturnable<Boolean> cir) {
        try {
            if (Boolean.TRUE.equals(cir.getReturnValue()) || level == null || targetDimension == null) {
                return; // DMZ already matched (or nothing to test): leave it
            }
            String equivalent = counterpart(targetDimension.trim());
            if (equivalent == null) {
                return; // the requested dim has no OW/planet twin
            }
            String here = level.dimension().location().toString();
            if (equivalent.equals(here)) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
            // fall through to DMZ's own result
        }
    }

    /** The paired dimension id for one half of an equivalence, or null when the id is not part of a pair. */
    private static String counterpart(String dimensionId) {
        return switch (dimensionId) {
            case NAMEK -> NAMEKOW;
            case NAMEKOW -> NAMEK;
            case SACRED_KAI -> KAIOW;
            case KAIOW -> SACRED_KAI;
            default -> null;
        };
    }
}
