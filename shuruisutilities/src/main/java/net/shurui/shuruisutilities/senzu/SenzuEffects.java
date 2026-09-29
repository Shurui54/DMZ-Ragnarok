package net.shurui.shuruisutilities.senzu;

import java.util.function.Consumer;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import com.dragonminez.common.init.MainEffects;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

/**
 * The bean eating effects, expressed against DragonMineZ's stat runtime. DMZ is a mandatory dependency of this addon,
 * but per the workspace rule "presence is not proof of API compatibility": every DMZ touch runs inside a Throwable
 * guard so a drifted DMZ build (a renamed method, a NoClassDefFoundError) degrades to "no DMZ effect applied" and logs
 * once, rather than crashing the server on someone eating a bean.
 *
 * <p>Vanilla health is handled first and OUTSIDE that guard, because health is a plain vanilla pool ({@code setHealth}
 * / {@code getMaxHealth}) with no DMZ dependency, so a bean's health restore must still work even if DMZ is broken. The
 * DMZ ki ("energy"), stamina and poise pools live behind the guard and resync through DMZ's own S2C packets, since the
 * client HUD is not refreshed automatically on the same tick a pool is mutated.
 */
public final class SenzuEffects
{
    private SenzuEffects() {}

    // log the DMZ failure exactly once per server run, not once per bean eaten, so a drifted build does not spam the log.
    private static boolean loggedDmzFailure;

    // full restore: vanilla health plus every DMZ pool, each brought UP to `fraction` of its maximum (never reduced). At
    // fraction 1.0 with clearLocks this is the senzu bean; at 0.35 without clearLocks it is the cracked senzu.
    public static void healFull(ServerPlayer player, float fraction, boolean clearLocks)
    {
        healHealth(player, fraction);
        dmz(player, data ->
        {
            raiseEnergy(data, fraction);
            raiseStamina(data, fraction);
            raisePoise(data, fraction);
            if (clearLocks)
            {
                clearCombatLocks(player, data);
            }
        });
    }

    // vanilla health only, raised up to `fraction` of max health. Never lowers a healthier player, so a bean is always a
    // gift: "restore to 75%" means "at least 75%", not "capped at 75%". Needs no DMZ and no manual resync (vanilla health
    // syncs on its own).
    public static void healHealth(ServerPlayer player, float fraction)
    {
        float target = player.getMaxHealth() * fraction;
        if (player.getHealth() < target)
        {
            player.setHealth(target);
        }
    }

    // DMZ energy (ki) only, raised up to `fraction` of max energy.
    public static void healEnergy(ServerPlayer player, float fraction)
    {
        dmz(player, data -> raiseEnergy(data, fraction));
    }

    // DMZ stamina only, raised up to `fraction` of max stamina.
    public static void healStamina(ServerPlayer player, float fraction)
    {
        dmz(player, data -> raiseStamina(data, fraction));
    }

    // DRAIN energy and stamina DOWN to `fraction` of their maxima. Unlike the heal paths this SETS the pool directly (a
    // debuff), so the death bean (fraction 0) empties both pools and the cracked death bean (fraction 0.25) leaves a
    // quarter. Health is deliberately untouched.
    public static void drain(ServerPlayer player, float fraction)
    {
        dmz(player, data ->
        {
            data.getResources().setCurrentEnergy(data.getMaxEnergy() * fraction);
            data.getResources().setCurrentStamina(data.getMaxStamina() * fraction);
        });
    }

    // the burnt bean's trap: a meaningful but survivable poison plus hunger. Pure vanilla effects, no DMZ, no restore.
    // POISON caps its victim at half a heart (it never kills), so POISON II for 12 seconds is a nasty but non-lethal dose.
    public static void applyBurnt(ServerPlayer player)
    {
        player.addEffect(new MobEffectInstance(MobEffects.POISON, 12 * 20, 1));
        player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 12 * 20, 0));
    }

    // resolve the player's DMZ stats and hand them to `body`, then resync. StatsCapability.INSTANCE is null-guarded
    // because a sibling-addon load-order edge can leave it null, and the whole thing is Throwable-guarded so a drifted
    // DMZ build degrades to a no-op (logged once) instead of crashing. Nothing runs if the player has no character yet.
    private static void dmz(ServerPlayer player, Consumer<StatsData> body)
    {
        if (StatsCapability.INSTANCE == null)
        {
            return;
        }
        try
        {
            StatsData data = player.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
            if (data == null)
            {
                return;
            }
            body.accept(data);
            resync(player);
        }
        catch (Throwable t)
        {
            if (!loggedDmzFailure)
            {
                loggedDmzFailure = true;
                LoggingHandler.sulog.warn(
                        "[Senzu] DragonMineZ stat access failed; bean DMZ effects are disabled this run (health still works). Cause: {}",
                        t.toString());
            }
        }
    }

    // raise one pool UP to fraction*max, never lowering it. Shared by the heal paths so "restore" is always a gift.
    private static void raiseEnergy(StatsData data, float fraction)
    {
        float target = data.getMaxEnergy() * fraction;
        if (data.getResources().getCurrentEnergy() < target)
        {
            data.getResources().setCurrentEnergy(target);
        }
    }

    private static void raiseStamina(StatsData data, float fraction)
    {
        float target = data.getMaxStamina() * fraction;
        if (data.getResources().getCurrentStamina() < target)
        {
            data.getResources().setCurrentStamina(target);
        }
    }

    private static void raisePoise(StatsData data, float fraction)
    {
        float target = data.getMaxPoise() * fraction;
        if (data.getResources().getCurrentPoise() < target)
        {
            data.getResources().setCurrentPoise(target);
        }
    }

    // mirror of raid_bosses DmzHooks.clearCombatLocks: strip the DMZ stun/stagger/ki-slow effects and reset the
    // knockedDown/stunEffect/strikeLocked status flags, so a senzu bean eaten mid-lockout also frees the player's
    // inputs. Runs inside the dmz() guard, so it shares its resync and its Throwable safety.
    private static void clearCombatLocks(ServerPlayer player, StatsData data)
    {
        player.removeEffect(MainEffects.STUN.get());
        player.removeEffect(MainEffects.STAGGER.get());
        player.removeEffect(MainEffects.KI_SLOW.get());
        data.getStatus().setKnockedDown(false);
        data.getStatus().setStunEffect(false);
        data.getStatus().setStrikeLocked(false);
    }

    // push the mutated pools/stats to the player's client so the HUD updates on the same tick. Both DMZ S2C packets:
    // ResourceSyncS2C for the live pools (energy/stamina/poise) and StatsSyncS2C for the fuller stats. NetworkHandler
    // .INSTANCE is null-guarded for the same load-order reason as the capability token.
    private static void resync(ServerPlayer player)
    {
        if (NetworkHandler.INSTANCE == null)
        {
            return;
        }
        NetworkHandler.sendToPlayer(new StatsSyncS2C(player), player);
        NetworkHandler.sendToPlayer(new ResourceSyncS2C(player), player);
    }
}
