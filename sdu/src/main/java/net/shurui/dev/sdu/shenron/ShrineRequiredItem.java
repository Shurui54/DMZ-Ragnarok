package net.shurui.dev.sdu.shenron;

import com.google.gson.JsonObject;

/**
 * A single summoning requirement: an item registry id ({@code modid:path}) and a {@code count}. Requirements
 * are matched by item only (tags ignored), consumed across the whole player inventory on summon.
 */
public final class ShrineRequiredItem {

    public String item = "minecraft:nether_star";
    public int count = 1;

    public ShrineRequiredItem() {
    }

    public ShrineRequiredItem(String item, int count) {
        this.item = item;
        this.count = count;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("item", item == null ? "" : item);
        o.addProperty("count", Math.max(1, count));
        return o;
    }

    public static ShrineRequiredItem fromJson(JsonObject o) {
        ShrineRequiredItem r = new ShrineRequiredItem();
        if (o.has("item")) {
            r.item = o.get("item").getAsString();
        }
        if (o.has("count")) {
            r.count = Math.max(1, o.get("count").getAsInt());
        }
        return r;
    }
}
