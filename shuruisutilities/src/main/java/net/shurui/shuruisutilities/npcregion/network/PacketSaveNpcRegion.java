package net.shurui.shuruisutilities.npcregion.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import net.shurui.shuruisutilities.commons.network.ISUPacket;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.npcregion.NpcRegionManager;
import net.shurui.shuruisutilities.npcregion.NpcRegion;
import net.shurui.shuruisutilities.npcregion.NpcSpawnConfig;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server: save an edited NPC-region definition. Carries the region name, its (possibly narrowed)
 * vertical bounds and the full list of {@link NpcSpawnConfig}s. Server-authoritative: op-gated, the named
 * region must exist, and only its NPC list + Y bounds are updated (XZ footprint is fixed by the map draw).
 */
public class PacketSaveNpcRegion implements ISUPacket
{
    public String name = "";
    public int minY;
    public int maxY;
    public List<NpcSpawnConfig> npcs = new ArrayList<>();
    // Display options (title/description/difficulty + toggles) edited in the Display sub-screen.
    public String title = "";
    public String description = "";
    public String difficulty = "";
    public boolean showTitle = true;
    public boolean showHud = true;

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
    // Per-region TP falloff (SU Feature B). tpFalloffLevel 0 = feature off; sanitize() clamps both server-side.
    public int tpFalloffLevel = 0;
    public int tpFalloffRange = 20;
    // Admin flag: an eventOnly region runs only while a timed event owns it. handle() writes it to the region.
    public boolean eventOnly = false;

    public PacketSaveNpcRegion() {}

    public PacketSaveNpcRegion(String name, int minY, int maxY, List<NpcSpawnConfig> npcs,
                               String title, String description, String difficulty,
                               boolean showTitle, boolean showHud,
                               boolean crateBossEnabled, NpcSpawnConfig crateBoss,
                               List<net.shurui.shuruisutilities.npcregion.CustomDrop> crateLoot,
                               boolean airdropEnabled, int airdropMinIntervalMinutes,
                               int airdropMaxIntervalMinutes, String airdropAnnouncement,
                               String airdropSound, String color,
                               int tpFalloffLevel, int tpFalloffRange, boolean eventOnly)
    {
        this.eventOnly = eventOnly;
        this.crateLoot = crateLoot == null ? new ArrayList<>() : crateLoot;
        this.color = color == null ? "" : color;
        this.name = name == null ? "" : name;
        this.minY = minY;
        this.maxY = maxY;
        this.npcs = npcs == null ? new ArrayList<>() : npcs;
        this.title = title == null ? "" : title;
        this.description = description == null ? "" : description;
        this.difficulty = difficulty == null ? "" : difficulty;
        this.showTitle = showTitle;
        this.showHud = showHud;
        this.crateBossEnabled = crateBossEnabled;
        this.crateBoss = crateBoss;
        this.airdropEnabled = airdropEnabled;
        this.airdropMinIntervalMinutes = airdropMinIntervalMinutes;
        this.airdropMaxIntervalMinutes = airdropMaxIntervalMinutes;
        this.airdropAnnouncement = airdropAnnouncement == null ? "" : airdropAnnouncement;
        this.airdropSound = airdropSound == null ? NpcRegion.DEFAULT_AIRDROP_SOUND : airdropSound;
        this.tpFalloffLevel = tpFalloffLevel;
        this.tpFalloffRange = tpFalloffRange;
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
        buf.writeBoolean(eventOnly); // appended last so the wire layout stays additive
    }

    public static PacketSaveNpcRegion decode(FriendlyByteBuf buf)
    {
        PacketSaveNpcRegion p = new PacketSaveNpcRegion();
        p.name = buf.readUtf();
        p.minY = buf.readInt();
        p.maxY = buf.readInt();
        p.npcs = NpcRegionCodec.decodeList(buf);
        p.title = buf.readUtf();
        p.description = buf.readUtf();
        p.difficulty = buf.readUtf();
        p.showTitle = buf.readBoolean();
        p.showHud = buf.readBoolean();
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
        p.eventOnly = buf.readBoolean();
        return p;
    }

    @Override
    public void handle(NetworkEvent.Context context)
    {
        ServerPlayer player = context.getSender();
        // Node-gated (not raw op level) so region management can be granted per group.
        if (player == null || !net.shurui.shuruisutilities.api.APIRegistry.perms.checkPermission(
                player, NpcRegionManager.PERM_ADMIN))
            return;
        NpcRegion r = NpcRegionManager.instance().get(name);
        if (r == null)
            return;
        for (NpcSpawnConfig c : npcs)
            c.sanitize();
        r.npcs = npcs;
        // The editor's single Y range applies to EVERY selection of the region.
        int lo = Math.min(minY, maxY);
        int hi = Math.max(minY, maxY);
        for (NpcRegion.Box b : r.boxes)
        {
            b.minY = lo;
            b.maxY = hi;
        }
        r.title = title;
        r.description = description;
        r.difficulty = difficulty;
        r.showTitle = showTitle;
        r.showHud = showHud;
        r.crateBossEnabled = crateBossEnabled;
        if (crateBoss != null)
            crateBoss.sanitize();
        r.crateBoss = crateBoss;
        r.crateLoot = crateLoot;
        r.airdropEnabled = airdropEnabled;
        r.airdropMinIntervalMinutes = airdropMinIntervalMinutes;
        r.airdropMaxIntervalMinutes = airdropMaxIntervalMinutes;
        r.airdropAnnouncement = airdropAnnouncement;
        r.airdropSound = airdropSound;
        r.color = color;
        r.tpFalloffLevel = tpFalloffLevel;
        r.tpFalloffRange = tpFalloffRange;
        // Only "true" is stored; false folds to null in sanitize() so a normal region's JSON stays byte-identical.
        r.eventOnly = eventOnly ? Boolean.TRUE : null;
        r.sanitize(); // normalizes the colour, clamps the TP falloff fields, and everything else before persist
        NpcRegionManager.instance().put(r);
    }

    public static void handler(final PacketSaveNpcRegion message, Supplier<NetworkEvent.Context> ctx)
    {
        NetworkUtils.handleGetLog(message);
        ctx.get().enqueueWork(() -> message.handle(ctx.get()));
        ctx.get().setPacketHandled(true);
    }
}
