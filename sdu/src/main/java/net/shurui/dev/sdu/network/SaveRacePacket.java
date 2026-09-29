package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.race.RaceData;
import net.shurui.dev.sdu.race.RaceFileManager;

import java.util.function.Supplier;

/** Client -> server. Writes a race's character.json + stats.json in DMZ format, then reloads. Op-gated. */
public class SaveRacePacket {

    private static final Gson GSON = new Gson();
    private final String bundle;

    public SaveRacePacket(String bundle) {
        this.bundle = bundle;
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(bundle, 2_000_000);
    }

    public static SaveRacePacket decode(FriendlyByteBuf buf) {
        return new SaveRacePacket(buf.readUtf(2_000_000));
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context context = ctx.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null || player.getServer() == null || !net.shurui.dev.sdu.util.SduPerms.canEdit(player)) {
                return;
            }
            apply(player, bundle);
        });
        context.setPacketHandled(true);
    }

    /** Apply a race bundle JSON on the server. Caller must have verified the sender and edit permission. */
    public static void apply(ServerPlayer player, String bundleJson) {
        try {
            RaceData race = RaceData.fromBundle(GSON.fromJson(bundleJson, JsonObject.class));
            String err = RaceFileManager.save(race);
            if (err == null) {
                // Rebuild the race's display-name lang keys server-side and push them out.
                net.shurui.dev.sdu.lang.GeneratedLangStore.putAll(net.shurui.dev.sdu.lang.GeneratedNames.raceKeys(
                        race.raceId, race.racialSkill, race.displayName, race.description, race.racialName, race.racialDesc));
                // Class names too, else a class added here has no lang entry and DMZ's character screen prints the key.
                net.shurui.dev.sdu.lang.GeneratedLangStore.putAllIfAbsent(
                        net.shurui.dev.sdu.lang.GeneratedNames.classKeys(race.classes.keySet()));
                // Then the typed names/descriptions, which OVERWRITE the prettified fallback above.
                // Order matters: put-if-absent first, put second.
                net.shurui.dev.sdu.lang.GeneratedLangStore.putAll(
                        net.shurui.dev.sdu.lang.GeneratedNames.classDisplayKeys(race.classes));
                // A class added/renamed here needs its passive registered NOW: DMZ resolves passives through a
                // registry filled once at class-load, so an unseen id resolves to NONE. Without this the passive
                // only worked after a restart.
                net.shurui.dev.sdu.passive.SduClassPassives.refresh();
                DmzNet.syncLangToAll(player.getServer());
                // Rebuild the base-race aura-size lookup from disk and push it out, so AuraScaleMixin picks up the
                // edit live (DMZ's own config sync drops these custom keys).
                net.shurui.dev.sdu.race.RaceAuraConfig.rebuildFromDisk();
                DmzNet.syncRaceAurasToAll();
                boolean resynced = net.shurui.dev.sdu.compat.DmzCompat.resyncConfigsToAll(player.getServer());
                player.displayClientMessage(Component.translatable(resynced
                        ? "message.dmz_ragnarok.npc.race.saved.synced"
                        : "message.dmz_ragnarok.npc.race.saved.rejoin", race.raceId), false);
            } else {
                player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.race.save_failed", err), false);
            }
        } catch (Exception e) {
            player.displayClientMessage(Component.translatable("message.dmz_ragnarok.npc.race.bad_data", e.getMessage()), false);
        }
    }
}
