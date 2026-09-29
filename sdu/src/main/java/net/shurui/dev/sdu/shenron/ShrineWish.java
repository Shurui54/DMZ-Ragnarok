package net.shurui.dev.sdu.shenron;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * One configurable wish: an {@code id} (stable key referenced by a colour's {@code wishIds}), a display
 * {@code name} + {@code description} shown in the wish-select GUI, and a list of {@code commands} run as
 * console when the wish is granted. Each command supports the {@code %player%} placeholder, substituted with
 * the summoner's name at run time.
 */
public final class ShrineWish {

    public String id = "";
    public String name = "";
    public String description = "";
    public List<String> commands = new ArrayList<>();

    public ShrineWish() {
    }

    public ShrineWish(String id, String name, String description, List<String> commands) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.commands = commands == null ? new ArrayList<>() : new ArrayList<>(commands);
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.addProperty("id", id == null ? "" : id);
        o.addProperty("name", name == null ? "" : name);
        o.addProperty("description", description == null ? "" : description);
        JsonArray cmds = new JsonArray();
        if (commands != null) {
            for (String c : commands) {
                if (c != null) {
                    cmds.add(c);
                }
            }
        }
        o.add("commands", cmds);
        return o;
    }

    public static ShrineWish fromJson(JsonObject o) {
        ShrineWish w = new ShrineWish();
        if (o.has("id")) {
            w.id = o.get("id").getAsString();
        }
        if (o.has("name")) {
            w.name = o.get("name").getAsString();
        }
        if (o.has("description")) {
            w.description = o.get("description").getAsString();
        }
        w.commands = new ArrayList<>();
        if (o.has("commands") && o.get("commands").isJsonArray()) {
            for (var el : o.getAsJsonArray("commands")) {
                w.commands.add(el.getAsString());
            }
        }
        return w;
    }
}
