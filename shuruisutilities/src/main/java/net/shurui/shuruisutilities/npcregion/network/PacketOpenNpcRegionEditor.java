package net.shurui.shuruisutilities.npcregion.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.npcregion.NpcRegion;
import net.shurui.shuruisutilities.npcregion.NpcSpawnConfig;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: open the NPC-region editor for one region, carrying its name, editable vertical bounds
 * ({@link #minY}/{@link #maxY}) and full list of {@link NpcSpawnConfig}s. The XZ footprint is fixed by the map
 * draw and is not sent (edited only by re-drawing). The client pushes {@code NpcRegionEditScreen}; Save sends
 * back a {@link PacketSaveNpcRegion}.
 */
public class PacketOpenNpcRegionEditor implements ISUPacket
{
    public String name = "";
    public int minY;
    public int maxY;
    public List<NpcSpawnConfig> npcs = new ArrayList<>();
    public String title = "";
    public String description = "";
    public String difficulty = "";
    public boolean showTitle = true;
    public boolean showHud = true;
    public boolean eventOnly = false;
    /** The region's selections as {minX,minY,minZ,maxX,maxY,maxZ}, for the editor's Selections screen. */
    public List<int[]> boxes = new ArrayList<>();
    // Airdrop crate boss (Crates screen). Null crateBoss = automatic apex x2.
    public boolean crateBossEnabled = true;
    public NpcSpawnConfig crateBoss;
    /** Region-specific airdrop chest loot; empty = this region produces no drops. */
    public List<net.shurui.shuruisutilities.npcregion.CustomDrop> crateLoot = new ArrayList<>();
    // Per-region airdrop scheduling (Airdrop screen).
    public boolean airdropEnabled = false;
    public int airdropMinIntervalMinutes = 30;
    public int airdropMaxIntervalMinutes = 60;
    public String airdropAnnouncement = "";
    /** Per-region landing sound id; blank = silent. */
    public String airdropSound = NpcRegion.DEFAULT_AIRDROP_SOUND;
    /** Map overlay fill colour as {@code #RRGGBB}; blank = default. */
    public String color = "";
    // Per-region TP falloff (SU Feature B), shown so the admin edits the current values. 0 level = off.
    public int tpFalloffLevel = 0;
    public int tpFalloffRange = 20;

    // --- Z orbs (appended at the END of the wire format, Z1). The Z orb editor consumes these in Z3; for now
    // they only ride the packet so the wire format is fixed. The client decides whether to show the Z orbs button
    // from ClientGate.feature("zorbs"); zorbsAvailable mirrors the server's ZOrbHooks.available() for reference. ---
    /** Whether the server's Ragnarok Key installed the Z orb feature. */
    public boolean zorbsAvailable = false;
    /** This region's Z orb config, or null when it was never configured. */
    public net.shurui.shuruisutilities.zorb.ZOrbConfig zorbs;
    /** The server-wide Z orb globals, for the editor's Global sub-screen (Z3). */
    public net.shurui.shuruisutilities.zorb.ZOrbGlobals zorbsGlobals = new net.shurui.shuruisutilities.zorb.ZOrbGlobals();
    /** When true, the client opens the world Z orb editor directly (no region), for /zorbs edit. */
    public boolean zorbsGlobalsOnly = false;

    public PacketOpenNpcRegionEditor() {}

    public PacketOpenNpcRegionEditor(NpcRegion region)
    {
        this.name = region.name;
        this.minY = region.primary().minY;
        this.maxY = region.primary().maxY;
        this.npcs = region.npcs == null ? new ArrayList<>() : region.npcs;
        this.title = region.title == null ? "" : region.title;
        this.description = region.description == null ? "" : region.description;
        this.difficulty = region.difficulty == null ? "" : region.difficulty;
        this.showTitle = region.showTitle;
        this.showHud = region.showHud;
        this.eventOnly = region.isEventOnly();
        for (NpcRegion.Box b : region.boxes)
            this.boxes.add(new int[] {b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ});
        this.crateBossEnabled = region.crateBossEnabled;
        this.crateBoss = region.crateBoss;
        this.crateLoot = region.crateLoot == null ? new ArrayList<>() : region.crateLoot;
        this.airdropEnabled = region.airdropEnabled;
        this.airdropMinIntervalMinutes = region.airdropMinIntervalMinutes;
        this.airdropMaxIntervalMinutes = region.airdropMaxIntervalMinutes;
        this.airdropAnnouncement = region.airdropAnnouncement == null ? "" : region.airdropAnnouncement;
        this.airdropSound = region.airdropSound == null ? NpcRegion.DEFAULT_AIRDROP_SOUND : region.airdropSound;
        this.color = region.color == null ? "" : region.color;
        this.tpFalloffLevel = region.tpFalloffLevel;
        this.tpFalloffRange = region.tpFalloffRange;
        this.zorbsAvailable = net.shurui.shuruisutilities.api.key.ZOrbHooks.available();
        this.zorbs = region.zorbs;
        this.zorbsGlobals = net.shurui.shuruisutilities.zorb.ZOrbGlobalStore.get();
    }

    /** Send the editor for {@code region} to {@code player} (op-gating done by the caller). */
    public static void sendTo(ServerPlayer player, NpcRegion region)
    {
        NetworkUtils.sendTo(new PacketOpenNpcRegionEditor(region), player);
    }

    @Override
    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(name);
        buf.writeInt(minY);
        buf.writeInt(maxY);
        NpcRegionCodec.encodeList(buf, npcs);
        buf.writeUtf(title);
        buf.writeUtf(description);
        buf.writeUtf(difficulty);
        buf.writeBoolean(showTitle);
        buf.writeBoolean(showHud);
        buf.writeVarInt(boxes.size());
        for (int[] b : boxes)
            for (int v : b)
                buf.writeInt(v);
        buf.writeBoolean(crateBossEnabled);
        buf.writeBoolean(crateBoss != null);
        if (crateBoss != null)
            crateBoss.encode(buf);
        buf.writeVarInt(crateLoot.size());
        for (net.shurui.shuruisutilities.npcregion.CustomDrop d : crateLoot)
            d.encode(buf);
        buf.writeBoolean(airdropEnabled);
        buf.writeVarInt(airdropMinIntervalMinutes);
        buf.writeVarInt(airdropMaxIntervalMinutes);
        buf.writeUtf(airdropAnnouncement == null ? "" : airdropAnnouncement);
        buf.writeUtf(airdropSound == null ? "" : airdropSound);
        buf.writeUtf(color);
        buf.writeVarInt(tpFalloffLevel);
        buf.writeVarInt(tpFalloffRange);
        buf.writeBoolean(zorbsAvailable);
        buf.writeBoolean(zorbs != null);
        if (zorbs != null)
            zorbs.encode(buf);
        (zorbsGlobals == null ? new net.shurui.shuruisutilities.zorb.ZOrbGlobals() : zorbsGlobals).encode(buf);
        buf.writeBoolean(zorbsGlobalsOnly);
        buf.writeBoolean(eventOnly); // appended last so the wire layout stays additive
    }

    public static PacketOpenNpcRegionEditor decode(FriendlyByteBuf buf)
    {
        PacketOpenNpcRegionEditor p = new PacketOpenNpcRegionEditor();
        p.name = buf.readUtf();
        p.minY = buf.readInt();
        p.maxY = buf.readInt();
        p.npcs = NpcRegionCodec.decodeList(buf);
        p.title = buf.readUtf();
        p.description = buf.readUtf();
        p.difficulty = buf.readUtf();
        p.showTitle = buf.readBoolean();
        p.showHud = buf.readBoolean();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            p.boxes.add(new int[] {buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readInt(), buf.readInt(), buf.readInt()});
        p.crateBossEnabled = buf.readBoolean();
        if (buf.readBoolean())
            p.crateBoss = NpcSpawnConfig.decode(buf);
        int nl = buf.readVarInt();
        for (int i = 0; i < nl; i++)
            p.crateLoot.add(net.shurui.shuruisutilities.npcregion.CustomDrop.decode(buf));
        p.airdropEnabled = buf.readBoolean();
        p.airdropMinIntervalMinutes = buf.readVarInt();
        p.airdropMaxIntervalMinutes = buf.readVarInt();
        p.airdropAnnouncement = buf.readUtf();
        p.airdropSound = buf.readUtf();
        p.color = buf.readUtf();
        p.tpFalloffLevel = buf.readVarInt();
        p.tpFalloffRange = buf.readVarInt();
        p.zorbsAvailable = buf.readBoolean();
        if (buf.readBoolean())
            p.zorbs = net.shurui.shuruisutilities.zorb.ZOrbConfig.decode(buf);
        p.zorbsGlobals = net.shurui.shuruisutilities.zorb.ZOrbGlobals.decode(buf);
        p.zorbsGlobalsOnly = buf.readBoolean();
        p.eventOnly = buf.readBoolean();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> net.shurui.shuruisutilities.client.gui.editor.NpcRegionEditScreen.open(
                        name, minY, maxY, npcs, title, description, difficulty, showTitle, showHud, boxes,
                        crateBossEnabled, crateBoss, crateLoot,
                        airdropEnabled, airdropMinIntervalMinutes, airdropMaxIntervalMinutes, airdropAnnouncement,
                        airdropSound, color, tpFalloffLevel, tpFalloffRange,
                        zorbsAvailable, zorbs, zorbsGlobals, zorbsGlobalsOnly, eventOnly));
    }

    public static void handler(final PacketOpenNpcRegionEditor message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
