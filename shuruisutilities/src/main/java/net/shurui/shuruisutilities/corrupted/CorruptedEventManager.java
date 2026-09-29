package net.shurui.shuruisutilities.corrupted;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.core.SUConfig;
import net.shurui.shuruisutilities.util.output.ChatOutputHandler;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Entry point for the event fired once a full set of swap blocks is activated. Phase 1 recorded that it happened
 * and announced a placeholder; phase 2 runs the timed cinematic below. No wish is ever granted here: the shenron
 * that appears is a self-contained SU {@link ShadowShenronEntity} prop with no wish logic at all, so nothing can
 * pull a wish from it and nothing clears the storm as a side effect of removing it.
 *
 * <p>The sequence is driven by a server tick counter (never a blocking wait), following the throttled tick idiom
 * in {@code ShrineManager} (Ragnarok Key). The timeline lives in the named tick constants so
 * it can be tuned without reading the logic. Tick 0 is the moment the seven balls are consumed.
 */
public final class CorruptedEventManager
{
    private CorruptedEventManager() {}

    // announcement + intro voice line + sky darkening + shenron appears
    private static final int T_START = 0;
    // intro clip is 8.875 s (verified); at 20 tps that is 177.5 ticks, round up so the smite lands after it ends
    private static final int INTRO_LENGTH = 178;
    // the behold clip is 2.603 s (verified); at 20 tps that is ~52 ticks. kept next to INTRO_LENGTH so the storm
    // duration derived from it (below) is obviously tied to the audio it must outlast.
    private static final int BEHOLD_LENGTH = 52;
    // three-second tail after the behold clip finishes, before the storm is allowed to end
    private static final int STORM_TAIL_AFTER_BEHOLD = 60;

    // the behold clip starts the instant the intro clip ends. the smite then lands when the behold clip finishes
    // (the user wants the smite to fire "as soon as the behold line ends"), so the smite is derived from the
    // behold start plus its length rather than hardcoded, keeping it correct if the clip lengths are re-measured.
    private static final int T_BEHOLD = INTRO_LENGTH;
    private static final int T_SMITE = T_BEHOLD + BEHOLD_LENGTH;

    // the shenron prop vanishes AFTER the smite. a short configurable linger past the smite, defaulted small so
    // it does not sit around for the whole clip. clamped to at least 1 so it is always strictly after the smite.
    private static int dragonGoneTick()
    {
        return T_SMITE + Math.max(1, SUConfig.corruptedPropLingerTicks);
    }

    // the storm must outlast the behold clip. its end is DERIVED from the timeline: behold start + behold length +
    // a three-second tail. the config (corruptedStormExtraTicks) can only ADD to this, never subtract, so the
    // storm can never end before the audio finishes no matter how it is configured. clamped at 0 as a floor.
    private static int stormEndTick()
    {
        return T_BEHOLD + BEHOLD_LENGTH + STORM_TAIL_AFTER_BEHOLD + Math.max(0, SUConfig.corruptedStormExtraTicks);
    }

    // the sequence ends once everything scheduled has happened: the last of the smite/behold, the prop removal and
    // the storm expiry. taking the max means a large configured linger or storm pad never truncates the sequence.
    private static int endTick()
    {
        return Math.max(Math.max(T_SMITE, dragonGoneTick()), stormEndTick()) + 1;
    }

    // how long the forced storm should last, measured from tick 0 (when it is set). derived from stormEndTick so
    // it always comfortably outlasts the behold clip, then expires on its own rather than being fought tick by
    // tick or left permanently on.
    private static int stormDurationTicks()
    {
        return stormEndTick();
    }

    private static boolean handlerRegistered;
    private static Sequence active;

    // UUIDs of the players currently inside the smite step. Populated immediately before the hurt() calls and
    // cleared the same tick in a finally block, so it is only ever non-empty for the single tick T_SMITE runs on.
    // The death guard below reads it to enforce the "never lethal" promise on the death itself, which no damage
    // handler can defeat. A stale entry here would make a player immortal, so every path that fills it also
    // empties it before returning (see smiteEveryone).
    private static final Set<UUID> smiteProtected = new HashSet<>();

    /**
     * Kick off the cinematic. If one is already running the new trigger is ignored: two overlapping sequences
     * would double the announcements, smites and props, so we let the in-flight one finish rather than restart.
     */
    public static void trigger(MinecraftServer server, ServerPlayer player, BlockPos pos)
    {
        // Held back for this release (see ReleaseToggles). The defiling path is already stopped one layer up in
        // CorruptedBallHandler; this second guard is for the admin force command, which reaches this directly.
        if (!net.shurui.shuruisutilities.core.ReleaseToggles.CORRUPTED_DRAGON_BALL_EVENT)
        {
            LoggingHandler.sulog.info("[wishtracking] event trigger ignored: held back in this build");
            return;
        }
        ShadowDragonStorage storage = ShadowDragonStorage.get(server);
        storage.setEverFired(true);
        // Rule 1: the base shadow_dragon race unlocks for the player who DEFILES the balls, at the moment of
        // defiling, not for everyone who later damages a dragon. The defiling itself is the qualifying act, so this
        // fires here (the balls were already consumed by the caller) even if a cinematic is already running below.
        storage.setLastDefiler(player.getUUID());
        ShadowDragonUnlocks.onBallsDefiled(server, player);

        LoggingHandler.sulog.info("[wishtracking] event triggered by {} at {}",
                player.getGameProfile().getName(), pos);

        if (active != null)
        {
            LoggingHandler.sulog.info("[wishtracking] a cinematic is already running, ignoring the new trigger");
            return;
        }

        ensureHandler();
        active = new Sequence(server, player.level().dimension(), pos);
    }

    /**
     * True while a cinematic is in flight. Diagnostic use only (the {@code /wishtracking status} readout): a
     * running sequence makes {@link #trigger} no-op, so callers can report that state.
     */
    public static boolean isRunning()
    {
        return active != null;
    }

    // register the tick handler once, the first time any cinematic runs
    private static void ensureHandler()
    {
        if (handlerRegistered)
            return;
        MinecraftForge.EVENT_BUS.register(new TickHandler());
        MinecraftForge.EVENT_BUS.register(new DeathGuard());
        handlerRegistered = true;
    }

    // The smite pre-clamps its damage to leave the player on one health, but that clamp is computed before the
    // Forge damage pipeline runs, so a DMZ or sibling-addon handler that raises the amount (or a second source in
    // the same tick) could still land the killing blow. This guard closes that gap at the death itself: if a
    // tracked player is about to die during the smite step, the death is cancelled and health is pinned to 1.0.
    // Acting on the death rather than the damage number makes it independent of handler priority and ordering.
    public static final class DeathGuard
    {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public void onDeath(LivingDeathEvent event)
        {
            if (smiteProtected.isEmpty())
                return;
            if (!(event.getEntity() instanceof ServerPlayer p))
                return;
            if (!smiteProtected.contains(p.getUUID()))
                return;
            event.setCanceled(true);
            p.setHealth(1.0f);
        }
    }

    public static final class TickHandler
    {
        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event)
        {
            if (event.side != LogicalSide.SERVER || event.phase != TickEvent.Phase.END)
                return;
            if (active == null)
                return;
            if (active.tick())
                active = null;
        }
    }

    // holds the per-run state and advances one step each server tick. returns true when finished.
    private static final class Sequence
    {
        private final MinecraftServer server;
        private final BlockPos altar;
        // the dimension the balls were defiled in; with the altar it centres the keyless random boss spots (S20b)
        private final ResourceKey<Level> altarDim;
        private int t = -1;
        // handle to the spawned prop so it can be discarded on schedule; null until spawned or if the spawn failed
        private ShadowShenronEntity dragonProp;

        private Sequence(MinecraftServer server, ResourceKey<Level> altarDim, BlockPos altar)
        {
            this.server = server;
            this.altarDim = altarDim;
            this.altar = altar.immutable();
        }

        // advance one tick. every per-player step is guarded so logouts and a zero-player server are harmless.
        private boolean tick()
        {
            t++;
            // the timeline: intro voice + storm + shenron at t=0; the behold voice line at T_BEHOLD (when the intro
            // clip ends); the smite at T_SMITE (when the behold clip ends, so the two are one behold length apart);
            // the prop removed a short configurable linger after the smite; the storm expires on its own (its
            // duration derived to outlast the behold clip). the tail cues are config-derived so they are compared
            // explicitly, not switched.
            if (t == T_START)
            {
                announce();
                playVoiceLine(ShadowDragonSounds.INTRO.get());
                darkenSky();
                spawnDragon();
            }
            if (t == T_BEHOLD)
            {
                playVoiceLine(ShadowDragonSounds.BEHOLD.get());
            }
            if (t == T_SMITE)
            {
                smiteEveryone();
            }
            if (t == dragonGoneTick())
            {
                removeDragon();
            }
            if (t == stormEndTick())
            {
                clearSky();
            }

            if (t >= endTick())
            {
                // safety net: make sure the prop is gone even if the dragonGoneTick step was somehow skipped
                removeDragon();
                // safety net: make sure the storm is cleared even if the stormEndTick step was somehow skipped
                clearSky();
                // phase 3c: the cinematic is over, spawn the seven boss dragons (the real fight). This records
                // each live dragon and the encounter start tick in storage; death/timeout/pips are driven from
                // ShadowDragonForgeHandler and CorruptedBallHandler's throttled tick.
                ShadowDragonBossManager.spawnEncounter(server, altarDim, altar);
                return true;
            }
            return false;
        }

        // chat broadcast + a big on-screen title to every online player
        private void announce()
        {
            // broadcast(String) sends the raw text verbatim (it never runs formatColors), which is why the &c
            // came through uncoloured; format it here and use the Component overload so the red actually applies.
            ChatOutputHandler.broadcast(Component.literal(ChatOutputHandler.formatColors(
                    "&cThe dragonballs have been defiled, pure malice takes form!")));
            Component title = Component.literal(ChatOutputHandler.formatColors("&c&lPure Malice"));
            Component subtitle = Component.literal(ChatOutputHandler.formatColors("&7The dragonballs have been defiled"));
            for (ServerPlayer p : server.getPlayerList().getPlayers())
            {
                // Per viewer, not per broadcast: a player who turned SU's on-screen announcements off does not see
                // this title, but the chat line above still reaches everyone and the other players still get the title.
                if (!net.shurui.shuruisutilities.announce.AnnouncementPrefs.shown(p))
                    continue;
                p.connection.send(new ClientboundSetTitlesAnimationPacket(10, 70, 20));
                p.connection.send(new net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket(subtitle));
                p.connection.send(new ClientboundSetTitleTextPacket(title));
            }
        }

        // send our flat non-positional sound packet to every online player, in every dimension. the client plays
        // it as a UI sound (no world position, no attenuation, no panning), so it is the identical, everyone-hears-
        // it-the-same cue the ender-dragon-death sound is. a directed ClientboundSoundPacket could not do this: it
        // anchors to a fixed position and attenuates as the player moves.
        private void playVoiceLine(SoundEvent sound)
        {
            if (sound == null)
                return;
            PacketGlobalSound packet = new PacketGlobalSound(sound, 1.0f, 1.0f);
            for (ServerPlayer p : server.getPlayerList().getPlayers())
                NetworkUtils.sendTo(packet, p);
        }

        // force a thunderstorm in every dimension that actually has weather, then let it expire on its own.
        // hasSkyLight() is the honest test: the Nether and the End report false and are skipped by design, and
        // it stays correct for the server's custom dimensions without hardcoding any dimension id.
        private void darkenSky()
        {
            for (ServerLevel level : server.getAllLevels())
            {
                if (!level.dimensionType().hasSkyLight())
                    continue;
                level.setWeatherParameters(0, stormDurationTicks(), true, true);
            }
        }

        // restore the weather to clear at the end of the cinematic, in exactly the dimensions darkenSky touched.
        // darkenSky set a BOUNDED storm, but that timer only runs down while the doWeatherCycle game rule is on: on
        // a world where it is off (it can be turned off, or arrive off from another shard through GameRuleSync) the
        // forced rain would otherwise never expire, which is exactly the "rains forever" report. Setting the weather
        // clear here ends the storm no matter the game rule, rather than trusting the timer. clearTime is positive so
        // it holds clear for a short spell before the world's own weather cycle (if any) resumes; raining/thundering
        // false is the part that stops the storm immediately.
        private void clearSky()
        {
            for (ServerLevel level : server.getAllLevels())
            {
                if (!level.dimensionType().hasSkyLight())
                    continue;
                level.setWeatherParameters(6000, 0, false, false);
            }
        }

        // spawn the self-contained SU shadow shenron prop at the altar. the swap blocks are scattered into the
        // overworld (CorruptedScatter), so that is where the altar sits. no DMZ guard needed: this is our entity.
        private void spawnDragon()
        {
            ServerLevel level = server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null)
                return;
            ShadowShenronEntity dragon = ShadowShenronEntities.SHADOW_SHENRON.get().create(level);
            if (dragon == null)
                return;
            dragon.moveTo(altar.getX() + 0.5D, altar.getY(), altar.getZ() + 0.5D,
                    level.getRandom().nextFloat() * 360F, 0F);
            if (level.addFreshEntity(dragon))
                dragonProp = dragon;
        }

        // remove the prop. plain discard(): our entity has no despawn/onDespawn side effects, so nothing here
        // touches the weather. tolerant of a null handle and of the entity already being gone.
        private void removeDragon()
        {
            if (dragonProp != null && dragonProp.isAlive())
                dragonProp.discard();
            dragonProp = null;
        }

        // smite every online player in their OWN level: a cosmetic bolt for the visual and thunder, then our own
        // clamped damage so the player is always left with at least one health point. never real lightning.
        private void smiteEveryone()
        {
            // clear defensively first: nothing should still be tracked from a previous run, but if it somehow were
            // (an exception between fill and clear last time), we drop it here rather than carry it forward.
            smiteProtected.clear();
            try
            {
                for (ServerPlayer p : server.getPlayerList().getPlayers())
                {
                    if (p.isSpectator())
                        continue;
                    if (!(p.level() instanceof ServerLevel level))
                        continue;

                    LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(level);
                    if (bolt != null)
                    {
                        bolt.moveTo(Vec3.atBottomCenterOf(p.blockPosition()));
                        // cosmetic only: no vanilla lightning damage, no fires, no burnt drops
                        bolt.setVisualOnly(true);
                        level.addFreshEntity(bolt);
                    }

                    // creative players still see the bolt but take no damage
                    if (p.isCreative())
                        continue;

                    // clamp from current health so the hit can never kill: at most (health - 1) is ever dealt, so a
                    // player at half a heart survives regardless of the damage source's own scaling
                    float damage = Math.min(6.0f, Math.max(0.0f, p.getHealth() - 1.0f));
                    if (damage <= 0.0f)
                        continue;

                    // mark the player just before the hit so the death guard covers this hurt(), then hurt them.
                    // even if a downstream handler inflates the amount past their health, the death is cancelled.
                    smiteProtected.add(p.getUUID());
                    p.hurt(level.damageSources().lightningBolt(), damage);
                }
            }
            finally
            {
                // the smite is a single synchronous tick: by the time we leave this method every hurt() has fully
                // resolved through the pipeline, so the guard is no longer needed. Always empty the set, even if a
                // hurt() threw, so a crash mid-smite can never leave a player permanently undeathable.
                smiteProtected.clear();
            }
        }
    }
}
