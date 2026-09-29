package net.shurui.shuruisutilities.client.hud;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Maps the SET of the radar a player holds to its custom GUI background, or null to fall through to DMZ's stock dial.
 * The ONE place per-set selection lives: both draw paths call {@link #forHeldRadar(Player)}. Path (a) is DMZ's
 * {@code RadarRenderEvent.renderRadar} (overworld, Namek), its hardcoded blit redirected in {@code MixinDmzRadarDraw};
 * path (b) is SU's {@link net.shurui.shuruisutilities.client.space.SuRadarHud} (space, planet_surface).
 *
 * <p>Resolved from the HELD item, not the radar definition, because DMZ blits its own texture in {@code renderRadar}
 * and ignores each radar's {@code radar_background_texture} field. Black Star, Earth and Namek return null: Black Star
 * has no art, Earth/Namek keep DMZ's defaults.
 */
public final class RadarBackgrounds
{
    private static final ResourceLocation SUPER =
            new ResourceLocation("dmz_ragnarok", "textures/gui/super_radar.png");
    private static final ResourceLocation CERULEAN =
            new ResourceLocation("dmz_ragnarok", "textures/gui/cerulean_radar.png");
    private static final ResourceLocation SHADOW =
            new ResourceLocation("dmz_ragnarok", "textures/gui/shadow_dragon_radar.png");

    private RadarBackgrounds()
    {
    }

    /**
     * Custom dial for whichever radar the player holds (main hand first, then off, matching DMZ's resolution order),
     * or null to fall through. Item path only, so no DragonMineZ class loads here.
     */
    public static ResourceLocation forHeldRadar(Player player)
    {
        if (player == null)
        {
            return null;
        }
        ResourceLocation main = forStack(player.getMainHandItem());
        return main != null ? main : forStack(player.getOffhandItem());
    }

    private static ResourceLocation forStack(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
        {
            return null;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null)
        {
            return null;
        }
        String path = id.getPath();
        if (!path.contains("radar"))
        {
            return null;
        }
        if (path.contains("super"))
        {
            return SUPER;
        }
        if (path.contains("cerulean"))
        {
            return CERULEAN;
        }
        if (path.contains("shadow"))
        {
            return SHADOW;
        }
        // Namek radar wears the shadow-dragon dial while the Namek set is defiled. Before the Earth branch since
        // "namek" is a distinct set; client flag fails closed.
        if (path.contains("namek")
                && net.shurui.shuruisutilities.corrupted.client.DefiledBallsClient.isNamekDefiled())
        {
            return SHADOW;
        }
        // Earth radar is DMZ's own "dball_radar" (every other radar carries a prefix). Wears the shadow-dragon dial
        // while the Earth balls are defiled; flag fails closed, so this stays null before the sync arrives.
        if (path.equals("dball_radar")
                && net.shurui.shuruisutilities.corrupted.client.DefiledBallsClient.isEarthDefiled())
        {
            return SHADOW;
        }
        // Black Star, Earth (undefiled), Namek (undefiled) and anything else keep DMZ's stock dial.
        return null;
    }
}
