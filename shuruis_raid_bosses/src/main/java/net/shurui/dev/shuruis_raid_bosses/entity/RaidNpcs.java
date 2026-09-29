package net.shurui.dev.shuruis_raid_bosses.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.shurui.dev.shuruis_raid_bosses.Config;
import net.shurui.dev.shuruis_raid_bosses.data.RaidData;
import net.shurui.dev.shuruis_raid_bosses.network.OpenSignupPacket;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidInstance;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidManager;
import net.shurui.dev.shuruis_raid_bosses.registry.ModEntities;

/**
 * A raid sign-up "NPC" is any entity tagged (in its Forge persistent data) with a raid id. A global
 * interact handler opens that raid's sign-up GUI for any tagged entity, so DMZ, sdu or vanilla mobs all
 * work as hosts.
 */
public final class RaidNpcs {
    public static final String TAG = "SrbRaid";

    /**
     * Currently-loaded sign-up hosts per level, so {@link #tickLook} touches only the handful of hosts
     * instead of scanning (and reading persistent data on) every entity every tick. Populated on spawn and
     * tagging, and on {@code EntityJoinLevelEvent} (chunk reload / restart); pruned on
     * {@code EntityLeaveLevelEvent} / removal. All access is on the server thread, so a plain map is safe.
     */
    private static final java.util.Map<ServerLevel, java.util.Set<java.util.UUID>> HOSTS =
            new java.util.HashMap<>();

    private RaidNpcs() {}

    public static String raidOf(Entity entity) {
        var data = entity.getPersistentData();
        return data.contains(TAG) ? data.getString(TAG) : null;
    }

    public static void tag(Entity entity, String raidId) {
        entity.getPersistentData().putString(TAG, raidId);
        register(entity); // a command tags an already-joined entity, so no join event fires to catch it
    }

    /** Track a host if it carries the raid tag. Idempotent; safe to call from spawn, tagging and join. */
    public static void register(Entity entity) {
        if (!(entity.level() instanceof ServerLevel sl)) return;
        if (raidOf(entity) == null) return;
        HOSTS.computeIfAbsent(sl, k -> new java.util.HashSet<>()).add(entity.getUUID());
    }

    /** Stop tracking a host (leave/removal). Safe when it was never tracked. */
    public static void unregister(Entity entity) {
        if (!(entity.level() instanceof ServerLevel sl)) return;
        java.util.Set<java.util.UUID> set = HOSTS.get(sl);
        if (set != null) {
            set.remove(entity.getUUID());
            if (set.isEmpty()) HOSTS.remove(sl);
        }
    }

    /** Drop the whole registry (server stop), so a world reload starts clean. */
    public static void clearRegistry() {
        HOSTS.clear();
    }

    /** Spawn the configured entity type for a raid, tagged and set up as a stationary host. */
    public static Entity spawn(ServerLevel level, RaidBossDef def, Vec3 pos, float yaw) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(def.npcEntityType));
        if (type == null) type = ModEntities.RAID_NPC.get();
        Entity entity = type.create(level);
        if (entity == null) return null;
        entity.moveTo(pos.x, pos.y, pos.z, yaw, 0);
        // A ragnarok NPC host wears whichever of the 386 the def picked; other entity types ignore this.
        net.shurui.shuruisutilities.ragnarok.RgNpcLook.apply(entity, def.npcModelId);
        tag(entity, def.id);
        if (def.npcName != null && !def.npcName.isBlank()) {
            entity.setCustomName(Component.literal(def.npcName));
            entity.setCustomNameVisible(true);
        }
        entity.setInvulnerable(true);
        if (entity instanceof Mob mob) {
            mob.setNoAi(true);
            mob.setPersistenceRequired();
        }
        level.addFreshEntity(entity);
        return entity;
    }

    /** How many degrees a host may turn per tick, so the head-turn eases in instead of snapping. */
    private static final float MAX_TURN_PER_TICK = 20.0f;

    /**
     * Turn every sign-up host to face the nearest player within {@link Config#NPC_LOOK_RADIUS}. Runs each
     * tick because hosts have AI disabled (the vanilla look goal never fires) and can be any entity type.
     */
    public static void tickLook(MinecraftServer server) {
        double radius = Config.NPC_LOOK_RADIUS.get();
        if (radius <= 0) return;
        for (ServerLevel level : server.getAllLevels()) {
            if (level.players().isEmpty()) continue;
            java.util.Set<java.util.UUID> hosts = HOSTS.get(level);
            if (hosts == null || hosts.isEmpty()) continue;
            // Iterate a snapshot: faceNearestPlayer never mutates the set, but this is cheap insurance.
            for (java.util.UUID id : hosts.toArray(new java.util.UUID[0])) {
                if (!(level.getEntity(id) instanceof Mob mob)) continue;
                faceNearestPlayer(mob, level, radius);
            }
        }
    }

    private static void faceNearestPlayer(Mob mob, ServerLevel level, double radius) {
        Player target = level.getNearestPlayer(mob.getX(), mob.getEyeY(), mob.getZ(), radius,
                e -> e instanceof Player p && p.isAlive() && !p.isSpectator());
        if (target == null) return;
        double dx = target.getX() - mob.getX();
        double dz = target.getZ() - mob.getZ();
        double dy = target.getEyeY() - mob.getEyeY();
        float wantYaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0f;
        float wantPitch = (float) (-(Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI)));
        float yaw = mob.getYRot() + Mth.clamp(Mth.wrapDegrees(wantYaw - mob.getYRot()), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        float pitch = mob.getXRot() + Mth.clamp(Mth.wrapDegrees(wantPitch - mob.getXRot()), -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK);
        mob.setYRot(yaw);
        mob.setYBodyRot(yaw);
        mob.setYHeadRot(yaw);
        mob.setXRot(pitch);
    }

    /** Find a raid's sign-up NPC anywhere on the server (for /rg raid join teleport). */
    public static Entity find(MinecraftServer server, String raidId) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (raidId.equalsIgnoreCase(raidOf(entity))) return entity;
            }
        }
        return null;
    }

    /**
     * Discard every tagged sign-up host on the server. When {@code raidId} is null all raid NPCs are
     * removed; otherwise only hosts bound to that raid. Returns how many were removed.
     */
    public static int removeAll(MinecraftServer server, String raidId) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                String tag = raidOf(entity);
                if (tag == null) continue;
                if (raidId != null && !raidId.equalsIgnoreCase(tag)) continue;
                entity.discard();
                removed++;
            }
        }
        return removed;
    }

    /** Open the sign-up GUI for a specific raid id. */
    public static void openSignup(ServerPlayer player, String raidId) {
        RaidBossDef def = RaidData.get(player.getServer()).getDef(raidId);
        if (def == null) return;
        RaidManager m = RaidManager.get();
        RaidInstance inst = m == null ? null : m.instance(def.id);
        RaidNet.sendToPlayer(new OpenSignupPacket(
                def.id, def.name,
                inst != null && inst.isSignupOpen(),
                inst != null && inst.isSignedUp(player.getUUID()),
                inst != null ? inst.signupCount() : 0,
                inst != null ? inst.state().ordinal() : 0), player);
    }

    /** Open the browser of every joinable raid (grouped into categories) for a raid host's menu. */
    public static void openBrowser(ServerPlayer player) {
        RaidManager m = RaidManager.get();
        java.util.List<net.shurui.dev.shuruis_raid_bosses.network.OpenBrowserPacket.Entry> entries = new java.util.ArrayList<>();
        for (RaidBossDef def : RaidData.get(player.getServer()).allDefs().values()) {
            if (!def.showInNpcMenu) continue;
            // Hide an eventOnly raid unless its timed event is active right now (keyless: always hidden).
            if (def.eventOnly && !net.shurui.dev.sdu.api.key.EventHooks.get().raidRunnable(def.id)) continue;
            RaidInstance inst = m == null ? null : m.instance(def.id);
            int state = inst != null ? inst.state().ordinal() : 0;
            entries.add(new net.shurui.dev.shuruis_raid_bosses.network.OpenBrowserPacket.Entry(
                    def.category == null || def.category.isBlank() ? "General" : def.category,
                    def.id, def.name, def.raidType.ordinal(), state));
        }
        RaidNet.sendToPlayer(new net.shurui.dev.shuruis_raid_bosses.network.OpenBrowserPacket(entries), player);
    }
}
