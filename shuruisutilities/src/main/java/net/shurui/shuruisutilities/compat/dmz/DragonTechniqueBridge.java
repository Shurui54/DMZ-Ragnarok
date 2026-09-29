package net.shurui.shuruisutilities.compat.dmz;

import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModList;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Guard entry point for registering the shadow dragon techniques and the summoned weapons' combat poses into DMZ.
 * Holds no DMZ imports itself: it only checks DMZ is present before touching the classes that name DMZ types
 * (the optional-dependency pattern).
 *
 * <p>Registers on {@code ServerAboutToStartEvent} rather than during mod setup, because DMZ populates its predefined
 * technique registry and rebuilds its weapon registry from its own data during load, and the ordering between two
 * mods' setup work is not guaranteed. Waiting until a server is starting puts us unambiguously after both, and
 * re-running per server start is harmless since both registrations are idempotent.
 *
 * <p>Registered by hand on the Forge bus from the mod's main class; annotation-driven subscribers keyed to one of
 * the five pre-merge modids register nothing and fail silently.
 */
public final class DragonTechniqueBridge
{
    @SubscribeEvent
    public void onServerAboutToStart(ServerAboutToStartEvent event)
    {
        if (!ModList.get().isLoaded("dragonminez"))
            return;
        try
        {
            DragonTechniqueDefs.register();
        }
        catch (Throwable t)
        {
            // A DMZ internals change must not stop the server booting; the moves simply will not appear.
            LoggingHandler.sulog.warn("[dragons] shadow dragon techniques not registered: {}", t.toString());
        }
        try
        {
            MiniCloneTechniqueDefs.register();
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[miniclone] mini clone technique not registered: {}", t.toString());
        }
        try
        {
            int added = KiWeaponTypes.register();
            LoggingHandler.sulog.info("[kiweapon] registered {} Ragnarok ki weapon types", added);
            registerWeaponPoses();
        }
        catch (Throwable t)
        {
            // Separate from the technique registration on purpose: losing the poses is cosmetic and must not take
            // the techniques down with it.
            LoggingHandler.sulog.warn("[kiweapon] combat poses not registered: {}", t.toString());
        }
    }

    /**
     * Bring a joining player's STORED copies of our techniques back in line with the current definitions.
     *
     * <p>DMZ keeps each unlocked technique in full in player NBT and never re-reads the registry for it, so without
     * this pass a character keeps whatever shape, size, colours and animation the move had on the day they unlocked
     * it. See {@link TechniqueRefresh} for the whole mechanism.
     *
     * <p>On login rather than on registration, because registration happens before anyone has joined.
     */
    @SubscribeEvent
    public void onPlayerLogin(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event)
    {
        if (!ModList.get().isLoaded("dragonminez"))
            return;
        if (event.getEntity() instanceof net.minecraft.server.level.ServerPlayer player)
        {
            TechniqueRefresh.refreshAll(player);
            // AND AGAIN SHORTLY. Whether DMZ's own stats capability is fully deserialised by the time this event
            // fires is not something an addon gets to rely on, and a refresh that runs against an empty technique
            // map silently does nothing - leaving the player on stale definitions for the whole session. The second
            // pass costs one NBT comparison per move when the first already worked.
            pendingRefresh.put(player.getUUID(), REFRESH_RETRY_TICKS);
        }
    }

    /** Players whose refresh is to be tried once more, and how many ticks until then. */
    private final java.util.Map<java.util.UUID, Integer> pendingRefresh = new java.util.HashMap<>();

    /** Two seconds: long enough for any login-time capability work to have settled. */
    private static final int REFRESH_RETRY_TICKS = 40;

    @SubscribeEvent
    public void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event)
    {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END || pendingRefresh.isEmpty())
            return;
        net.minecraft.server.MinecraftServer server = event.getServer();
        if (server == null)
            return;
        for (java.util.Iterator<java.util.Map.Entry<java.util.UUID, Integer>> it = pendingRefresh.entrySet().iterator();
             it.hasNext(); )
        {
            java.util.Map.Entry<java.util.UUID, Integer> entry = it.next();
            int left = entry.getValue() - 1;
            if (left > 0)
            {
                entry.setValue(left);
                continue;
            }
            net.minecraft.server.level.ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            it.remove();
            if (player != null)
                TechniqueRefresh.refreshAll(player);
        }
    }

    /**
     * Give the summoned weapons their DMZ combat poses: the staff swings two-handed like a staff.
     *
     * <p>This is all it takes, because {@code CombatAnimationResolver.resolvePlayerPose} reads the pose straight out
     * of {@code WeaponRegistry.getAttributes(mainHandItem)}. No packet and no client-side animation call - the
     * weapons animate down the same path DMZ's own weapons do.
     */
    private static void registerWeaponPoses()
    {
        // Poses for the ki weapon TYPES, keyed by the same id DMZ resolves their models from.
        for (net.shurui.shuruisutilities.god.RagnarokKiWeapon weapon
                : net.shurui.shuruisutilities.god.RagnarokKiWeapon.values())
        {
            KiWeaponPose.register(new net.minecraft.resources.ResourceLocation("dragonminez", weapon.type),
                    KiWeaponPose.POSE_STAFF, true, 4.0, "kiweapon");
        }
    }
}
