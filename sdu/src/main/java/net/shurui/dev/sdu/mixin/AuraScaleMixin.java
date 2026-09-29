package net.shurui.dev.sdu.mixin;

import com.dragonminez.client.render.effects.AuraRenderer;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.form.FormAuraConfig;
import net.shurui.dev.sdu.form.FormAuraData;
import net.shurui.dev.sdu.race.RaceAuraConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Per-form aura SIZE overrides. DMZ has no per-aura size field; getAuraScale returns [0]=width, [1]=height,
// [2]=depth. We multiply by the per-form width/height multipliers (default 1.0) on FormAuraData, keyed by
// active form/stack-form name (guarded by Config.enableAuraStacking). No form modifier -> fall back to the
// base RACE aura size from RaceAuraConfig (form OR race, never both).
// Client-side, remap=false; target method is private static.
@Mixin(value = AuraRenderer.class, remap = false)
public abstract class AuraScaleMixin {

    @Inject(method = "getAuraScale(Lcom/dragonminez/common/stats/StatsData;[F)[F",
            at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void sdu$scaleAura(StatsData stats, float[] in,
                                      CallbackInfoReturnable<float[]> cir) {
        if (!net.shurui.dev.sdu.Config.enableAuraStacking) {
            return; // shared safety switch (config/dmznpc-common.toml)
        }
        // guarded: worst case it just isn't resized, never crashes the render
        try {
            float[] ret = cir.getReturnValue();
            if (ret == null || ret.length < 2 || stats == null) {
                return;
            }
            Character ch = stats.getCharacter();
            if (ch == null) {
                return;
            }
            float wMul = 1.0f, hMul = 1.0f;
            if (ch.hasActiveForm()) {
                float[] m = mul(ch.getActiveFormData());
                wMul *= m[0];
                hMul *= m[1];
            }
            if (ch.hasActiveStackForm()) {
                float[] m = mul(ch.getActiveStackFormData());
                wMul *= m[0];
                hMul *= m[1];
            }
            // form modifier wins; only fall back to base RACE size when none applied (never race * form)
            if (wMul == 1.0f && hMul == 1.0f) {
                RaceAuraConfig.Size race = RaceAuraConfig.get(ch.getRaceName());
                wMul = race.width;
                hMul = race.height;
            }
            if (wMul == 1.0f && hMul == 1.0f) {
                return;
            }
            ret[0] *= wMul;
            ret[1] *= hMul;
            if (ret.length > 2) {
                ret[2] *= wMul; // depth tracks width so the billboard stays proportioned
            }
            cir.setReturnValue(ret);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] aura-size override skipped: {}", DmzNpc.MODID, t.toString());
        }
    }

    // per-form {width,height} multipliers (1.0 when absent)
    private static float[] mul(com.dragonminez.common.config.FormConfig.FormData form) {
        if (form == null) {
            return new float[]{1.0f, 1.0f};
        }
        FormAuraData data = FormAuraConfig.get(form.getName());
        if (data == null) {
            return new float[]{1.0f, 1.0f};
        }
        return new float[]{data.width, data.height};
    }
}
