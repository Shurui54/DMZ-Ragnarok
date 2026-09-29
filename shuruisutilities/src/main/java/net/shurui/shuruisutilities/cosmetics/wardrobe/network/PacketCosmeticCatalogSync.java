package net.shurui.shuruisutilities.cosmetics.wardrobe.network;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticEffect;

/**
 * Server to client: the cosmetic catalogue.
 *
 * <p>A FULL replacement every time. The catalogue is a few dozen small records and it changes only when an admin
 * edits one, so a delta protocol would be more code and more ways to drift for no measurable saving. Replacing
 * the whole table also means a client that missed an edit while loading cannot end up holding a stale entry.
 *
 * <p>Sent on login BEFORE {@link PacketWardrobeSync}, and again to everybody after any admin edit. The order
 * matters: the wardrobe packet is a list of catalogue ids, so a client that got it first would be holding ids it
 * could not resolve.
 *
 * <p>The client needs this even though it renders nothing yet in milestone 1: the wardrobe screen labels its
 * entries from it, and the render layer that arrives in milestone 2 reads it to know what to draw on somebody
 * else. Console commands on a definition are deliberately not encoded; see {@link CosmeticDef#encode}.
 *
 * <h2>The MAGIC effects ride along too</h2>
 * The catalogue is the cosmetics AND the effects (see {@link net.shurui.shuruisutilities.cosmetics.wardrobe
 * .CosmeticCatalog}), so this carries the effect records as well. Without them a client knows only WHICH effect id
 * a nearby player has equipped (that rides in {@link PacketWardrobeSync}) and has nothing to look the particle,
 * colours and pattern up in, so the Magic presentation would draw nothing. This is a wire-format addition on the
 * same id, safe here for the same reason the wardrobe packet grew its payload: client and server ship in one jar
 * and can never be a version apart on this channel. The effect set is eighteen small records, resent on the same
 * occasions as the definitions.
 */
public class PacketCosmeticCatalogSync implements ISUPacket
{
    public List<CosmeticDef> defs = new ArrayList<>();

    /** The MAGIC effect records, so a client can draw a nearby player's equipped effect. */
    public List<CosmeticEffect> effects = new ArrayList<>();

    public PacketCosmeticCatalogSync()
    {
    }

    public static PacketCosmeticCatalogSync of(Collection<CosmeticDef> defs)
    {
        return of(defs, null);
    }

    public static PacketCosmeticCatalogSync of(Collection<CosmeticDef> defs, Collection<CosmeticEffect> effects)
    {
        PacketCosmeticCatalogSync p = new PacketCosmeticCatalogSync();
        if (defs != null)
            for (CosmeticDef d : defs)
                if (d != null)
                    p.defs.add(d);
        if (effects != null)
            for (CosmeticEffect e : effects)
                if (e != null)
                    p.effects.add(e);
        return p;
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeVarInt(defs.size());
        for (CosmeticDef d : defs)
            d.encode(buf);
        buf.writeVarInt(effects.size());
        for (CosmeticEffect e : effects)
            e.encode(buf);
    }

    public static PacketCosmeticCatalogSync decode(FriendlyByteBuf buf)
    {
        PacketCosmeticCatalogSync p = new PacketCosmeticCatalogSync();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.defs.add(CosmeticDef.decode(buf));
        int m = buf.readVarInt();
        for (int i = 0; i < m; i++)
            p.effects.add(CosmeticEffect.decode(buf));
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () ->
                net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore.applyCatalog(this));
    }

    public static void handler(final PacketCosmeticCatalogSync message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
