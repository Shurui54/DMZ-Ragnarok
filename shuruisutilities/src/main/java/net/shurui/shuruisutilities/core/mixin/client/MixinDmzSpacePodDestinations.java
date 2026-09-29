package net.shurui.shuruisutilities.core.mixin.client;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Hides space-pod destinations whose target dimension is not actually registered on the connected server.
 *
 * <p>DMZ's {@code SpacePodScreen.loadDestinations} rebuilds its private {@code destinations} list from the
 * data-driven registry and filters only on unlock rules; it never checks whether the destination's dimension
 * exists. On a server that ships a subset of the possible planet dimensions that leaves dead rows in the menu
 * which silently do nothing when launched. This injector runs at the tail of {@code loadDestinations} and
 * prunes any entry whose dimension is absent from the client's view of the server's registered dimensions
 * ({@code getConnection().levels()}).</p>
 *
 * <p>Filtering the contents of the same {@code destinations} list the screen already built is safe: every
 * downstream consumer (row rendering, {@code selectedIndex}, scroll bounds via {@code destinations.size()},
 * and {@code initiateTravel}'s {@code destinations.get(selectedIndex)}) reads that one list, so removing
 * elements keeps selection and scroll consistent with no separate index to fix up. {@code selectedIndex} is
 * only ever set by clicking a rendered (post-filter) row, and {@code initiateTravel} guards both
 * {@code < 0} and {@code >= size}, so a shorter list cannot mis-select or overflow.</p>
 *
 * <p>{@code require = 0}: DMZ is a hard dependency so the class is present, but if a future DMZ build reshapes
 * this method or renames the field the injector degrades to a no-op instead of crashing. Client-only (listed
 * in the "client" block of {@code mixins.shuruisutilities.json}); it never loads server-side.</p>
 */
@Mixin(targets = "com.dragonminez.client.gui.SpacePodScreen", remap = false)
public abstract class MixinDmzSpacePodDestinations
{
    /** Never hide the overworld even if some registry quirk makes it look absent. */
    private static final String OVERWORLD_ID = "minecraft:overworld";

    /** DMZ's own field (not remapped): its element type is the private {@code PlanetDestination} inner class. */
    @Shadow
    @Final
    private List<?> destinations;

    /** Cached reflective handle onto {@code PlanetDestination.dimensionId}; resolved once, tolerant of failure. */
    private static Field shuruisutilities$dimensionIdField;
    private static boolean shuruisutilities$dimensionIdResolved;

    @Inject(method = "loadDestinations", at = @At("TAIL"), remap = false, require = 0)
    private void shuruisutilities$hideUnloadedDimensions(CallbackInfo ci)
    {
        try
        {
            Minecraft mc = Minecraft.getInstance();
            ClientPacketListener connection = mc.getConnection();
            // No active connection means we cannot know which dimensions exist; show everything rather than
            // risk emptying the menu.
            if (connection == null)
            {
                return;
            }
            Set<ResourceKey<Level>> loaded = connection.levels();
            if (loaded == null || loaded.isEmpty())
            {
                return;
            }
            if (this.destinations == null || this.destinations.isEmpty())
            {
                return;
            }

            Iterator<?> it = this.destinations.iterator();
            while (it.hasNext())
            {
                Object entry = it.next();
                String dimId = shuruisutilities$readDimensionId(entry);
                // Unknown or malformed dimension ids are left in place so a read failure never blanks the menu.
                if (dimId == null || dimId.isBlank() || OVERWORLD_ID.equals(dimId))
                {
                    continue;
                }
                ResourceLocation rl = ResourceLocation.tryParse(dimId);
                if (rl == null)
                {
                    continue;
                }
                ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, rl);
                if (!loaded.contains(key))
                {
                    it.remove();
                }
            }
        }
        catch (Throwable ignored)
        {
            // Degrade to DMZ's default behaviour if anything about the internals shifts.
        }
    }

    private static String shuruisutilities$readDimensionId(Object entry)
    {
        try
        {
            if (entry == null)
            {
                return null;
            }
            if (!shuruisutilities$dimensionIdResolved)
            {
                shuruisutilities$dimensionIdResolved = true;
                Field f = entry.getClass().getDeclaredField("dimensionId");
                f.setAccessible(true);
                shuruisutilities$dimensionIdField = f;
            }
            if (shuruisutilities$dimensionIdField == null)
            {
                return null;
            }
            Object value = shuruisutilities$dimensionIdField.get(entry);
            return value instanceof String ? (String) value : null;
        }
        catch (Throwable ignored)
        {
            return null;
        }
    }
}
