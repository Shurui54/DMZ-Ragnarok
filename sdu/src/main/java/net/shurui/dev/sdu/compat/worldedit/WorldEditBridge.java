package net.shurui.dev.sdu.compat.worldedit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

import java.util.UUID;

/**
 * Reads a player's current WorldEdit cuboid selection entirely through reflection, so this addon
 * compiles and runs whether or not WorldEdit is installed. Works with WorldEdit installed either as a
 * Forge mod (via {@code ForgeAdapter}) or as a Bukkit plugin on a hybrid server such as Mohist/Arclight
 * (via {@code BukkitAdapter}); the Forge path is tried first, then the Bukkit path. Returns {@code null}
 * when WorldEdit is absent or the player has no complete selection.
 *
 * <p>WorldEdit is an OPTIONAL dependency of {@code sdu}: no WorldEdit type is imported or compiled
 * against here; every access is pure reflection, gated behind {@link #isPresent()}. Copied from the
 * raid-bosses addon's bridge per the optional-dependency pattern.
 */
public final class WorldEditBridge {
    private WorldEditBridge() {}

    public static boolean isPresent() {
        if (ModList.get() != null && ModList.get().isLoaded("worldedit")) return true;
        // WorldEdit may be installed as a Bukkit plugin on a hybrid server: detect it by class presence.
        return classAvailable("com.sk89q.worldedit.WorldEdit");
    }

    /** @return {min, max} block corners of the player's selection, or null if unavailable. */
    public static BlockPos[] getSelection(ServerPlayer player) {
        if (!isPresent()) return null;
        // Resolve the player to a WorldEdit actor via whichever platform is installed, then read the
        // selection through the shared session API.
        Object actor = adaptForge(player);
        if (actor == null) actor = adaptBukkit(player);
        if (actor == null) return null;
        return selectionOf(actor);
    }

    /** WorldEdit-as-a-Forge-mod: adapt the ServerPlayer directly. */
    private static Object adaptForge(ServerPlayer player) {
        try {
            Class<?> adapter = Class.forName("com.sk89q.worldedit.forge.ForgeAdapter");
            return adapter.getMethod("adaptPlayer", ServerPlayer.class).invoke(null, player);
        } catch (Throwable t) {
            return null;
        }
    }

    /** WorldEdit-as-a-Bukkit-plugin (hybrid server): look the player up on the Bukkit side and adapt that. */
    private static Object adaptBukkit(ServerPlayer player) {
        try {
            Class<?> bukkit = Class.forName("org.bukkit.Bukkit");
            Object bukkitPlayer = bukkit.getMethod("getPlayer", UUID.class).invoke(null, player.getUUID());
            if (bukkitPlayer == null) return null;
            Class<?> bukkitAdapter = Class.forName("com.sk89q.worldedit.bukkit.BukkitAdapter");
            Class<?> bukkitPlayerClass = Class.forName("org.bukkit.entity.Player");
            return bukkitAdapter.getMethod("adapt", bukkitPlayerClass).invoke(null, bukkitPlayer);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Shared: read the cuboid selection for an already-adapted WorldEdit actor. */
    private static BlockPos[] selectionOf(Object actor) {
        try {
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
        } catch (Throwable t) {
            // No selection / API mismatch: caller falls back to radius mode.
            return null;
        }
    }

    private static boolean classAvailable(String name) {
        try {
            Class.forName(name, false, WorldEditBridge.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static BlockPos toBlockPos(Object blockVector3) throws Exception {
        int x = (int) blockVector3.getClass().getMethod("getX").invoke(blockVector3);
        int y = (int) blockVector3.getClass().getMethod("getY").invoke(blockVector3);
        int z = (int) blockVector3.getClass().getMethod("getZ").invoke(blockVector3);
        return new BlockPos(x, y, z);
    }
}
