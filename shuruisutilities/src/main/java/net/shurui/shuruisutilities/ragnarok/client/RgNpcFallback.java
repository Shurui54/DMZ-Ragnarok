package net.shurui.shuruisutilities.ragnarok.client;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.core.ShuruisUtilities;

/**
 * The presence check that decides when an rgnpc's own model must be stood in for, plus the warn-once and the plain-Steve
 * texture stand-in the model resolvers fall back to.
 *
 * <p>This exists because the rgnpc set is no longer in the jar: it is streamed from the server folder
 * ({@code RgNpcAssetServer} -&gt; {@link RgNpcPackResources}). A client that joins a server whose admin has not
 * installed the pack, or that meets an entity whose model was dropped from a later pack, would otherwise hand
 * GeckoLib a resource location with no file behind it, and GeckoLib does not degrade: it throws out of
 * {@code getBakedModel}, on the render thread, which takes the client down. A missing badge is a missing badge; a
 * missing model is a crash. So every location is checked before it is handed over.</p>
 *
 * <p>The check is ordered for the common case: {@link RgNpcPackResources#has} is a map lookup and answers yes for
 * every model on a properly installed server, so the resource manager is only consulted on the rare miss. That
 * second lookup is what still honours a model added by a player's own resource pack, which the map alone would
 * wrongly report as absent. Because it only runs on the miss path, no cache is needed and there is nothing to
 * invalidate on a resource reload.</p>
 *
 * <p>WHAT is drawn on a miss is NOT decided here any more. The three rgnpc {@link software.bernie.geckolib.model.GeoModel}
 * resolvers now draw a generated DragonMineZ saiyan on the borrowed race geo (see {@code SaiyanFallbackRender} and the
 * saiyan layer stack) rather than a plain Steve, because a missing model is the NORMAL case on a server without the pack
 * and a random saiyan reads far better than a wall of Steves. This class only answers "is the wanted model present" and
 * logs the miss once; {@link #TEXTURE} remains as the last-ditch texture the models hand back if a geo is somehow present
 * while its own skin is not.</p>
 */
public final class RgNpcFallback
{
    private RgNpcFallback() {}

    private static final Logger LOGGER = LogUtils.getLogger();

    /**
     * Last-ditch texture: a plain Steve skin. The geo fallback is no longer a Steve body (the models borrow a DMZ race geo
     * and paint a saiyan onto it), but this vanilla skin is still handed back on the rare path where a ragnarok geo is
     * present yet its own texture is not, so a textured character never renders with a missing (magenta) skin.
     */
    public static final ResourceLocation TEXTURE =
            new ResourceLocation("minecraft", "textures/entity/player/wide/steve.png");

    // one line per distinct missing file, not one per frame: these are consulted from the render loop.
    private static final Set<String> WARNED = Collections.synchronizedSet(new HashSet<>());

    /** {@code wanted} if a file really backs it, otherwise {@code fallback}. Null {@code wanted} yields the fallback. */
    public static ResourceLocation presentOrDefault(ResourceLocation wanted, ResourceLocation fallback)
    {
        if (present(wanted))
            return wanted;
        warnMissing(wanted);
        return fallback;
    }

    /**
     * True when a file really backs {@code loc}. Public so a {@link software.bernie.geckolib.model.GeoModel} can recompute
     * the SAME check it used to pick the geo, which is how a saiyan layer asks whether the entity is falling back. A null
     * location is never present.
     */
    public static boolean present(ResourceLocation loc)
    {
        if (loc == null)
            return false;
        if (ShuruisUtilities.MODID.equals(loc.getNamespace()) && RgNpcPackResources.has(loc.getPath()))
            return true;
        Minecraft mc = Minecraft.getInstance();
        return mc.getResourceManager().getResource(loc).isPresent();
    }

    /** Log a missing model once per distinct location, so the render loop cannot flood the log. A no-op for null. */
    public static void warnMissing(ResourceLocation wanted)
    {
        if (wanted != null && WARNED.add(wanted.toString()))
        {
            LOGGER.warn("[rgnpc] {} is not bundled; drawing a generated saiyan instead. This normally means a stored "
                    + "NPC still names a character that was removed when the model set was replaced.", wanted);
        }
    }
}
