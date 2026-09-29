package net.shurui.dev.sdu.mixin;

import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.client.DmzAssets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Real icon (and optional tint) for custom form TYPES in DMZ's radial "X" menu.
// iconForFormType(String) maps a few keywords to stock icons, else tries
// dragonminez:textures/gui/radial/<type>.png and falls back to an unshipped PLACEHOLDER, so a new
// custom formType renders missing. Two RETURN injectors:
//   iconForFormType: if DmzAssets has an iconBase, or DMZ returned the placeholder, override with
//     dragonminez:textures/gui/radial/<iconBase>.png (default superforms).
//   tintOf(FormData) gets no type string, but FormSelectNode's ctor calls it right after
//     iconForFormType(formType), so we stash the last type in a ThreadLocal and read it back.
//     non--1 tint override wins; otherwise DMZ's aura-colour tint stands.
// Both require=0, remap=false, all guarded so a mismatch degrades to DMZ. Client-only.
@Mixin(targets = "com.dragonminez.client.gui.radial.AbstractRadialNode", remap = false)
public abstract class AbstractRadialNodeMixin {

    // last type from iconForFormType, so the adjacent tintOf (FormData only) can recover it
    @Unique
    private static final ThreadLocal<String> sdu$lastFormType = new ThreadLocal<>();

    @Inject(method = "iconForFormType", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void sdu$overrideIcon(String type, CallbackInfoReturnable<ResourceLocation> cir) {
        sdu$lastFormType.set(type);
        if (type == null || type.isEmpty()) {
            return;
        }
        try {
            ResourceLocation current = cir.getReturnValue();
            boolean isPlaceholder = current != null && "textures/gui/radial/placeholder.png".equals(current.getPath());
            String iconBase = DmzAssets.formTypeIcon(type);
            if (iconBase != null) {
                cir.setReturnValue(sdu$radialIcon(iconBase)); // admin picked an icon
            } else if (isPlaceholder) {
                // no registration and DMZ gave the broken placeholder: stock default so it's never missing
                cir.setReturnValue(sdu$radialIcon(net.shurui.dev.sdu.form.FormTypeMeta.DEFAULT_ICON));
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "tintOf", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private static void sdu$overrideTint(com.dragonminez.common.config.FormConfig.FormData formData,
                                         CallbackInfoReturnable<Integer> cir) {
        try {
            String type = sdu$lastFormType.get();
            if (type == null || type.isEmpty()) {
                return;
            }
            int tint = DmzAssets.formTypeTint(type);
            if (tint >= 0) {
                cir.setReturnValue(tint);
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private static ResourceLocation sdu$radialIcon(String name) {
        return ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/gui/radial/"
                + net.shurui.dev.sdu.form.FormTypeMeta.normalizeIcon(name) + ".png");
    }
}
