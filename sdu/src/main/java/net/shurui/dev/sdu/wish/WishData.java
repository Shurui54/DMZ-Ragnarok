package net.shurui.dev.sdu.wish;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

// one DMZ wish (an entry in world/dragonminez/wishes/<dragon>.json). every wish has name/description (a lang
// key or plain text) + a type; each type adds its own fields, matching DMZ's WishTypeAdapter:
//   item -> itemId + count; tps -> amount; command -> commands[]; skill -> skill + level;
//   multi_wish -> items[] of (itemId, count); relocatestats/passivereset/recustomize -> no extra fields.
public class WishData {

    public static final String[] TYPES =
            {"item", "tps", "command", "skill", "multi_wish", "relocatestats", "passivereset", "recustomize",
                    "changedifficulty", "resetstory"};

    public String type = "item";
    public String name = "";
    public String description = "";

    // item
    public String itemId = "minecraft:diamond";
    public int count = 1;
    // tps
    public int amount = 1000;
    // command
    public final List<String> commands = new ArrayList<>();
    // skill
    public String skill = "";
    public int level = 1;
    // multi_wish
    public final List<MultiItem> items = new ArrayList<>();

    // raw source object, kept so an unknown wish type (one a future DMZ adds) round-trips without losing its
    // extra fields. null for wishes built fresh in the editor.
    private JsonObject raw;

    // types this editor models field-by-field; anything else is preserved via raw.
    private static boolean isKnownType(String t) {
        for (String k : TYPES) {
            if (k.equals(t)) {
                return true;
            }
        }
        return false;
    }

    // one multi_wish entry: item id (a) + count (b).
    public static class MultiItem {
        public String itemId = "minecraft:diamond";
        public int count = 1;

        public MultiItem() {
        }

        public MultiItem(String itemId, int count) {
            this.itemId = itemId;
            this.count = count;
        }
    }

    public WishData copy() {
        return fromJson(toJson());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        switch (type) {
            case "item" -> {
                o.addProperty("itemId", itemId);
                o.addProperty("count", count);
            }
            case "tps" -> o.addProperty("amount", amount);
            case "command" -> {
                JsonArray arr = new JsonArray();
                for (String c : commands) {
                    arr.add(c);
                }
                o.add("commands", arr);
            }
            case "skill" -> {
                o.addProperty("skill", skill);
                o.addProperty("level", level);
            }
            case "multi_wish" -> {
                JsonArray arr = new JsonArray();
                for (MultiItem m : items) {
                    JsonObject e = new JsonObject();
                    e.addProperty("a", m.itemId);
                    e.addProperty("b", m.count);
                    arr.add(e);
                }
                o.add("items", arr);
            }
            default -> {
                // no-extra-field types. an unmodelled type keeps whatever fields it came with (via raw).
                if (!isKnownType(type) && raw != null) {
                    for (var e : raw.entrySet()) {
                        if (!e.getKey().equals("name") && !e.getKey().equals("description") && !e.getKey().equals("type")) {
                            o.add(e.getKey(), e.getValue());
                        }
                    }
                }
            }
        }
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.addProperty("type", type);
        return o;
    }

    public static WishData fromJson(JsonObject o) {
        WishData w = new WishData();
        w.raw = o;
        w.type = GsonHelper.getAsString(o, "type", "item");
        w.name = GsonHelper.getAsString(o, "name", "");
        w.description = GsonHelper.getAsString(o, "description", "");
        w.itemId = GsonHelper.getAsString(o, "itemId", "minecraft:diamond");
        w.count = GsonHelper.getAsInt(o, "count", 1);
        w.amount = GsonHelper.getAsInt(o, "amount", 1000);
        w.skill = GsonHelper.getAsString(o, "skill", "");
        w.level = GsonHelper.getAsInt(o, "level", 1);
        if (o.has("commands") && o.get("commands").isJsonArray()) {
            for (var el : o.getAsJsonArray("commands")) {
                w.commands.add(el.getAsString());
            }
        }
        if (o.has("items") && o.get("items").isJsonArray()) {
            for (var el : o.getAsJsonArray("items")) {
                if (el.isJsonObject()) {
                    JsonObject e = el.getAsJsonObject();
                    w.items.add(new MultiItem(GsonHelper.getAsString(e, "a", "minecraft:diamond"),
                            GsonHelper.getAsInt(e, "b", 1)));
                }
            }
        }
        return w;
    }

    // list-row summary using the raw name; prefer summary(String) on the client.
    public String summary() {
        return summary(name);
    }

    // client passes the name resolved through the language file so DMZ default wishes (name is a lang key)
    // show their real text.
    public String summary(String displayName) {
        String label = displayName == null || displayName.isBlank() ? "(unnamed)" : displayName;
        return switch (type) {
            case "item" -> label + " §7[" + count + "x " + itemId + "]";
            case "tps" -> label + " §7[" + amount + " TP]";
            case "command" -> label + " §7[" + commands.size() + " cmd]";
            case "skill" -> label + " §7[" + skill + " lv" + level + "]";
            case "multi_wish" -> label + " §7[" + items.size() + " items]";
            default -> label + " §7[" + type + "]";
        };
    }
}
