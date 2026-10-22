package net.shurui.shuruisutilities.compat.dmz;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.dragonminez.common.init.block.custom.DragonBallBlock;
import com.dragonminez.server.world.data.DragonBallSavedData;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import net.shurui.dev.sdu.api.key.PrivateWorldHooks;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Where DMZ's Namek ball set scatters: the open Namek world ({@code dmz_ragnarok:namekow}) by default instead of the
 * guild-claimable Namek planet, configurable through {@code NamekDragonBallDimension}.
 *
 * <p>The set's dimension list is the ONE input DMZ reads for this (scatter, first spawn, the radar gather), so
 * {@code MixinDmzNamekBallHome} answers the Namek set's two dimension accessors from {@link #current()} every time they
 * are read. That names the configured dimension only when it is allowed (namekow is a private world, so the Ragnarok
 * Key must be installed) and the dimension exists; in every other case it is DMZ's own {@code dragonminez:namek},
 * exactly as before.
 *
 * <p>When relocated, the ball is placed anywhere inside that dimension's world border (the namekow border is seeded by
 * {@code DimensionBorderSeeder}), not in a square around a spawn point the dimension does not really have; see
 * {@code MixinDmzDragonBallScatter}.
 *
 * <p>The move itself: the first time a server starts with the relocation on, the set has not yet first spawned on
 * namekow. Just before DMZ's own first spawn (this runs at HIGH priority on the same event) the Namek planet's
 * tracked Namek balls (placed blocks and pending spots) are removed, so DMZ's first spawn lays exactly one fresh set
 * on namekow instead of a second one beside the old. Balls players are holding are not touched. Logged.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok")
public final class NamekBallHome
{
    private NamekBallHome() {}

    public static final String SET_ID = "namek";
    public static final ResourceLocation DMZ_NAMEK = new ResourceLocation("dragonminez", "namek");

    /** The dimension the Namek set scatters in right now. Never null, never throws. */
    public static ResourceLocation current()
    {
        ResourceLocation target = configured();
        return target != null ? target : DMZ_NAMEK;
    }

    /** True when the set is moved off DMZ's Namek right now. */
    public static boolean relocated()
    {
        return configured() != null;
    }

    // The configured relocation target when it is usable, else null (stay on DMZ's Namek).
    private static ResourceLocation configured()
    {
        try
        {
            String raw = SUConfig.namekDragonBallDimension;
            if (raw == null || raw.isBlank())
                return null;
            ResourceLocation id = ResourceLocation.tryParse(raw.trim());
            if (id == null || id.equals(DMZ_NAMEK))
                return null;
            if (!PrivateWorldHooks.available())
                return null;
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server != null && server.getLevel(ResourceKey.create(Registries.DIMENSION, id)) == null)
                return null;
            return id;
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /** True when {@code level} is where the relocated Namek set scatters (the scatter then fills the border). */
    public static boolean isRelocatedScatter(ServerLevel level, String setId)
    {
        return SET_ID.equals(setId) && relocated() && level != null && level.dimension().location().equals(current());
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onServerStarting(ServerStartingEvent event)
    {
        try
        {
            migrate(event.getServer());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[dragonball] Namek set relocation step failed: {}", t.toString());
        }
    }

    private static void migrate(MinecraftServer server)
    {
        if (!relocated())
            return;
        ServerLevel target = server.getLevel(ResourceKey.create(Registries.DIMENSION, current()));
        ServerLevel namek = server.getLevel(ResourceKey.create(Registries.DIMENSION, DMZ_NAMEK));
        if (target == null || namek == null)
            return;
        if (DragonBallSavedData.get(target).isFirstSpawnComplete(SET_ID))
            return; // already moved on an earlier start
        DragonBallSavedData data = DragonBallSavedData.get(namek);
        int blocks = 0;
        int pending = 0;
        for (Map.Entry<Integer, List<BlockPos>> e : data.getActiveBalls(SET_ID).entrySet())
        {
            if (e.getValue() == null)
                continue;
            for (BlockPos pos : new ArrayList<>(e.getValue()))
            {
                BlockState state = namek.getBlockState(pos);
                if (state.getBlock() instanceof DragonBallBlock ball && SET_ID.equals(ball.getBallSetId()))
                {
                    namek.removeBlock(pos, false);
                    blocks++;
                }
            }
            e.getValue().clear();
        }
        for (List<BlockPos> list : data.getPendingBalls(SET_ID).values())
        {
            if (list != null)
            {
                pending += list.size();
                list.clear();
            }
        }
        data.setDirty();
        LoggingHandler.sulog.warn("[dragonball] Namek set moved to {}: removed {} placed and {} pending Namek ball(s) "
                + "from {}; DMZ's first spawn now lays one fresh set there.", current(), blocks, pending, DMZ_NAMEK);
    }

    /** {@link Level} key of the relocation target, for callers that need it. */
    public static ResourceKey<Level> currentKey()
    {
        return ResourceKey.create(Registries.DIMENSION, current());
    }
}
