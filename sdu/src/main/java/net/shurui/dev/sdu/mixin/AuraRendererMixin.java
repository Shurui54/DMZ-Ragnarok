package net.shurui.dev.sdu.mixin;

import com.dragonminez.client.render.effects.AuraRenderer;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;
import net.minecraft.world.entity.player.Player;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.form.FormAuraConfig;
import net.shurui.dev.sdu.form.FormAuraData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

// Multi-layered auras. DMZ's getAuraLayers returns one layer per active form, so we append extra layers
// (from FormAuraConfig, keyed by active form/stack-form name) to stack auras (e.g. white + silver electric).
// Client-side, remap=false. Config is the client's own config/sdu/form_auras.json; a dedicated server's
// clients need that file too (same distribution model as the resource pack).
@Mixin(value = AuraRenderer.class, remap = false)
public abstract class AuraRendererMixin {

    @Inject(method = "getAuraLayers(Lnet/minecraft/world/entity/player/Player;Lcom/dragonminez/common/stats/StatsData;F)Ljava/util/List;",
            at = @At("RETURN"), cancellable = true, remap = false, require = 0)
    private static void sdu$appendExtraAuras(Player player, StatsData stats, float partialTick,
                                             CallbackInfoReturnable<List<AuraRenderer.AuraLayer>> cir) {
        if (!net.shurui.dev.sdu.Config.enableAuraStacking) {
            return; // safety switch (config/dmznpc-common.toml)
        }
        // guarded: worst case it just doesn't stack, never crashes the render
        try {
            List<AuraRenderer.AuraLayer> result = cir.getReturnValue();
            if (result == null || stats == null) {
                return;
            }
            List<AuraRenderer.AuraLayer> extra = new ArrayList<>();
            Character ch = stats.getCharacter();
            if (ch == null) {
                return;
            }
            if (ch.hasActiveForm()) {
                collect(ch.getActiveFormData(), extra);
            }
            if (ch.hasActiveStackForm()) {
                collect(ch.getActiveStackFormData(), extra);
            }
            if (extra.isEmpty()) {
                return;
            }
            // DMZ scales each layer by id (radius = 1 + layerId*0.15), so two layers sharing an id draw
            // coincident. Give every extra a distinct free slot (0..6, like DMZ's putShifting) so the stack
            // renders as separate rings, then re-sort by layerId: DMZ appended us after its own sort and
            // treats get(last) as the top layer, so the merged list must stay sorted to match that contract.
            List<AuraRenderer.AuraLayer> merged = new ArrayList<>(result);
            java.util.Set<Integer> used = new java.util.HashSet<>();
            for (AuraRenderer.AuraLayer l : merged) {
                used.add(l.layerId);
            }
            for (AuraRenderer.AuraLayer x : extra) {
                int id = Math.max(0, Math.min(6, x.layerId));
                while (used.contains(id) && id < 6) {
                    id++;
                }
                x.layerId = id;
                used.add(id);
                merged.add(x);
            }
            merged.sort(java.util.Comparator.comparingInt(l -> l.layerId));
            cir.setReturnValue(merged);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] aura-stacking skipped: {}", DmzNpc.MODID, t.toString());
        }
    }

    private static void collect(com.dragonminez.common.config.FormConfig.FormData form, List<AuraRenderer.AuraLayer> out) {
        if (form == null) {
            return;
        }
        FormAuraData data = FormAuraConfig.get(form.getName());
        for (FormAuraData.Layer l : data.layers) {
            if (l.type == null || l.type.isBlank()) {
                continue;
            }
            // 4-arg ctor: alpha=1.0 explicit (DMZ 2.1.1 added the alpha field; don't rely on the 3-arg ctor
            // delegating), and clamp the layer slot to DMZ's valid 0..6 range
            out.add(new AuraRenderer.AuraLayer(l.type, Math.max(0, Math.min(6, l.layer)), hexToRgb(l.color), 1.0f));
        }
    }

    /** "#RRGGBB" -> {r,g,b} in 0..1; white when blank/invalid. */
    private static float[] hexToRgb(String hex) {
        if (hex != null) {
            String s = hex.trim();
            if (s.startsWith("#")) {
                s = s.substring(1);
            }
            if (s.length() == 6) {
                try {
                    int rgb = Integer.parseInt(s, 16);
                    return new float[]{((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new float[]{1f, 1f, 1f};
    }
}
