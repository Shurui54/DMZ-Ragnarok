package net.shurui.shuruisutilities.grave;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;

/**
 * SU's {@code KeepPartialInventory} boolean gamerule: registration + accessor.
 *
 * <p>When TRUE this rule takes over death-item handling for players:
 * <ul>
 *   <li>DMZ level &lt; {@link #FULL_KEEP_LEVEL_THRESHOLD}: full keep-inventory (all items + XP).</li>
 *   <li>DMZ level &gt;= threshold: keep hotbar 0-8, offhand, armor, Curios; main inv 9-35 + all XP go to a
 *       grave at the death location.</li>
 * </ul>
 * FALSE = SU does nothing, vanilla death rules apply.
 *
 * <p>Independent of vanilla keepInventory. When on, SU cancels the vanilla drop/XP events and restores kept
 * items in Clone regardless of vanilla keepInventory. If vanilla keepInventory is ALSO on, SU's restore is
 * either a no-op (full-keep tier) or actively clears main-inv 9-35 off the clone into the grave (level>=100),
 * so the grave still wins for high-level players. Want pure vanilla keepInventory? Turn THIS rule off.
 */
public final class KeepPartialInventory
{
    private KeepPartialInventory() {}

    public static final String RULE_NAME = "KeepPartialInventory";
    public static final boolean DEFAULT = true;

    // DMZ level at/above which a death produces a grave (partial keep); below it is full keep-inventory
    public static final int FULL_KEEP_LEVEL_THRESHOLD = 100;

    // Minutes after creation a grave despawns: blocks/marker removed, contents (items + XP) DISCARDED not
    // dropped. Aging uses game time, so only advances while the server runs. Operator-settable through
    // /rg npc totem time and shared across shards; the live value lives in sdu (GraveTotemConfig), which is the
    // one tree both the /rg command and this sweep can reach without inverting the sdu import invariant. Read it
    // through graveDespawnMinutes(), never cache it, so a mid-session change takes effect on the next sweep.
    public static int graveDespawnMinutes()
    {
        return net.shurui.dev.sdu.grave.GraveTotemConfig.minutes();
    }

    // null until register() runs in common setup; guarded reads in isEnabled degrade to DEFAULT before then
    private static GameRules.Key<GameRules.BooleanValue> key;

    // Register the gamerule. MUST run on the mod thread (GameRules' static registry isn't thread-safe), e.g.
    // FMLCommonSetupEvent#enqueueWork. Guarded because re-registering the same name would throw.
    public static void register()
    {
        if (key != null)
            return;
        key = GameRules.register(RULE_NAME, GameRules.Category.PLAYER, GameRules.BooleanValue.create(DEFAULT));
    }

    // registered key, or null if register() hasn't run yet
    public static GameRules.Key<GameRules.BooleanValue> key()
    {
        return key;
    }

    // registered and currently on in the player's level
    public static boolean isEnabled(ServerPlayer player)
    {
        if (key == null || player == null)
            return false;
        try
        {
            return player.serverLevel().getGameRules().getBoolean(key);
        }
        catch (Throwable t)
        {
            return DEFAULT;
        }
    }
}
