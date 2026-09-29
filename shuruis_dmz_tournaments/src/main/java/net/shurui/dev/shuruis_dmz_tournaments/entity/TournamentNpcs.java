package net.shurui.dev.shuruis_dmz_tournaments.entity;

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
import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.data.TournamentData;
import net.shurui.dev.shuruis_dmz_tournaments.network.OpenBrowserPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.OpenSignupPacket;
import net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet;
import net.shurui.dev.shuruis_dmz_tournaments.registry.ModEntities;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentDef;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentInstance;
import net.shurui.dev.shuruis_dmz_tournaments.tournament.TournamentManager;

/**
 * A tournament sign-up "NPC" is any registered entity tagged (in its Forge persistent data) with a tournament
 * id. A global interact handler opens the sign-up GUI for any tagged entity, so DMZ, sdu or vanilla mobs all
 * work as hosts.
 */
public final class TournamentNpcs {
    /** Legacy tag: entity is bound to a single tournament and opens that sign-up screen directly. */
    public static final String TAG = "SdtTournament";
    /** Browser tag: entity opens the tournament browser, filtered to this group ("" = all groups). */
    public static final String GROUP_TAG = "SdtBrowse";

    /**
     * Currently-loaded sign-up hosts per level, so {@link #tickLook} touches only the handful of hosts
     * instead of scanning (and reading persistent data on) every entity every tick. Populated on spawn and
     * tagging, and on {@code EntityJoinLevelEvent} (chunk reload / restart); pruned on
     * {@code EntityLeaveLevelEvent} / removal. All access is on the server thread, so a plain map is safe.
     */
    private static final java.util.Map<ServerLevel, java.util.Set<java.util.UUID>> HOSTS =
            new java.util.HashMap<>();

    private TournamentNpcs() {}

    /** Track a host if it carries a tournament tag. Idempotent; safe from spawn, tagging and join. */
    public static void register(Entity entity) {
        if (!(entity.level() instanceof ServerLevel sl)) return;
        if (!isHost(entity)) return;
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

    public static String tournamentOf(Entity entity) {
        var data = entity.getPersistentData();
        return data.contains(TAG) ? data.getString(TAG) : null;
    }

    /** The browser group filter for this entity, or null if it is not a browser NPC. */
    public static String browseGroupOf(Entity entity) {
        var data = entity.getPersistentData();
        return data.contains(GROUP_TAG) ? data.getString(GROUP_TAG) : null;
    }

    public static void tag(Entity entity, String tournamentId) {
        entity.getPersistentData().putString(TAG, tournamentId);
        register(entity); // a command tags an already-joined entity, so no join event fires to catch it
    }

    /** Make an entity a browser NPC filtered to the given group ("" = show all groups). */
    public static void tagBrowse(Entity entity, String group) {
        entity.getPersistentData().remove(TAG);
        entity.getPersistentData().putString(GROUP_TAG, group == null ? "" : group);
        register(entity); // a command re-tags an already-joined entity, so no join event fires to catch it
    }

    /** Spawn the configured entity type for a tournament, tagged and set up as a stationary host. */
    public static Entity spawn(ServerLevel level, TournamentDef def, Vec3 pos, float yaw) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(ResourceLocation.tryParse(def.npcEntityType));
        if (type == null) type = ModEntities.TOURNAMENT_NPC.get();
        Entity entity = type.create(level);
        if (entity == null) return null;
        entity.moveTo(pos.x, pos.y, pos.z, yaw, 0);
        // a ragnarok NPC host wears whichever of the 386 the def picked; every other entity type ignores this
        net.shurui.shuruisutilities.ragnarok.RgNpcLook.apply(entity, def.npcModelId);
        // new NPCs are browsers: list every tournament in the def's group ("" = all)
        tagBrowse(entity, def.group);
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
     * Turn every sign-up host to face the nearest player within {@link Config#NPC_LOOK_RADIUS}. Runs each server
     * tick because hosts have AI disabled (the vanilla look goal never fires) and a host can be any entity type.
     * Rotation is capped per tick so the turn eases in.
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

    /** True if this entity is a tournament sign-up host (a bound sign-up NPC or a browser NPC). */
    public static boolean isHost(Entity entity) {
        return browseGroupOf(entity) != null || tournamentOf(entity) != null;
    }

    /** Discard every tournament sign-up host on the server. @return how many were removed. */
    public static int removeAll(MinecraftServer server) {
        int removed = 0;
        for (ServerLevel level : server.getAllLevels()) {
            java.util.List<Entity> hosts = new java.util.ArrayList<>();
            for (Entity entity : level.getEntities().getAll()) if (isHost(entity)) hosts.add(entity);
            for (Entity entity : hosts) { entity.discard(); removed++; }
        }
        return removed;
    }

    /** Find a sign-up host for a tournament anywhere on the server (for /rg tourney join teleport). */
    public static Entity find(MinecraftServer server, TournamentDef def) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getEntities().getAll()) {
                if (def.id.equalsIgnoreCase(tournamentOf(entity))) return entity;   // legacy bound host
                String group = browseGroupOf(entity);
                if (group != null && (group.isEmpty() || group.equalsIgnoreCase(def.group))) return entity;
            }
        }
        return null;
    }

    /** Open the sign-up GUI for a specific tournament (with a fresh state + team snapshot). */
    public static void openSignup(ServerPlayer player, String tournamentId) {
        TournamentDef def = TournamentData.get(player.getServer()).getDef(tournamentId);
        if (def == null) return;
        TournamentManager m = TournamentManager.get();
        TournamentInstance inst = m == null ? null : m.instance(def.id);
        TournamentNet.sendToPlayer(new OpenSignupPacket(
                def.id, def.name,
                inst != null && inst.isSignupOpen(),
                inst != null && inst.isSignedUp(player.getUUID()),
                inst != null ? inst.signupCount() : 0,
                inst != null ? inst.state().ordinal() : 0,
                def.format().ordinal(),
                def.format().teamSize(),
                inst != null ? inst.teamViews() : java.util.List.of()), player);
    }

    /** Open the tournament browser, listing every tournament in the given group ("" = all groups). */
    public static void openBrowser(ServerPlayer player, String groupFilter) {
        TournamentManager m = TournamentManager.get();
        java.util.List<OpenBrowserPacket.Entry> entries = new java.util.ArrayList<>();
        for (TournamentDef def : TournamentData.get(player.getServer()).allDefs().values()) {
            if (groupFilter != null && !groupFilter.isEmpty() && !groupFilter.equalsIgnoreCase(def.group)) continue;
            TournamentInstance inst = m == null ? null : m.instance(def.id);
            entries.add(new OpenBrowserPacket.Entry(
                    def.id, def.name,
                    (def.group == null || def.group.isBlank()) ? "Ungrouped" : def.group,
                    def.format().label(),
                    inst != null && inst.isSignupOpen(),
                    inst != null ? inst.signupCount() : 0));
        }
        TournamentNet.sendToPlayer(new OpenBrowserPacket(entries), player);
    }
}
