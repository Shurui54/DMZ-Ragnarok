package net.shurui.dev.sdu.form;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.util.GsonHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * Addon-only extra aura layers for a form. DMZ allows only one aura per form
 * ({@code auraType}/{@code auraLayer}/{@code auraColor}); this stacks several (e.g. white plus silver
 * electric). Each layer mirrors DMZ's {@code AuraRenderer.AuraLayer}; our client mixin appends them to
 * what DMZ draws. Stored by {@link FormAuraConfig}, never in DMZ's form JSON.
 */
public class FormAuraData {

    public static class Layer {
        public String type = "";
        public int layer = 0;
        public String color = "";

        public Layer() {
        }

        public Layer(String type, int layer, String color) {
            this.type = type;
            this.layer = layer;
            this.color = color;
        }

        public JsonObject toJson() {
            JsonObject o = new JsonObject();
            o.addProperty("type", type);
            o.addProperty("layer", layer);
            o.addProperty("color", color);
            return o;
        }

        public static Layer fromJson(JsonObject o) {
            return new Layer(GsonHelper.getAsString(o, "type", ""),
                    GsonHelper.getAsInt(o, "layer", 0),
                    GsonHelper.getAsString(o, "color", ""));
        }
    }

    public final List<Layer> layers = new ArrayList<>();

    /** SDU-owned per-form aura size multipliers (default 1.0). Applied on top of DMZ's derived aura scale. */
    public float width = 1.0f;
    public float height = 1.0f;

    public boolean isEmpty() {
        // a non-default size keeps it non-empty, else the empty-removal would drop a form with a size
        // override and no layers
        if (width != 1.0f || height != 1.0f) {
            return false;
        }
        for (Layer l : layers) {
            if (l.type != null && !l.type.isBlank()) {
                return false;
            }
        }
        return true;
    }

    public FormAuraData copy() {
        return fromJson(toJson());
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (Layer l : layers) {
            arr.add(l.toJson());
        }
        o.add("layers", arr);
        o.addProperty("auraWidth", width);
        o.addProperty("auraHeight", height);
        return o;
    }

    public static FormAuraData fromJson(JsonObject o) {
        FormAuraData d = new FormAuraData();
        if (o != null && o.has("layers") && o.get("layers").isJsonArray()) {
            for (var el : o.getAsJsonArray("layers")) {
                if (el.isJsonObject()) {
                    d.layers.add(Layer.fromJson(el.getAsJsonObject()));
                }
            }
        }
        if (o != null) {
            d.width = GsonHelper.getAsFloat(o, "auraWidth", 1.0f);
            d.height = GsonHelper.getAsFloat(o, "auraHeight", 1.0f);
        }
        return d;
    }
}
