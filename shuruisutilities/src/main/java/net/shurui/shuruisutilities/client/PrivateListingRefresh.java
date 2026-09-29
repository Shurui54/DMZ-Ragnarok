package net.shurui.shuruisutilities.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import net.shurui.dev.sdu.api.ClientGate;

/**
 * Re-applies the private-item listings when the server's synced key answer changes.
 *
 * <p>The creative tabs are built once per connection and cached, and JEI can build them (and its own ingredient
 * list) BEFORE the login key packet lands: vanilla sends recipes and tags ahead of {@code PlayerLoggedInEvent}. Without
 * this, a keyed server's private items could stay hidden until a relog. So every client tick compares the synced
 * answer (licence plus key features) with the last one applied; on a change it rebuilds the tab contents and tells the
 * listeners (the optional JEI plugin). It costs one string compare per tick otherwise.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PrivateListingRefresh
{
    private PrivateListingRefresh() {}

    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    /** The answer the listings were last built for. Starts at the locked default, which is what they build with. */
    private static String applied = signature();

    /** Called on every change of the synced answer (on the client thread). */
    public static void addListener(Runnable r)
    {
        if (r != null)
            LISTENERS.add(r);
    }

    private static String signature()
    {
        List<String> ids = new java.util.ArrayList<>(ClientGate.features());
        java.util.Collections.sort(ids);
        return ClientGate.key() + "|" + String.join(",", ids);
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        String now = signature();
        if (now.equals(applied))
            return;
        applied = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null)
        {
            // CreativeModeTabs only rebuilds when its parameters change, and has no public "rebuild now". Asking once
            // with the operator flag flipped and once with the real one forces exactly one fresh build with the real
            // parameters (the same ones the creative screen passes, so opening it does not rebuild a third time).
            boolean op = mc.player.canUseGameMasterBlocks() && mc.options.operatorItemsTab().get();
            var flags = mc.player.connection.enabledFeatures();
            var access = mc.level.registryAccess();
            CreativeModeTabs.tryRebuildTabContents(flags, !op, access);
            CreativeModeTabs.tryRebuildTabContents(flags, op, access);
        }
        // The dragon balls and blocks tabs pick a different icon without the key; a tab caches its icon, so drop it.
        for (var tab : java.util.List.of(net.shurui.shuruisutilities.content.ContentTabs.DRAGON_BALLS,
                net.shurui.shuruisutilities.content.ContentTabs.BLOCKS_MISC))
            tab.ifPresent(t -> ((net.shurui.shuruisutilities.core.mixin.client.AccessorCreativeModeTabIcon) t)
                    .su$setIconItemStack(null));
        for (Runnable r : LISTENERS)
        {
            try
            {
                r.run();
            }
            catch (Throwable ignored)
            {
                // A listing that fails to refresh must never take the client tick down with it.
            }
        }
    }
}
