package net.shurui.shuruisutilities.compat.customnpcs;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.world.space.SpaceKeys;

/**
 * Hides the CustomNPCs client-side chat warning that fires on login inside our space dimensions:
 * "You logged into a dynamic dimension using a non-vanilla dimensionType, your game will think you are in
 * minecraft:overworld ... Please refrain from using multiworlds in the configuration!".
 *
 * <p>Our shuruisutilities:space and shuruisutilities:planet_surface dimensions genuinely need a custom
 * dimension_type (no skylight, custom effects, custom height), so that CustomNPCs check is inherently
 * tripped and cannot be avoided. The user has confirmed the actual symptom it warns about does not occur
 * (the sdu compass pip renders correctly in our dimensions), so the message is pure noise that scares
 * players.</p>
 *
 * <p>This is a text-matching {@link ClientChatReceivedEvent} filter rather than a mixin into CustomNPCs, for
 * two reasons. First, the warning is not present in the CustomNPCs build this workspace compiles/runs against
 * (customnpcs 1.20.1.20260227), so there is no method here to target with a compile-safe {@code @Inject}; the
 * emitter lives in whatever CustomNPCs build the connected server ships. Second, because it targets no
 * CustomNPCs class at all, this handler cannot crash when CustomNPCs is absent and keeps working across
 * CustomNPCs build drift. The trade-off is that the match is on message text: if CustomNPCs ever rewords or
 * translates the string, the substring below must be updated. We deliberately match only distinctive phrases
 * that are unlikely to collide with any other mod's chat.</p>
 *
 * <p>Gating: we only swallow the warning while the local player is actually inside one of OUR two space
 * dimensions. The warning text itself hardcodes "minecraft:overworld" and never names the offending
 * dimension, so we cannot scope by the message; instead we scope by the client's current dimension. A genuine
 * multiworld misconfiguration in any other dimension still reaches the operator. Behind the
 * {@link SUConfig#muteCnpcDimensionWarning} toggle (default true), so an operator can restore the warning.</p>
 *
 * <p>Client-only and on the FORGE bus, so the class never loads on a dedicated server.</p>
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CnpcDimensionWarningFilter
{
    // Distinctive fragments of the CustomNPCs warning. We require BOTH so an unrelated chat line that merely
    // mentions "dimensionType" or "multiworlds" on its own is not swallowed.
    private static final String MARKER_A = "non-vanilla dimensionType";
    private static final String MARKER_B = "multiworlds";

    private CnpcDimensionWarningFilter()
    {
    }

    @SubscribeEvent
    public static void onClientChat(ClientChatReceivedEvent event)
    {
        if (!SUConfig.muteCnpcDimensionWarning)
            return;

        // Only suppress while the local player is inside one of our custom-dimensionType dimensions; a real
        // multiworld misconfiguration elsewhere still surfaces.
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null)
            return;

        ResourceKey<Level> dim = player.level().dimension();
        if (!dim.equals(SpaceKeys.SPACE) && !dim.equals(SpaceKeys.SURFACE))
            return;

        String text = event.getMessage().getString();
        if (text.contains(MARKER_A) && text.contains(MARKER_B))
            event.setCanceled(true);
    }
}
