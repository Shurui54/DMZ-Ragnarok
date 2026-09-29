package net.shurui.dev.shuruis_dmz_tournaments.region;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Reads a player's current WorldEdit cuboid selection entirely through reflection, so this addon compiles and
 * runs with or without WorldEdit. Two flavours:
 * <ul>
 *   <li>WorldEdit as a <b>Forge mod</b> (the usual case), and</li>
 *   <li>WorldEdit as a <b>Bukkit plugin</b> on a Forge/Bukkit hybrid (Mohist, Arclight, Magma).</li>
 * </ul>
 * Returns {@code null} when WorldEdit is absent or the selection is incomplete; callers fall back to manual
 * pos1/pos2 entry.
 */
public final class WorldEditBridge {
    private WorldEditBridge() {}

    /** True if WorldEdit is available in either supported form. */
    public static boolean isPresent() {
        return isForgeModPresent() || isBukkitPluginPresent();
    }

    /** @return {min, max} block corners of the player's selection, or null if unavailable. */
    public static BlockPos[] getSelection(ServerPlayer player) {
        BlockPos[] sel = fromForgeMod(player);
        if (sel != null) return sel;
        return fromBukkitPlugin(player);
    }

    private static boolean isForgeModPresent() {
        return ModList.get() != null && ModList.get().isLoaded("worldedit");
    }

    private static BlockPos[] fromForgeMod(ServerPlayer player) {
        if (!isForgeModPresent()) return null;
        try {
            Class<?> adapter = Class.forName("com.sk89q.worldedit.forge.ForgeAdapter");
            Object actor = adapter.getMethod("adaptPlayer", ServerPlayer.class).invoke(null, player);
            return selectionOf(actor);
        } catch (Throwable t) {
            // no selection or API mismatch: caller falls back
            return null;
        }
    }

    private static boolean isBukkitPluginPresent() {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object pluginManager = bukkit.getMethod("getPluginManager").invoke(null);
            Object plugin = pluginManager.getClass().getMethod("getPlugin", String.class).invoke(pluginManager, "WorldEdit");
            return plugin != null;
        } catch (Throwable t) {
            return false;
        }
    }

    private static BlockPos[] fromBukkitPlugin(ServerPlayer player) {
        if (!isBukkitPluginPresent()) return null;
        try {
            // on a hybrid server a Forge ServerPlayer exposes its Bukkit CraftPlayer via getBukkitEntity()
            Object bukkitPlayer = player.getClass().getMethod("getBukkitEntity").invoke(player);
            Class<?> bukkitPlayerIface = Class.forName("org.bukkit.entity.Player");
            Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Object actor = bukkitAdapter.getMethod("adapt", bukkitPlayerIface).invoke(null, bukkitPlayer);
            return selectionOf(actor);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Given a WorldEdit {@code Player} actor, resolve their current session selection to block corners. */
    private static BlockPos[] selectionOf(Object actor) throws Exception {
        Class<?> weClass = Class.forName("com.sk89q.worldedit.WorldEdit");
        Object we = weClass.getMethod("getInstance").invoke(null);
        Object sessionManager = weClass.getMethod("getSessionManager").invoke(we);

        Class<?> sessionOwner = Class.forName("com.sk89q.worldedit.session.SessionOwner");
        Object session = sessionManager.getClass().getMethod("get", sessionOwner).invoke(sessionManager, actor);

        Object world = actor.getClass().getMethod("getWorld").invoke(actor);
        Class<?> worldClass = Class.forName("com.sk89q.worldedit.world.World");
        Object region = session.getClass().getMethod("getSelection", worldClass).invoke(session, world);

        Object minV = region.getClass().getMethod("getMinimumPoint").invoke(region);
        Object maxV = region.getClass().getMethod("getMaximumPoint").invoke(region);
        return new BlockPos[]{toBlockPos(minV), toBlockPos(maxV)};
    }

    private static BlockPos toBlockPos(Object blockVector3) throws Exception {
        int x = (int) blockVector3.getClass().getMethod("getX").invoke(blockVector3);
        int y = (int) blockVector3.getClass().getMethod("getY").invoke(blockVector3);
        int z = (int) blockVector3.getClass().getMethod("getZ").invoke(blockVector3);
        return new BlockPos(x, y, z);
    }
}
