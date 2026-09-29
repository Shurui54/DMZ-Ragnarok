package net.shurui.dev.sdu.mixin.cnpc;

import com.goodbird.cnpcgeckoaddon.utils.NpcTextureUtils;
import net.minecraft.resources.ResourceLocation;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.client.renderer.FighterSkins;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.io.File;

// PRIMARY URL-skin fix, ahead of the getRenderType belt-and-braces hook in RenderCustomModelMixin.
//
// Why here. The addon resolves a URL skin (skinType 2) far earlier than any render: its own
// MixinEntityUtil.copy runs NpcTextureUtils.getNpcTexture(npc) when client NPC data is copied onto the
// EntityCustomModel render entity, and getNpcTexture (for skinType 2) builds the RL customnpcs:skins/<hash>,
// stores it on the npc, then calls this loadSkin(file, loc, url, flag). loadSkin's FIRST line is
// TextureManager.getTexture(loc) with the SINGLE-arg overload, which AUTO-REGISTERS a SimpleTexture for a
// customnpcs:skins/<hash> path that no resource pack contains: that is the FileNotFoundException in the log,
// and because getTexture then returns non-null the addon SKIPS its own ImageDownloadAlt. So the broken
// SimpleTexture is registered once and never replaced, and the NPC is Steve for the rest of the session, not
// merely until a later render. Hooking getRenderType could only ever run after that damage was already done.
//
// Injecting at loadSkin HEAD registers our real (downloaded, normalized) texture under loc BEFORE the addon's
// getTexture call, so the addon's own getTexture then finds ours and leaves it alone, and no broken
// SimpleTexture is ever created for loc. loc and url arrive here as exact parameters, so there is nothing to
// recompute. installUrlTexture is idempotent and self-healing per RL, so a repeat resolve is cheap.
//
// remap=false (addon-owned class and method). require=0 to degrade, not crash, on a future addon reshape.
// loadSkin is private static, so the handler is static too; parameters mirror the target exactly
// (File, ResourceLocation, String, boolean) or this would be an apply-phase crash. Gated with the rest of
// sdu.cnpc.mixins.json by CnpcMixinPlugin, so it skips when the CNPC stack is absent. NpcTextureUtils is a
// render-time client util, loaded long after sdu's mixin config is registered, so the transform applies.
@Mixin(value = NpcTextureUtils.class, remap = false)
public abstract class NpcTextureUtilsMixin {

    @Inject(
            method = "loadSkin(Ljava/io/File;Lnet/minecraft/resources/ResourceLocation;Ljava/lang/String;Z)V",
            at = @At("HEAD"),
            remap = false,
            require = 0
    )
    private static void sdu$installUrlSkin(File file, ResourceLocation loc, String url, boolean flag, CallbackInfo ci) {
        if (loc == null || url == null) {
            return;
        }
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return;
        }
        try {
            FighterSkins.installUrlTexture(loc, trimmed);
        } catch (Throwable t) {
            // never throw out of the addon's resolve path; the belt-and-braces getRenderType hook remains
            DmzNpc.LOGGER.warn("[sdu-skin] loadSkin-hook install failed loc={} url='{}': {}", loc, trimmed, t.toString());
        }
    }
}
