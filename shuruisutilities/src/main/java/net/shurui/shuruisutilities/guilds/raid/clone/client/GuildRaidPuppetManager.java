package net.shurui.shuruisutilities.guilds.raid.clone.client;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.authlib.GameProfile;

import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Client-side owner of the guild-raid clone PUPPETS. Keyed by the driver entity's network id, it builds one
 * {@link GuildRaidPuppet} per clone from an appearance capsule, keeps each puppet's position and rotation slaved
 * to its (invisible) server-side driver, and is asked by {@link GuildRaidPuppetRenderHook} to draw them each
 * frame.
 *
 * <h2>Graceful degradation is the whole point</h2>
 * Every entry point here is wrapped so that ANY failure (a missing driver, a DragonMineZ capability change, a
 * renderer error) silently drops that one puppet and never propagates. The server-side driver is the real fighter
 * and is completely independent of this class: if a puppet never appears or throws while rendering, the driver is
 * simply visible (its plain saga model) or unadorned, and the raid runs exactly the same. Nothing here can affect
 * the fight, and nothing here runs on a server (the class is client-only and only reached through {@code
 * DistExecutor}).
 */
@OnlyIn(Dist.CLIENT)
public final class GuildRaidPuppetManager
{
    private GuildRaidPuppetManager()
    {
    }

    // driver entity network id -> puppet. ConcurrentHashMap because the render hook (render thread) reads while the
    // packet handler (client main thread via enqueueWork) writes.
    private static final Map<Integer, GuildRaidPuppet> PUPPETS = new ConcurrentHashMap<>();

    /**
     * Handle one appearance capsule from {@link net.shurui.shuruisutilities.guilds.raid.clone.PacketCloneAppearance}.
     * SPAWN builds (or refreshes) a puppet bound to {@code cloneEntityId}; REMOVE drops it. Runs on the client main
     * thread. Never throws.
     */
    public static void accept(byte action, int cloneEntityId, UUID sourceMember, String sourceName,
                              CompoundTag statsNbt)
    {
        try
        {
            if (action == net.shurui.shuruisutilities.guilds.raid.clone.PacketCloneAppearance.ACTION_REMOVE)
            {
                PUPPETS.remove(cloneEntityId);
                return;
            }
            ClientLevel level = Minecraft.getInstance().level;
            if (level == null)
            {
                return;
            }
            String name = (sourceName == null || sourceName.isEmpty()) ? "clone" : sourceName;
            GameProfile profile = new GameProfile(sourceMember, name);
            GuildRaidPuppet puppet = new GuildRaidPuppet(level, profile);
            applyStats(puppet, statsNbt);
            PUPPETS.put(cloneEntityId, puppet);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[GuildRaid] Failed to build clone puppet {}; driver stays visible.",
                    cloneEntityId, t);
        }
    }

    // populate the puppet's DragonMineZ StatsData capability (attached to every Player) from the source member's
    // saved stats, so DragonMineZ's model renders the member's race, colours, hair and active form. A foreign save
    // load is wrapped so a version skew degrades to the default appearance rather than crashing.
    private static void applyStats(GuildRaidPuppet puppet, CompoundTag statsNbt)
    {
        if (statsNbt == null || statsNbt.isEmpty())
        {
            return;
        }
        try
        {
            StatsData data = puppet.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
            if (data != null)
            {
                data.load(statsNbt);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[GuildRaid] Failed to load clone puppet stats; default appearance used.", t);
        }
    }

    /** Drop every puppet. Called on level unload / disconnect so nothing lingers across worlds. */
    public static void clear()
    {
        PUPPETS.clear();
    }

    /** True if there is at least one puppet to draw, so the render hook can early-out cheaply. */
    public static boolean isEmpty()
    {
        return PUPPETS.isEmpty();
    }

    /**
     * True if a puppet is currently bound to the driver with this network id. The clone entity renderer calls this
     * to suppress the driver's own plain saga body while its puppet is drawing, so exactly one body appears. When
     * no puppet exists (never built, or dropped after a failure) this returns false and the driver renders normally,
     * which is the graceful-degradation path.
     */
    public static boolean hasPuppetFor(int driverEntityId)
    {
        return PUPPETS.containsKey(driverEntityId);
    }

    /**
     * Slave every puppet to its driver and hand the live set to the caller. A puppet whose driver has despawned
     * (dead clone, chunk unloaded) is dropped here, so a puppet can never outlive its driver on the client. Never
     * throws.
     */
    static void syncAndForEach(PuppetConsumer consumer)
    {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || PUPPETS.isEmpty())
        {
            return;
        }
        for (Iterator<Map.Entry<Integer, GuildRaidPuppet>> it = PUPPETS.entrySet().iterator(); it.hasNext(); )
        {
            Map.Entry<Integer, GuildRaidPuppet> e = it.next();
            try
            {
                Entity driver = level.getEntity(e.getKey());
                if (driver == null || !driver.isAlive())
                {
                    it.remove(); // driver gone: drop the orphan puppet
                    continue;
                }
                GuildRaidPuppet puppet = e.getValue();
                slave(puppet, driver);
                consumer.accept(puppet, driver);
            }
            catch (Throwable t)
            {
                // one bad puppet must never stop the others or the frame: drop it.
                it.remove();
                LoggingHandler.sulog.warn("[GuildRaid] Clone puppet render failed; dropped.", t);
            }
        }
    }

    // mirror the driver's motion onto the puppet so it can be interpolated smoothly. interpolation is the whole
    // point here: the render hook lerps between a field's PREVIOUS-tick value and its CURRENT-tick value using the
    // frame's partial tick, so prev and current MUST hold different values. the earlier version copied the driver's
    // CURRENT position into both the previous AND the current fields (xo = xOld = x), which collapsed the delta to
    // zero: with nothing to lerp between, the puppet only jumped when the driver's client position updated, roughly
    // five times a second, and that stepping is exactly the "5 fps" stutter the user reported. so we copy the
    // driver's PREVIOUS tick into the puppet's previous fields and its CURRENT tick into the current fields, and
    // never collapse one onto the other.
    private static void slave(GuildRaidPuppet puppet, Entity driver)
    {
        // position: current from the driver's current, previous from the driver's previous. both prev triples
        // (xOld/yOld/zOld and xo/yo/zo) are pinned to the driver's PREVIOUS tick, never to the current position.
        puppet.setPos(driver.getX(), driver.getY(), driver.getZ());
        puppet.xOld = driver.xOld;
        puppet.yOld = driver.yOld;
        puppet.zOld = driver.zOld;
        puppet.xo = driver.xOld;
        puppet.yo = driver.yOld;
        puppet.zo = driver.zOld;

        // rotations get the same prev != current split. the model reads view yaw/pitch as lerp(partial, *O, *),
        // so a collapsed rotation would step the head and body in lockstep with the position stutter.
        puppet.setYRot(driver.getYRot());
        puppet.yRotO = driver.yRotO;
        puppet.setXRot(driver.getXRot());
        puppet.xRotO = driver.xRotO;
        puppet.yBodyRot = driver.getYRot();
        puppet.yBodyRotO = driver.yRotO;

        if (driver instanceof LivingEntity living)
        {
            // head yaw, again prev from prev and current from current.
            puppet.yHeadRot = living.yHeadRot;
            puppet.yHeadRotO = living.yHeadRotO;

            // locomotion: the puppet is never ticked, so without this its walk-cycle and velocity sit frozen at
            // zero and DMZ's model only ever resolves the IDLE animation, so a running clone slides in an idle pose.
            // mirror the driver's swing amplitude (this is what flips the model out of idle into walk/run) and step
            // the puppet's own swing phase by it so the limbs actually cycle. update(speed, 1.0) is the only public
            // way to advance the phase, as WalkAnimationState exposes no position setter.
            puppet.walkAnimation.update(living.walkAnimation.speed(), 1.0f);
        }
        // some DMZ animations also gate on horizontal velocity, so copy the driver's delta movement too.
        puppet.setDeltaMovement(driver.getDeltaMovement());

        // ground state: this is the real cause of the permanent "falling" pose. DMZ's player-model animator
        // (PlayerGeoAnimatableMixin.predicate) gates its ENTIRE idle/walk/run branch behind player.onGround(); when
        // onGround is false it falls straight through to the airborne branch and returns FLY_IDLE (descending) or
        // JUMP, which is the floating/falling look the clones showed. A hand-positioned RemotePlayer is never
        // physics-ticked, so its onGround stays false forever no matter where the driver actually stands. Mirror the
        // driver's real ground state (and its fall distance, so DMZ's landing lead-in can resolve) so the puppet
        // picks the same animation the driver's own state would.
        puppet.setOnGround(driver.onGround());
        puppet.fallDistance = driver.fallDistance;

        // KEEP THIS. tickCount is the live clock GeckoLib feeds into its animation time (currentTick + partial
        // tick); it is NOT the interpolation bug and removing it freezes every GeckoLib animation dead. it sits
        // next to the position copies and looks like a suspect, so this note stays to stop it being deleted.
        puppet.tickCount = driver.tickCount;
    }

    @FunctionalInterface
    interface PuppetConsumer
    {
        void accept(GuildRaidPuppet puppet, Entity driver);
    }
}
