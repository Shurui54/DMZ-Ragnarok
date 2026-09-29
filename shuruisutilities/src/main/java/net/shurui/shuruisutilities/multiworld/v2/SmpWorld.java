package net.shurui.shuruisutilities.multiworld.v2;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * The one place that answers "which dimension is the SMP survival world".
 *
 * <h2>Why this exists</h2>
 * The SMP world is a MULTIWORLD world, so its dimension id is built from {@link Multiworld#SUNameSpace}, which the
 * dimension/biome rename stage moved from {@code shuruisutilities} to {@code dmz_ragnarok}. Two mixins had that id
 * spelled out as a constant and were not moved with it, so on a migrated server they stopped matching the SMP world
 * entirely: nether portals in SMP could no longer be lit, and one that was already there no longer routed back.
 * Nothing failed loudly, because both sites simply fall through to vanilla behaviour when the id does not match.
 *
 * <p>So the id is derived here, and the LEGACY spelling is still accepted, because a world that has not been through
 * {@code world-tools/ns-rename} genuinely still holds the dimension under the old namespace. Accepting both costs a
 * string comparison and means neither kind of server is broken by this file.
 */
public final class SmpWorld
{

    /** The multiworld's internal name. The dimension id is {@code <namespace>:smp}. */
    public static final String INTERNAL_NAME = "smp";

    /** The namespace multiworld dimensions used BEFORE the rename stage. Still valid on an unmigrated world. */
    private static final String LEGACY_NAMESPACE = "shuruisutilities";

    private SmpWorld()
    {
    }

    /** The SMP dimension id for THIS build's multiworld namespace. */
    public static ResourceLocation id()
    {
        return new ResourceLocation(Multiworld.SUNameSpace, INTERNAL_NAME);
    }

    /** The pre-rename SMP dimension id, still present on a world that has not been migrated. */
    public static ResourceLocation legacyId()
    {
        return new ResourceLocation(LEGACY_NAMESPACE, INTERNAL_NAME);
    }

    /** True when {@code dimension} is the SMP world under either spelling. */
    public static boolean is(ResourceLocation dimension)
    {
        return dimension != null && (dimension.equals(id()) || dimension.equals(legacyId()));
    }

    public static boolean is(ResourceKey<Level> dimension)
    {
        return dimension != null && is(dimension.location());
    }

    /**
     * The SMP level, loading it through the multiworld manager if it is not up yet, and falling back to the legacy
     * id on an unmigrated world. Null when this server has no SMP world at all, which is the normal case off the
     * live server.
     */
    public static ServerLevel level(MinecraftServer server)
    {
        if (server == null)
        {
            return null;
        }
        for (ResourceLocation candidate : new ResourceLocation[] {id(), legacyId()})
        {
            ServerLevel live = server.getLevel(ResourceKey.create(Registries.DIMENSION, candidate));
            if (live != null)
            {
                return live;
            }
            ServerLevel loaded = MultiworldEngine.manager().ensureWorldLoaded(candidate.toString());
            if (loaded != null)
            {
                return loaded;
            }
        }
        return null;
    }
}
