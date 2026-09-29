package net.shurui.dev.sdu.wish;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

// all wishes for one dragon (shenron, porunga, ...), the array DMZ reads from
// world/dragonminez/wishes/<dragon>.json. dragon id = file name; DMZ only serves dragons it knows.
public class WishSetData {

    public String dragon = "shenron";
    public String model = "";        // playermodel folder (resource pack); blank = don't write a pack
    public String dragonballs = "";  // dragonballs folder (resource pack); blank = don't write a pack
    public final List<WishData> wishes = new ArrayList<>();

    // exactly as DMZ's WishManager reads it.
    public JsonArray toWishArray() {
        JsonArray arr = new JsonArray();
        for (WishData w : wishes) {
            arr.add(w.toJson());
        }
        return arr;
    }

    public static WishSetData fromWishArray(String dragon, JsonArray arr) {
        WishSetData s = new WishSetData();
        s.dragon = dragon;
        if (arr != null) {
            for (var el : arr) {
                if (el.isJsonObject()) {
                    s.wishes.add(WishData.fromJson(el.getAsJsonObject()));
                }
            }
        }
        return s;
    }

    // editor-sync bundle: dragon id + wish array in an object.
    public JsonObject toBundle() {
        JsonObject o = new JsonObject();
        o.addProperty("dragon", dragon);
        o.addProperty("model", model);
        o.addProperty("dragonballs", dragonballs);
        o.add("wishes", toWishArray());
        return o;
    }

    public static WishSetData fromBundle(JsonObject o) {
        String dragon = GsonHelper.getAsString(o, "dragon", "shenron");
        JsonArray arr = o.has("wishes") && o.get("wishes").isJsonArray() ? o.getAsJsonArray("wishes") : new JsonArray();
        WishSetData s = fromWishArray(dragon, arr);
        s.model = GsonHelper.getAsString(o, "model", "");
        s.dragonballs = GsonHelper.getAsString(o, "dragonballs", "");
        return s;
    }

    public WishSetData copy() {
        return fromBundle(toBundle());
    }
}
