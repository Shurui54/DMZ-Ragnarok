package net.shurui.shuruisutilities.cosmetics.wardrobe.client;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticAnimation;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticSlot;
import net.shurui.shuruisutilities.cosmetics.wardrobe.network.PacketCosmeticAnimationPlay;
import net.shurui.shuruisutilities.particle.RgParticles;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The client's list of triggered cosmetic animations playing right now.
 *
 * <p>CLIENT ONLY, and self-expiring. Each entry holds a snapshot of the animation, a world position and an age;
 * {@link #tick()} emits its particles and retires it on its own duration. Nothing here is authority: the server
 * decided who plays what, and if this client disconnects mid-animation it loses a visual and nothing else.
 *
 * <p>An effect is presented one of two ways, chosen at {@link #accept}: a {@link CosmeticAnimation#STYLE_GEO}
 * effect that names a rig draws the real GeckoLib model ({@link CosmeticAnimGeoRenderer}) at
 * {@code RenderLevelStageEvent}; everything else draws particles (our tintable {@code tinted_dust} /
 * {@code tinted_glow}) here on the tick. A geo effect past the rig cap, or with rigs disabled, falls back to its
 * particle style, so a bad or missing model degrades to particles rather than crashing. A missing SOUND id is
 * silence, never a crash.
 *
 * <h2>Caps live here, where the render cost is</h2>
 * At most {@link #MAX_ACTIVE} effects play at once per viewer; a new one past that replaces the oldest. Of those,
 * at most {@link #MAX_ACTIVE_RIGS} draw a heavy rig, the rest fall back to particles. Twenty people joining spawn
 * therefore draw six, at most four as rigs. The server still sends twenty small packets, which is nothing.
 */
public final class CosmeticAnimationClientStore
{
    private CosmeticAnimationClientStore()
    {
    }

    /** The most animations drawn at once for this viewer. See the class note: the real performance protection. */
    public static final int MAX_ACTIVE = 6;

    /**
     * The most RIGS (real GeckoLib models) drawn at once. A rig is far heavier than particles, so past this cap a
     * geo effect draws its {@link CosmeticAnimation#geoFallbackStyle} particles instead. The total is still bounded
     * by {@link #MAX_ACTIVE}.
     */
    public static final int MAX_ACTIVE_RIGS = 4;

    /** Ring of GeckoLib animation-instance ids so the animatable's instance cache cannot grow without bound. */
    private static final int RIG_ID_RING = 16;

    private static final List<ActiveFx> ACTIVE = new ArrayList<>();

    private static final RandomSource RANDOM = RandomSource.create();

    private static long nextRigId;

    /** One playing animation. */
    private static final class ActiveFx
    {
        final CosmeticAnimation anim;
        final UUID subject;
        final CosmeticSlot trigger;
        final ResourceLocation dimension;
        final Vec3 pos;
        final float yaw;
        final int duration;
        /** True when this effect drew a rig slot at accept time; if false it presents as particles. */
        final boolean geo;
        /** The GeckoLib instance id for this rig (ring-allocated), used only when {@link #geo}. */
        final long rigId;
        int age;
        boolean soundPlayed;
        /** The rig has been triggered once, on its first render pass. */
        boolean rigTriggered;

        ActiveFx(CosmeticAnimation anim, UUID subject, CosmeticSlot trigger, ResourceLocation dimension, Vec3 pos,
                float yaw, int duration, boolean geo, long rigId)
        {
            this.anim = anim;
            this.subject = subject;
            this.trigger = trigger;
            this.dimension = dimension;
            this.pos = pos;
            this.yaw = yaw;
            this.duration = duration;
            this.geo = geo;
            this.rigId = rigId;
        }
    }

    /** A play packet has arrived. Resolve it against the catalogue, honour the client options, and enqueue it. */
    public static void accept(PacketCosmeticAnimationPlay packet)
    {
        if (packet == null)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null)
            return;
        // Wrong dimension: the packet was sent shard-wide by position, so a viewer in another dimension discards it.
        if (packet.dimension == null || !mc.level.dimension().location().equals(packet.dimension))
            return;
        CosmeticAnimationClientOptions.Mode viewMode = CosmeticAnimationClientOptions.mode();
        if (viewMode == CosmeticAnimationClientOptions.Mode.OFF)
            return;
        boolean isSelf = packet.subject != null && packet.subject.equals(mc.player.getUUID());
        if (viewMode == CosmeticAnimationClientOptions.Mode.SELF && !isSelf)
            return;
        CosmeticDef def = CosmeticClientStore.def(packet.catalogId);
        if (def == null || !def.enabled || def.animation == null || !def.animation.valid())
        {
            // A stale or missing catalogue entry is silence, not a crash, and is the normal state for a few hundred
            // milliseconds after joining. Not logged: it would spam on every join before the catalogue lands.
            return;
        }
        Vec3 pos = new Vec3(packet.x, packet.y, packet.z);
        double clientRadius = CosmeticAnimationClientOptions.radius();
        if (mc.player.position().distanceToSqr(pos) > clientRadius * clientRadius)
            return;
        CosmeticSlot trigger = CosmeticSlot.byKey(packet.triggerKey);
        int duration = Mth.clamp(packet.durationTicks, 1, CosmeticAnimation.MAX_DURATION);
        CosmeticAnimation anim = def.animation.copy();
        synchronized (ACTIVE)
        {
            if (ACTIVE.size() >= MAX_ACTIVE)
                ACTIVE.remove(0);
            // Claim a rig slot only if this effect names a rig, the client draws rigs, and the simultaneous-rig cap
            // is not reached; otherwise it presents as particles (its geoFallbackStyle). Decided once, at accept.
            boolean hasGeo = anim.hasGeo();
            boolean rigsOn = CosmeticAnimationClientOptions.rigs();
            boolean available = hasGeo && CosmeticAnimGeoRenderer.rigAvailable(anim.geoRig);
            boolean underCap = activeRigCount() < MAX_ACTIVE_RIGS;
            boolean geo = hasGeo && rigsOn && available && underCap;
            long rigId = geo ? (nextRigId++ % RIG_ID_RING) : -1L;
            logDecisionOnce(packet.catalogId, anim, geo, hasGeo, rigsOn, available, underCap);
            ACTIVE.add(new ActiveFx(anim, packet.subject, trigger, packet.dimension, pos, packet.yaw,
                    duration, geo, rigId));
        }
    }

    /** One-shot guard so the geo/particle decision for a catalogue id is logged once per session, not every accept. */
    private static final java.util.Set<String> DECISION_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Log the geo-versus-particle decision for a catalogue id ONCE, with the reason, so the next report is
     * diagnosable from a log rather than guesswork. A record that draws its rig logs "geo"; anything else logs why
     * it fell back (not a geo record and so no rig named, rigs toggled off, the rig not baked on this client, or the
     * simultaneous-rig cap reached). rigAvailable itself logs the exact missing keys once per key.
     */
    private static void logDecisionOnce(String catalogId, CosmeticAnimation anim, boolean geo, boolean hasGeo,
            boolean rigsOn, boolean available, boolean underCap)
    {
        if (catalogId == null || !DECISION_LOGGED.add(catalogId))
            return;
        if (geo)
        {
            LoggingHandler.sulog.info("[Cosmetics] Animation '{}' draws its GEO rig '{}' (style={}).",
                    catalogId, anim.geoRig, anim.style);
            return;
        }
        String why = !hasGeo
                ? ("no rig on this record (style=" + anim.style + ", geoRig='" + anim.geoRig
                        + "'): drawing particle style " + anim.particleStyle())
                : !rigsOn ? "rigs disabled by /cosmeticanims rigs off"
                : !available ? "rig '" + anim.geoRig + "' not baked on this client (see the rig-unavailable line)"
                : !underCap ? "simultaneous-rig cap reached"
                : "unknown";
        LoggingHandler.sulog.info("[Cosmetics] Animation '{}' falls back to particles: {}.", catalogId, why);
    }

    /** How many active effects currently hold a rig slot. Called under the {@link #ACTIVE} lock. */
    private static int activeRigCount()
    {
        int n = 0;
        for (ActiveFx fx : ACTIVE)
            if (fx.geo)
                n++;
        return n;
    }

    /** Age every active FX one tick, emit its particles and play its sound once. Called on the client tick. */
    public static void tick()
    {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        synchronized (ACTIVE)
        {
            if (ACTIVE.isEmpty())
                return;
            if (level == null)
            {
                ACTIVE.clear();
                return;
            }
            // The count in flight drives a gentle dampener so a crowd of arrivals does not become fill-limited.
            int concurrent = ACTIVE.size();
            java.util.Iterator<ActiveFx> it = ACTIVE.iterator();
            while (it.hasNext())
            {
                ActiveFx fx = it.next();
                if (!level.dimension().location().equals(fx.dimension))
                {
                    it.remove();
                    continue;
                }
                if (!fx.soundPlayed)
                {
                    fx.soundPlayed = true;
                    playSound(mc, fx);
                }
                // A rig effect is drawn at RenderLevelStageEvent by renderGeo, not as particles. It still ages and
                // plays its sound here; only the particle emission is skipped.
                if (!fx.geo)
                    emit(level, fx, concurrent);
                fx.age++;
                if (fx.age >= fx.duration)
                    it.remove();
            }
        }
    }

    /**
     * Draw every active RIG effect this frame, at its world position and oriented to the subject's yaw. Called from
     * {@link CosmeticAnimGeoRenderHook} at {@code RenderLevelStageEvent.AFTER_ENTITIES}. Particle effects are NOT
     * touched here; they are emitted on the client tick. A subject that is loaded and invisible draws nothing.
     */
    public static void renderGeo(com.mojang.blaze3d.vertex.PoseStack poseStack,
            net.minecraft.client.renderer.MultiBufferSource buffer, Vec3 cam, float partialTick)
    {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null)
            return;
        List<ActiveFx> toDraw = null;
        synchronized (ACTIVE)
        {
            for (ActiveFx fx : ACTIVE)
            {
                if (!fx.geo || !level.dimension().location().equals(fx.dimension))
                    continue;
                if (toDraw == null)
                    toDraw = new ArrayList<>(MAX_ACTIVE_RIGS);
                toDraw.add(fx);
            }
        }
        if (toDraw == null)
            return;
        for (ActiveFx fx : toDraw)
        {
            // Nothing drawn for an invisible subject (vanished staff, spectator): only skip when the entity is
            // actually loaded, so a distant subject we cannot see is still drawn for others normally.
            net.minecraft.world.entity.player.Player subject =
                    fx.subject == null ? null : level.getPlayerByUUID(fx.subject);
            if (subject != null && subject.isInvisible())
                continue;
            boolean arriving = CosmeticAnimation.arriving(fx.trigger);
            boolean firstDraw = !fx.rigTriggered;
            fx.rigTriggered = true;
            CosmeticAnimGeoRenderer.INSTANCE.draw(poseStack, buffer,
                    fx.pos.x - cam.x, fx.pos.y - cam.y, fx.pos.z - cam.z, fx.yaw,
                    fx.anim.geoRig, arriving, fx.anim.geoScale, fx.rigId, firstDraw);
        }
    }

    /**
     * A snapshot of the geo effect currently playing on a subject, so the real player can be posed to perform it.
     * {@code progress} is the effect's clip progress 0..1, smoothed with the partial tick.
     */
    public record ActivePose(String rigKey, boolean arriving, float progress)
    {
    }

    /**
     * The geo effect that should drive the REAL player's pose for {@code subject} right now, or null when nothing is.
     * Only GEO effects contribute: a particle fallback draws no rig, so animating the player would be a performance
     * with no matching visual. If a subject somehow has more than one geo effect at once (the caps make that rare),
     * the most recently started one wins, matching what is drawn on top. Called once per player render from
     * {@link CosmeticAnimPlayerPoser}.
     */
    public static ActivePose activePose(UUID subject, float partialTick)
    {
        if (subject == null)
            return null;
        ActivePose pose = null;
        synchronized (ACTIVE)
        {
            for (ActiveFx fx : ACTIVE)
            {
                if (!fx.geo || fx.subject == null || !fx.subject.equals(subject))
                    continue;
                // (age + partialTick)/duration is the clip progress, smoothed between ticks: durationFor a geo effect
                // IS the per-direction clip length, so the pose stays in step with the rig's actived clip.
                float progress = fx.duration <= 1 ? 1.0F : (fx.age + partialTick) / (float) fx.duration;
                if (progress < 0.0F)
                    progress = 0.0F;
                else if (progress > 1.0F)
                    progress = 1.0F;
                pose = new ActivePose(fx.anim.geoRig, CosmeticAnimation.arriving(fx.trigger), progress);
            }
        }
        return pose;
    }

    /** Wipe on logout, so the next server is not read through this one's list. */
    public static void clear()
    {
        synchronized (ACTIVE)
        {
            ACTIVE.clear();
        }
        // Let the one-shot diagnostics report again on the next server, so a reconnect re-states the decision.
        DECISION_LOGGED.clear();
    }

    private static void playSound(Minecraft mc, ActiveFx fx)
    {
        String soundId = fx.anim.soundFor(fx.trigger);
        if (soundId == null || soundId.isBlank())
            return;
        ResourceLocation rl = ResourceLocation.tryParse(soundId);
        if (rl == null)
            return;
        try
        {
            // A client-initiated play needs no registry entry: a variable-range SoundEvent built from the id
            // resolves against sounds.json at play time, and an id with no entry is silence rather than a crash.
            net.minecraft.sounds.SoundEvent event = net.minecraft.sounds.SoundEvent.createVariableRangeEvent(rl);
            SimpleSoundInstance inst = new SimpleSoundInstance(event, SoundSource.PLAYERS,
                    Mth.clamp(fx.anim.soundVolume, 0.0F, 1.0F), Mth.clamp(fx.anim.soundPitch, 0.5F, 2.0F), RANDOM,
                    fx.pos.x, fx.pos.y + 1.0D, fx.pos.z);
            mc.getSoundManager().play(inst);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Cosmetics] Could not play animation sound '{}': {}", soundId, t.toString());
        }
    }

    // -------------------------------------------------------------- particle emission

    private static void emit(ClientLevel level, ActiveFx fx, int concurrent)
    {
        CosmeticAnimation a = fx.anim;
        float progress = fx.duration <= 1 ? 1.0F : (float) fx.age / (float) fx.duration;
        // Base count per tick, thinned by the operator density dial and by how many are on screen at once.
        float dampen = concurrent >= 4 ? 0.6F : 1.0F;
        int count = Math.max(1, Math.round(6.0F * Mth.clamp(a.density, 0.0F, 4.0F) * dampen));
        int primary = a.colorPrimaryRgb();
        int secondary = a.colorSecondaryRgb();
        // The FX lives for the animation's remaining life, so a mote never outlives the effect.
        int life = Math.max(6, fx.duration - fx.age);
        // The effective particle style: a geo effect that fell back to particles draws its geoFallbackStyle.
        switch (a.particleStyle())
        {
            case CosmeticAnimation.STYLE_COLUMN:
                emitColumn(level, fx, count, primary, secondary, life, progress);
                break;
            case CosmeticAnimation.STYLE_RING:
                emitRing(level, fx, count, primary, secondary, life, progress);
                break;
            case CosmeticAnimation.STYLE_VORTEX:
                emitVortex(level, fx, count, primary, secondary, life, progress);
                break;
            case CosmeticAnimation.STYLE_GATE:
                emitGate(level, fx, count, primary, secondary, life, progress);
                break;
            case CosmeticAnimation.STYLE_BURST:
            default:
                emitBurst(level, fx, count, primary, secondary, life, progress);
                break;
        }
    }

    private static ParticleOptions dot(ActiveFx fx, int rgb, float scale, int life, float gravity)
    {
        return fx.anim.glow ? RgParticles.glow(rgb, scale, life, gravity) : RgParticles.dust(rgb, scale, life, gravity);
    }

    private static void emitColumn(ClientLevel level, ActiveFx fx, int count, int primary, int secondary, int life,
            float progress)
    {
        // A thin rising column at the feet: motes lift and drift outward slightly as they age.
        for (int i = 0; i < count; i++)
        {
            double ang = RANDOM.nextDouble() * Math.PI * 2.0;
            double r = 0.15 + RANDOM.nextDouble() * 0.45;
            double x = fx.pos.x + Math.cos(ang) * r;
            double z = fx.pos.z + Math.sin(ang) * r;
            double y = fx.pos.y + RANDOM.nextDouble() * 0.4;
            int rgb = i % 3 == 0 ? secondary : primary;
            level.addParticle(dot(fx, rgb, 0.9F, life, -0.25F), x, y, z,
                    Math.cos(ang) * 0.01, 0.14 + RANDOM.nextDouble() * 0.06, Math.sin(ang) * 0.01);
        }
    }

    private static void emitRing(ClientLevel level, ActiveFx fx, int count, int primary, int secondary, int life,
            float progress)
    {
        // A ring expanding along the ground as the effect ages.
        double radius = 0.3 + progress * 2.2;
        for (int i = 0; i < count; i++)
        {
            double ang = RANDOM.nextDouble() * Math.PI * 2.0;
            double x = fx.pos.x + Math.cos(ang) * radius;
            double z = fx.pos.z + Math.sin(ang) * radius;
            double y = fx.pos.y + 0.1 + RANDOM.nextDouble() * 0.2;
            int rgb = i % 4 == 0 ? secondary : primary;
            level.addParticle(dot(fx, rgb, 1.0F, life, 0.0F), x, y, z,
                    Math.cos(ang) * 0.02, 0.02, Math.sin(ang) * 0.02);
        }
    }

    private static void emitVortex(ClientLevel level, ActiveFx fx, int count, int primary, int secondary, int life,
            float progress)
    {
        // Motes spiral inward and sink: rot pulled down into the ground.
        double baseR = 1.6 * (1.0 - progress) + 0.3;
        for (int i = 0; i < count; i++)
        {
            double ang = fx.age * 0.35 + i * (Math.PI * 2.0 / Math.max(1, count));
            double r = baseR + RANDOM.nextDouble() * 0.3;
            double x = fx.pos.x + Math.cos(ang) * r;
            double z = fx.pos.z + Math.sin(ang) * r;
            double y = fx.pos.y + 0.9 * (1.0 - progress) + RANDOM.nextDouble() * 0.3;
            int rgb = i % 3 == 0 ? secondary : primary;
            // Velocity tangent plus inward, so the spiral reads as swirling in.
            double vx = -Math.sin(ang) * 0.05 - Math.cos(ang) * 0.03;
            double vz = Math.cos(ang) * 0.05 - Math.sin(ang) * 0.03;
            level.addParticle(dot(fx, rgb, 0.85F, life, 0.15F), x, y, z, vx, -0.02, vz);
        }
    }

    private static void emitBurst(ClientLevel level, ActiveFx fx, int count, int primary, int secondary, int life,
            float progress)
    {
        // A strong outward burst on the first few ticks, then sparse embers.
        int n = fx.age < 3 ? count * 2 : Math.max(1, count / 2);
        for (int i = 0; i < n; i++)
        {
            double ang = RANDOM.nextDouble() * Math.PI * 2.0;
            double pitch = (RANDOM.nextDouble() - 0.3) * Math.PI;
            double speed = 0.12 + RANDOM.nextDouble() * 0.18;
            double vx = Math.cos(ang) * Math.cos(pitch) * speed;
            double vy = Math.sin(pitch) * speed + 0.05;
            double vz = Math.sin(ang) * Math.cos(pitch) * speed;
            int rgb = i % 3 == 0 ? secondary : primary;
            level.addParticle(dot(fx, rgb, 0.9F, life, 0.05F),
                    fx.pos.x, fx.pos.y + 1.0, fx.pos.z, vx, vy, vz);
        }
    }

    private static void emitGate(ClientLevel level, ActiveFx fx, int count, int primary, int secondary, int life,
            float progress)
    {
        // A vertical doorway ring facing the way the player looked: an oval of fire the player steps through.
        double yawRad = Math.toRadians(fx.yaw);
        double rightX = Math.cos(yawRad);
        double rightZ = Math.sin(yawRad);
        double halfW = 0.9;
        double height = 2.2;
        for (int i = 0; i < count; i++)
        {
            double t = RANDOM.nextDouble() * Math.PI * 2.0;
            double ox = Math.cos(t) * halfW;
            double oy = 1.1 + Math.sin(t) * (height / 2.0);
            double x = fx.pos.x + rightX * ox;
            double z = fx.pos.z + rightZ * ox;
            double y = fx.pos.y + oy;
            int rgb = i % 3 == 0 ? secondary : primary;
            level.addParticle(dot(fx, rgb, 1.0F, life, -0.05F), x, y, z, 0.0, 0.01, 0.0);
        }
    }
}
