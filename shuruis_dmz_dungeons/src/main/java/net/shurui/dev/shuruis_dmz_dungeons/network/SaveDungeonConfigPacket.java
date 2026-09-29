package net.shurui.dev.shuruis_dmz_dungeons.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.shuruis_dmz_dungeons.Shuruis_dmz_dungeons;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloorConfig;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonFloors;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.DungeonRules;
import net.shurui.dev.shuruis_dmz_dungeons.dungeon.gen.LayoutStyle;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

// C2S: save the dungeon-wide rules and (when procedural floors are unlocked) the whole ordered floor list.
// server-authoritative: op check, then every value re-validated here, never trusted from the client.
//  * rules  -> DungeonRules.apply clamps the two timers.
//  * floors -> only when DungeonKeyHooks.available() (the key installed floors and its gate answers yes), so a
//              keyless server can never gain floors via the GUI (it still
//              saves its time limit / cooldown / pvp / ki-block rules). Each floor is sanitized: unknown theme
//              -> OVERWORLD, unknown layout -> inherit default, size/depth clamped, floor count capped.
public class SaveDungeonConfigPacket {

    // matches the /rg dungeon floor count range, so the GUI can never seed more floors than the command allows.
    private static final int MAX_FLOORS = 256;

    private final boolean pvp;
    private final boolean kiBlockDestruction;
    private final boolean blockEditing;
    private final int timeLimitSeconds;
    private final int cooldownSeconds;
    private final List<CompoundTag> floors;

    public SaveDungeonConfigPacket(boolean pvp, boolean kiBlockDestruction, boolean blockEditing,
                                   int timeLimitSeconds, int cooldownSeconds, List<CompoundTag> floors) {
        this.pvp = pvp;
        this.kiBlockDestruction = kiBlockDestruction;
        this.blockEditing = blockEditing;
        this.timeLimitSeconds = timeLimitSeconds;
        this.cooldownSeconds = cooldownSeconds;
        this.floors = floors;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(pvp);
        buf.writeBoolean(kiBlockDestruction);
        buf.writeBoolean(blockEditing);
        buf.writeInt(timeLimitSeconds);
        buf.writeInt(cooldownSeconds);
        buf.writeVarInt(floors.size());
        for (CompoundTag t : floors) {
            buf.writeNbt(t);
        }
    }

    public static SaveDungeonConfigPacket decode(FriendlyByteBuf buf) {
        boolean pvp = buf.readBoolean();
        boolean kiBlockDestruction = buf.readBoolean();
        boolean blockEditing = buf.readBoolean();
        int timeLimitSeconds = buf.readInt();
        int cooldownSeconds = buf.readInt();
        int n = buf.readVarInt();
        List<CompoundTag> floors = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            floors.add(buf.readNbt());
        }
        return new SaveDungeonConfigPacket(pvp, kiBlockDestruction, blockEditing,
                timeLimitSeconds, cooldownSeconds, floors);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> applyServer(context.getSender(), this));
        context.setPacketHandled(true);
    }

    // server-authoritative apply, shared by handle() above and the chunked transport so a save arriving in one
    // packet or many lands the same way. op check, every value re-validated. MUST run on the server thread.
    public static void applyServer(ServerPlayer player, SaveDungeonConfigPacket packet) {
        if (player == null || !player.hasPermissions(2)) {
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        // getOrCreate, not get: this is a WRITE path, the same kind DungeonStateSync uses the force-creating
        // resolvers for. The editor will normally have materialised the legacy dungeon dim already (DungeonCommand
        // refuses to open without it), but an admin's edit must never be dropped because the dim happens not to be
        // live. get() stays non-creating for the read/publish path, which must not spin up a dim on an idle server.
        boolean rulesSaved = false;
        boolean floorsSaved = false;
        // dungeon-wide rules: always applied (they work without the key). apply() clamps the timers.
        DungeonRules rules = DungeonRules.getOrCreate(server);
        if (rules != null) {
            rules.apply(packet.pvp, packet.kiBlockDestruction, packet.blockEditing,
                    packet.timeLimitSeconds, packet.cooldownSeconds);
            rulesSaved = true;
        }
        // floors: only when procedural floors are actually unlocked on this server. every field re-validated.
        boolean floorsUnlocked = net.shurui.dev.shuruis_dmz_dungeons.api.key.DungeonKeyHooks.available();
        if (floorsUnlocked) {
            DungeonFloors floorData = DungeonFloors.getOrCreate(server);
            if (floorData != null) {
                List<DungeonFloorConfig> sanitized = new ArrayList<>();
                for (CompoundTag t : packet.floors) {
                    if (sanitized.size() >= MAX_FLOORS) {
                        break;
                    }
                    DungeonFloorConfig c = DungeonFloorConfig.load(t);
                    c.theme = DungeonFloorConfig.canonicalTheme(c.theme);
                    c.type = DungeonFloorConfig.canonicalType(c.type);
                    c.size = c.clampedSize();
                    c.depth = c.clampedDepth();
                    // unknown / renamed layout style -> inherit the config default (empty string).
                    if (c.layoutStyle != null && !c.layoutStyle.isBlank()
                            && LayoutStyle.byName(c.layoutStyle, null) == null) {
                        c.layoutStyle = "";
                    }
                    sanitized.add(c);
                }
                // applyConfigsFromEditor, not applyConfigs: an admin edit ORIGINATED here, so a floor whose theme,
                // type or layout style changed is relocated onto fresh ground (old build abandoned, not re-stamped)
                // and a removed floor's location is retired. The client never sends an epoch; the server owns it
                // and the bump rides the shared config to the other shards.
                floorData.applyConfigsFromEditor(sanitized);
                floorsSaved = true;
            }
        } else if (!packet.floors.isEmpty()) {
            // Floors edited on a server that may not have them. Logged rather than reported as a failure: the rules
            // did save, and on a keyless server this is the gate working as designed, not a fault.
            Shuruis_dmz_dungeons.LOGGER.warn("[Dungeons] {} saved {} floor(s) from the editor, but procedural floors"
                    + " are not unlocked on this server, so only the dungeon rules were kept.",
                    player.getGameProfile().getName(), packet.floors.size());
        }
        // Say what actually landed. This toast used to fire unconditionally, so a save the server silently dropped
        // (no dungeon dimension, so both resolvers returned null) was indistinguishable from one that worked: the
        // admin saw "saved" and found the edit gone later. A save that is refused now says so at the moment it is
        // refused, which is the difference between a lost edit and a reported one.
        boolean saved = rulesSaved && (floorsSaved || !floorsUnlocked);
        if (!saved) {
            Shuruis_dmz_dungeons.LOGGER.warn("[Dungeons] dungeon config save from {} did not land: rules={},"
                    + " floors={} (floors unlocked={}). The dungeon dimension has no LevelStem, so nothing could be"
                    + " written. The edit was NOT kept.",
                    player.getGameProfile().getName(), rulesSaved, floorsSaved, floorsUnlocked);
        }
        player.displayClientMessage(Component.translatable(saved
                ? "message.dmz_ragnarok.dungeons.config_saved"
                : "message.dmz_ragnarok.dungeons.config_save_failed"), true);
    }
}
