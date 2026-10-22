package net.shurui.shuruisutilities.client.ocarina;

import java.util.IdentityHashMap;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import software.bernie.geckolib.core.animatable.model.CoreGeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * The ocarina hold: both hands brought to the mouth, on top of DragonMineZ's meditation pose.
 *
 * <p>The server plays DMZ's own {@code base.meditation} clip on the player (the same KI_ANIMATION packet DMZ's
 * training uses), which folds the legs and settles the body. Its arms rest in the lap, so the arms are set here, at
 * the tail of {@code DMZPlayerModel}'s per-frame posing: raised forward and turned in so the hands meet just under
 * the face with the instrument between them.
 *
 * <p>Who is playing is read from what every client already sees: the instrument the server puts in the main hand
 * (tagged {@code su_ocarina_instrument}). For the local player the open ocarina menu or a running song counts too,
 * so the pose holds even when a full inventory meant the song is mimed with no prop.
 *
 * <p>GeckoLib leaves a bone it was told about flagged, and the next frame skips resetting it; the bones are also
 * shared by every player drawn with the model. So every write is remembered and taken back at the start of the next
 * call unless an animation already replaced it (the same rule as {@code CosmeticAnimPlayerPoser}).
 */
@OnlyIn(Dist.CLIENT)
public final class OcarinaPose
{
    private OcarinaPose() {}

    /** The tag on the server's instrument stack (key OcarinaService.INSTRUMENT_TAG). */
    public static final String INSTRUMENT_TAG = "su_ocarina_instrument";

    // GeckoLib bone radians. Animation (x,y,z) degrees map to bone (-x,-y,z): x up = raise forward, y = turn in.
    private static final float ARM_RAISE = (float) Math.toRadians(118.0D);
    private static final float ARM_IN = (float) Math.toRadians(28.0D);
    private static final float ARM_ROLL = (float) Math.toRadians(6.0D);

    /** bone -> {written x, y, z, previous x, y, z}. Render thread only. */
    private static final Map<CoreGeoBone, float[]> WRITTEN = new IdentityHashMap<>();

    public static void applyGeo(GeoModel<?> model, AbstractClientPlayer player)
    {
        if (model == null)
            return;
        undo();
        if (player == null || !isPlaying(player))
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && player.getId() == mc.player.getId() && mc.options.getCameraType().isFirstPerson())
            return;
        model.getBone("right_arm").ifPresent(b -> write(b, ARM_RAISE, ARM_IN, -ARM_ROLL));
        model.getBone("left_arm").ifPresent(b -> write(b, ARM_RAISE, -ARM_IN, ARM_ROLL));
    }

    public static boolean isPlaying(AbstractClientPlayer player)
    {
        ItemStack held = player.getMainHandItem();
        if (!held.isEmpty() && held.getTag() != null && held.getTag().getBoolean(INSTRUMENT_TAG))
            return true;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && player.getId() == mc.player.getId())
            return mc.screen instanceof OcarinaScreen
                    || net.shurui.shuruisutilities.client.combat.ClashRhythmOverlay.ocarinaPlaying();
        return false;
    }

    private static void write(CoreGeoBone b, float x, float y, float z)
    {
        WRITTEN.put(b, new float[] {x, y, z, b.getRotX(), b.getRotY(), b.getRotZ()});
        b.setRotX(x);
        b.setRotY(y);
        b.setRotZ(z);
    }

    private static void undo()
    {
        if (WRITTEN.isEmpty())
            return;
        for (Map.Entry<CoreGeoBone, float[]> e : WRITTEN.entrySet())
        {
            CoreGeoBone b = e.getKey();
            float[] w = e.getValue();
            // Still our value: nothing re-animated it, so put back what was there. Otherwise leave the fresh pose.
            if (b.getRotX() == w[0] && b.getRotY() == w[1] && b.getRotZ() == w[2])
            {
                b.setRotX(w[3]);
                b.setRotY(w[4]);
                b.setRotZ(w[5]);
            }
        }
        WRITTEN.clear();
    }
}
