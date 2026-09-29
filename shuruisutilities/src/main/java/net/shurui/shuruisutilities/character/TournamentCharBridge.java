package net.shurui.shuruisutilities.character;

import java.lang.reflect.Method;

import net.minecraft.server.level.ServerPlayer;

/**
 * Soft bridge from SU's character-slot GUI to the Tournaments addon's dedicated tournament character. Reflection
 * based, mirroring {@link ZSoulBridge}: SU never hard-references the tournament classes, so its tree stays coherent
 * on its own, and everything no-ops when the tournament addon is absent. In the merged {@code dmz_ragnarok} jar the
 * tournament classes are always present, so this simply forwards.
 *
 * <p>The tournament character lives in its OWN sibling store and is deliberately NOT one of SU's selectable slots.
 * The slots GUI only ever makes it visible, lets the player create one in advance, and lets them delete it. It can
 * never be selected outside a tournament.</p>
 */
public final class TournamentCharBridge
{
    private TournamentCharBridge() {}

    private static boolean resolved;
    private static Method enabledM;  // TournamentCharacter.featureEnabled() -> boolean
    private static Method hasM;      // TournamentCharacter.hasTemplate(ServerPlayer) -> boolean
    private static Method activeM;   // TournamentCharacter.isActive(ServerPlayer) -> boolean
    private static Method createM;   // TournamentCharacter.beginCreation(ServerPlayer) -> void
    private static Method deleteM;   // TournamentCharacter.deleteTemplate(ServerPlayer) -> boolean

    /** Whether the tournament character feature is switched on (so the slots GUI should show its tournament row). */
    public static boolean featureEnabled()
    {
        resolve();
        return enabledM != null && invokeBool(enabledM, null);
    }

    /** Whether this player has a built tournament character. */
    public static boolean present(ServerPlayer p)
    {
        resolve();
        return invokeBool(hasM, p);
    }

    /** Whether this player is currently swapped into their tournament character (in a match). */
    public static boolean active(ServerPlayer p)
    {
        resolve();
        return invokeBool(activeM, p);
    }

    /** Start full tournament-character creation (blank DMZ character sandbox). No-op when the addon is absent. */
    public static void openCreate(ServerPlayer p)
    {
        resolve();
        invokeVoid(createM, p);
    }

    /** Delete this player's tournament character (server refuses while they are in a match, and messages them). */
    public static void delete(ServerPlayer p)
    {
        resolve();
        invokeVoid(deleteM, p);
    }

    private static boolean invokeBool(Method m, ServerPlayer p)
    {
        if (m == null)
            return false;
        try
        {
            Object r = m.invoke(null, p == null ? new Object[0] : new Object[] { p });
            return r instanceof Boolean b && b;
        }
        catch (Throwable ignored)
        {
            return false;
        }
    }

    private static void invokeVoid(Method m, ServerPlayer p)
    {
        if (m == null)
            return;
        try
        {
            m.invoke(null, p);
        }
        catch (Throwable ignored)
        {
        }
    }

    private static synchronized void resolve()
    {
        if (resolved)
            return;
        resolved = true;
        try
        {
            Class<?> c = Class.forName("net.shurui.dev.shuruis_dmz_tournaments.character.TournamentCharacter");
            enabledM = c.getMethod("featureEnabled");
            hasM = c.getMethod("hasTemplate", ServerPlayer.class);
            activeM = c.getMethod("isActive", ServerPlayer.class);
            createM = c.getMethod("beginCreation", ServerPlayer.class);
            deleteM = c.getMethod("deleteTemplate", ServerPlayer.class);
        }
        catch (Throwable ignored)
        {
            enabledM = null;
            hasM = null;
            activeM = null;
            createM = null;
            deleteM = null;
        }
    }
}
