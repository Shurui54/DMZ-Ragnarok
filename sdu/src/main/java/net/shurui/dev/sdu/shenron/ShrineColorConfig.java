package net.shurui.dev.sdu.shenron;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

// per-colour shrine settings: requiredItems to summon, the display entity's model/texture/scale, wishIds this
// colour offers (empty = all wishes), darkenSky, and summon duration.
public final class ShrineColorConfig {

    public static final String DEFAULT_GEO = "dmz_ragnarok:geo/entity/shenron.geo.json";
    public static final String DEFAULT_TEXTURE = "dmz_ragnarok:textures/entity/shenron.png";
    public static final int DEFAULT_DURATION = 1200;

    public List<ShrineRequiredItem> requiredItems = new ArrayList<>();
    public String modelGeo = DEFAULT_GEO;
    public String modelTexture = DEFAULT_TEXTURE;
    public float entityScale = 1.0f;
    public List<String> wishIds = new ArrayList<>();  // empty = all wishes in ShrineConfig
    public boolean darkenSky = true;
    public int summonDurationTicks = DEFAULT_DURATION;

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray items = new JsonArray();
        for (ShrineRequiredItem r : requiredItems) {
            items.add(r.toJson());
        }
        o.add("requiredItems", items);
        o.addProperty("modelGeo", modelGeo);
        o.addProperty("modelTexture", modelTexture);
        o.addProperty("entityScale", entityScale);
        JsonArray ids = new JsonArray();
        for (String id : wishIds) {
            ids.add(id);
        }
        o.add("wishIds", ids);
        o.addProperty("darkenSky", darkenSky);
        o.addProperty("summonDurationTicks", summonDurationTicks);
        return o;
    }

    public static ShrineColorConfig fromJson(JsonObject o) {
        ShrineColorConfig c = new ShrineColorConfig();
        c.requiredItems = new ArrayList<>();
        if (o.has("requiredItems") && o.get("requiredItems").isJsonArray()) {
            for (var el : o.getAsJsonArray("requiredItems")) {
                if (el.isJsonObject()) {
                    c.requiredItems.add(ShrineRequiredItem.fromJson(el.getAsJsonObject()));
                }
            }
        }
        c.modelGeo = o.has("modelGeo") ? o.get("modelGeo").getAsString() : DEFAULT_GEO;
        c.modelTexture = o.has("modelTexture") ? o.get("modelTexture").getAsString() : DEFAULT_TEXTURE;
        c.entityScale = o.has("entityScale") ? o.get("entityScale").getAsFloat() : 1.0f;
        c.wishIds = new ArrayList<>();
        if (o.has("wishIds") && o.get("wishIds").isJsonArray()) {
            for (var el : o.getAsJsonArray("wishIds")) {
                c.wishIds.add(el.getAsString());
            }
        }
        c.darkenSky = !o.has("darkenSky") || o.get("darkenSky").getAsBoolean();
        c.summonDurationTicks = o.has("summonDurationTicks")
                ? o.get("summonDurationTicks").getAsInt() : DEFAULT_DURATION;
        return c;
    }

    // seeded default: one nether star, all wishes.
    public static ShrineColorConfig seedDefault() {
        ShrineColorConfig c = new ShrineColorConfig();
        c.requiredItems.add(new ShrineRequiredItem("minecraft:nether_star", 1));
        return c;
    }
}
