package net.shurui.dev.sdu.mixin;

import com.dragonminez.client.render.hair.HairStyleResolver;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.hair.HairCodec;
import com.dragonminez.common.hair.HairStyleSlot;
import com.dragonminez.common.stats.character.Character;
import net.minecraft.world.entity.player.Player;
import net.shurui.dev.sdu.compat.dmz.HairCodeCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Per-form forced hair codes that are FULL SETS (Base+SSJ+SSJ2+SSJ3).
//
// 2.1 had two client bugs in DMZHairLayer.getHairForForm / getHairForStackForm, both fixed here then: a full-set code
// always showed its Base slot, and the code was re-decoded every frame (FPS 421 to 35). DragonMineZ 2.2 moved form
// hair to HairStyleResolver.styleForFormData and fixed the second one itself (it caches decodes), and it now picks the
// render slot from hairType. It still decodes a full set with HairCodec.fromCode, which returns only Base, so a form
// forcing a full set plus a hairType still shows Base. We keep only that half: for a full-set code we decode the SET
// (memoised in HairCodeCache) and, when the form defines hairType, return the matching slot. Single codes, and
// anything we cannot decode, fall through to DMZ unchanged.
//
// Client-only, remap=false, require=0. HairCodeCache.clear() runs on resource reload (ClientModBusEvents) and after
// config sync (ConfigSyncSeedMixin), so edits apply without relog.
@Mixin(value = HairStyleResolver.class, remap = false)
public abstract class DMZHairLayerForcedCodeMixin {

    @Inject(
            method = "styleForFormData(Lnet/minecraft/world/entity/player/Player;Lcom/dragonminez/common/stats/character/Character;Lcom/dragonminez/common/config/FormConfig$FormData;Lcom/dragonminez/client/render/hair/HairStyleResolver$StyleChoice;)Lcom/dragonminez/client/render/hair/HairStyleResolver$StyleChoice;",
            at = @At("HEAD"),
            cancellable = true,
            require = 0,
            remap = false)
    private static void sdu$fullSetForcedCode(Player player, Character character, FormConfig.FormData formData,
                                              HairStyleResolver.StyleChoice fallback,
                                              CallbackInfoReturnable<HairStyleResolver.StyleChoice> cir) {
        try {
            if (formData == null || !Boolean.TRUE.equals(formData.hasHairCodeOverride())) {
                return;
            }
            String code = formData.getForcedHairCode();
            if (!HairCodec.isFullSetCode(code)) {
                return; // single code: DMZ 2.2 handles it (and caches it) itself
            }
            CustomHair[] slots = HairCodeCache.decode(code);
            if (slots.length < 4) {
                return; // decode failed (cached sentinel): fall through to DMZ's own behaviour
            }
            boolean typed = Boolean.TRUE.equals(formData.hasDefinedHairType());
            int slot = typed ? HairCodeCache.slotForType(formData.getHairType()) : 0;
            CustomHair hair = slots[slot];
            if (hair == null) {
                return;
            }
            HairStyleSlot renderSlot = typed ? HairStyleSlot.byHairType(formData.getHairType()) : null;
            cir.setReturnValue(new HairStyleResolver.StyleChoice(hair, renderSlot != null ? renderSlot : HairStyleSlot.BASE));
        } catch (Throwable ignored) {
            // never break hair rendering; DMZ's own resolution runs
        }
    }
}
