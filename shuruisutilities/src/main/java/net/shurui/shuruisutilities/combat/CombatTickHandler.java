package net.shurui.shuruisutilities.combat;

import net.minecraft.server.MinecraftServer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * Drives the dash and the clash once per SERVER tick.
 *
 * <p>Server tick, not level tick, and that distinction is the whole reason this class exists in its current form. Both
 * services key their state by player UUID, and a level tick handler runs once per loaded dimension. Driving them from
 * there meant every dimension's tick asked "is this dasher in MY level", and the ones that were not, which is all of
 * them but one, dropped the dash on the floor. With more than one dimension loaded, which is always, a dash died within
 * a tick of starting and looked like the key was not bound at all.
 *
 * <p>Order matters and is fixed here: dashes advance first, then the clash looks for two of them that have just met.
 * Running the detection first would test last tick's positions against this tick's headings and let a clash form a tick
 * after the two players had already passed through each other.
 */
public final class CombatTickHandler
{
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return;
        DashService.tick(server);
        // Before the clash: a crash in flight can carry someone into a clash partner, and the detection below
        // should test where this tick's momentum actually put them.
        SonicCrash.tick(server);
        MeleeClashService.tick(server);
        // NPC lunges run LAST, after the player dash has been advanced and after any clash it might feed into has
        // been ticked, so a lunge tests against this tick's player state rather than last tick's.
        NpcDash.tick(server);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event)
    {
        // Both hold players by UUID and the clash holds live entities. Neither survives a restart meaningfully, and a
        // struggle left in the map would hold two players still the moment the server came back.
        MeleeClashService.clear();
        DashService.clear();
        SonicCrash.clear();
        SonicBoomService.clear();
        // (Held ocarinas are the Ragnarok Key's: it puts them away on its own ServerStoppingEvent.)
        // Drop every dimension's terrain-regen debt too. On an integrated client that opens several worlds in one
        // session the static maps would otherwise carry one world's owed blocks and tallies into the next.
        net.shurui.shuruisutilities.regen.TerrainRegenService.forgetAll();
    }
}
