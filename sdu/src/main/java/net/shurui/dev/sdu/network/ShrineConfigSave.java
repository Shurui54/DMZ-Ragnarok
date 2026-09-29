package net.shurui.dev.sdu.network;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.shenron.ShrineColor;
import net.shurui.dev.sdu.shenron.ShrineColorConfig;
import net.shurui.dev.sdu.shenron.ShrineConfig;
import net.shurui.dev.sdu.shenron.ShrineWish;
import net.shurui.dev.sdu.util.SduPerms;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side apply for the Shenron-shrine admin GUI's {@code "shrineconfig"} chunked save. Re-checks edit
 * permission, parses the {@code {"wishes":[...],"colors":{...}}} bundle (malformed entries skipped, duplicate
 * wish ids deduped last-wins), then replaces and persists the live model.
 */
public final class ShrineConfigSave {

    private ShrineConfigSave() {
    }

    private static final Gson GSON = new Gson();

    public static void apply(ServerPlayer player, String json) {
        if (player == null || !SduPerms.canEdit(player)) {
            return;
        }
        JsonObject root;
        try {
            root = GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            DmzNpc.LOGGER.warn("[{}] Rejecting malformed shrine config from {}: {}",
                    DmzNpc.MODID, player.getGameProfile().getName(), e.toString());
            player.displayClientMessage(Component.translatable("gui.dmz_ragnarok.npc.shrineconfig.save_failed"), false);
            return;
        }
        if (root == null) {
            player.displayClientMessage(Component.translatable("gui.dmz_ragnarok.npc.shrineconfig.save_failed"), false);
            return;
        }

        // Wishes: parse each object, skip broken/blank-id entries, dedupe by id (last wins) while keeping order.
        Map<String, ShrineWish> byId = new LinkedHashMap<>();
        if (root.has("wishes") && root.get("wishes").isJsonArray()) {
            for (var el : root.getAsJsonArray("wishes")) {
                if (!el.isJsonObject()) {
                    continue;
                }
                try {
                    ShrineWish w = ShrineWish.fromJson(el.getAsJsonObject());
                    if (w.id == null || w.id.isBlank()) {
                        continue;
                    }
                    byId.remove(w.id); // last occurrence wins, but re-appended at the end
                    byId.put(w.id, w);
                } catch (Exception ignored) {
                }
            }
        }
        List<ShrineWish> wishes = new ArrayList<>(byId.values());

        // Colours: parse the four keys, falling back to a seeded default for any missing/broken colour.
        Map<ShrineColor, ShrineColorConfig> colors = new EnumMap<>(ShrineColor.class);
        JsonObject colorsObj = root.has("colors") && root.get("colors").isJsonObject()
                ? root.getAsJsonObject("colors") : null;
        for (ShrineColor c : ShrineColor.values()) {
            ShrineColorConfig cfg = null;
            if (colorsObj != null && colorsObj.has(c.key()) && colorsObj.get(c.key()).isJsonObject()) {
                try {
                    cfg = ShrineColorConfig.fromJson(colorsObj.getAsJsonObject(c.key()));
                } catch (Exception ignored) {
                }
            }
            colors.put(c, cfg != null ? cfg : ShrineColorConfig.seedDefault());
        }

        ShrineConfig.saveFrom(wishes, colors);
        player.displayClientMessage(
                Component.translatable("gui.dmz_ragnarok.npc.shrineconfig.saved", wishes.size()), false);
    }
}
