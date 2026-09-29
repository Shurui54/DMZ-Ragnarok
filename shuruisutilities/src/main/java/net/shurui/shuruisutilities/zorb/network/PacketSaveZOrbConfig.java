package net.shurui.shuruisutilities.zorb.network;

import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.key.ZOrbHooks;
import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.npcregion.NpcRegionManager;
import net.shurui.shuruisutilities.npcregion.NpcRegion;
import net.shurui.shuruisutilities.zorb.ZOrbConfig;
import net.shurui.shuruisutilities.zorb.ZOrbGlobalStore;
import net.shurui.shuruisutilities.zorb.ZOrbGlobals;

/**
 * Client -&gt; server (fixed id 106): save a region's Z orb config, and optionally the server-wide globals (the
 * editor's Global button). Server-authoritative: requires {@code su.npcregion.admin} AND the Z orb hook actually
 * installed ({@link ZOrbHooks#available()}), so a keyless server refuses the write outright and can never gain a
 * {@code zorbs} field it would then have to strip. Sent SEPARATELY from {@code PacketSaveNpcRegion} so each stays
 * well under the 32767-byte serverbound ceiling.
 *
 * <p>On apply it sets {@code region.zorbs}, sanitises, and {@code manager.put(...)} (which persists the region JSON
 * and publishes it over the shard NPC-region sync, carrying the new {@code zorbs} field for free). When globals are
 * present it stores and force-publishes them under {@code zorbs:globals}, then notifies the key's hook so live
 * timers pick up the change.
 */
public class PacketSaveZOrbConfig implements ISUPacket
{
    public String region = "";
    public ZOrbConfig zorbs = new ZOrbConfig();
    /** Null when the editor is saving only this region's config, not the globals. */
    public ZOrbGlobals globals;

    public PacketSaveZOrbConfig() {}

    public PacketSaveZOrbConfig(String region, ZOrbConfig zorbs, ZOrbGlobals globals)
    {
        this.region = region == null ? "" : region;
        this.zorbs = zorbs == null ? new ZOrbConfig() : zorbs;
        this.globals = globals;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(region);
        zorbs.encode(buf);
        buf.writeBoolean(globals != null);
        if (globals != null)
            globals.encode(buf);
    }

    public static PacketSaveZOrbConfig decode(FriendlyByteBuf buf)
    {
        PacketSaveZOrbConfig p = new PacketSaveZOrbConfig();
        p.region = buf.readUtf();
        p.zorbs = ZOrbConfig.decode(buf);
        if (buf.readBoolean())
            p.globals = ZOrbGlobals.decode(buf);
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        if (player == null || !APIRegistry.perms.checkPermission(player, NpcRegionManager.PERM_ADMIN))
            return;
        // Private feature: refuse unless the key installed it. Keeps a keyless server from ever writing a zorbs field.
        if (!ZOrbHooks.available())
            return;
        // Globals-only save (the region-independent world editor, /zorbs edit): no region touched.
        if (region == null || region.isBlank())
        {
            if (globals != null)
            {
                globals.sanitize();
                ZOrbGlobalStore.set(globals);
                net.shurui.shuruisutilities.shard.ShardStateSync.forcePublish("zorbs:globals");
                ZOrbHooks.get().onRegionChanged("");
            }
            return;
        }
        NpcRegion r = NpcRegionManager.instance().get(region);
        if (r == null)
            return;
        zorbs.sanitize();
        r.zorbs = zorbs;
        NpcRegionManager.instance().put(r); // persists + shard-publishes the region (zorbs rides along)
        if (globals != null)
        {
            ZOrbGlobalStore.set(globals);
            // Push the globals across the shard network at once, bypassing the change detector's boot gate.
            net.shurui.shuruisutilities.shard.ShardStateSync.forcePublish("zorbs:globals");
        }
        ZOrbHooks.get().onRegionChanged(region.toLowerCase(java.util.Locale.ROOT));
    }

    public static void handler(final PacketSaveZOrbConfig message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
