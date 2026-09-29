package net.shurui.dev.sdu.transform;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

// ordered chain of TransformForms on a transforming NPC, persisted under persistentData key KEY ("sdu_tf").
// index tracks how many forms are consumed, so the remaining chain carries forward when TransformEngine swaps.
public class TransformChain {

    public static final String KEY = "sdu_tf";

    public List<TransformForm> forms = new ArrayList<>();

    public int index = 0;  // forms consumed so far

    public CompoundTag toNbt() {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (TransformForm f : forms) {
            list.add(f.toNbt());
        }
        tag.put("forms", list);
        tag.putInt("index", index);
        return tag;
    }

    public static TransformChain fromNbt(CompoundTag tag) {
        TransformChain c = new TransformChain();
        if (tag == null) {
            return c;
        }
        c.index = tag.getInt("index");
        ListTag list = tag.getList("forms", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            c.forms.add(TransformForm.fromNbt(list.getCompound(i)));
        }
        return c;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        for (TransformForm f : forms) {
            arr.add(f.toJson());
        }
        o.add("forms", arr);
        o.addProperty("index", index);
        return o;
    }

    // missing/absent -> empty chain.
    public static TransformChain fromJson(JsonObject o) {
        TransformChain c = new TransformChain();
        if (o == null) {
            return c;
        }
        c.index = GsonHelper.getAsInt(o, "index", 0);
        if (o.has("forms") && o.get("forms").isJsonArray()) {
            for (var el : o.getAsJsonArray("forms")) {
                if (el.isJsonObject()) {
                    c.forms.add(TransformForm.fromJson(el.getAsJsonObject()));
                }
            }
        }
        return c;
    }

    public boolean hasRemaining() {
        return index < forms.size();
    }

    // next un-consumed form. call only when hasRemaining().
    public TransformForm next() {
        return forms.get(index);
    }

    public static void writeToEntity(Entity e, TransformChain c) {
        if (e == null || c == null) {
            return;
        }
        e.getPersistentData().put(KEY, c.toNbt());
    }

    // null if absent/empty.
    public static TransformChain readFromEntity(Entity e) {
        if (e == null || !has(e)) {
            return null;
        }
        CompoundTag tag = e.getPersistentData().getCompound(KEY);
        TransformChain c = fromNbt(tag);
        return c.forms.isEmpty() ? null : c;
    }

    // true when e carries a non-empty chain.
    public static boolean has(Entity e) {
        if (e == null) {
            return false;
        }
        CompoundTag pd = e.getPersistentData();
        if (!pd.contains(KEY, Tag.TAG_COMPOUND)) {
            return false;
        }
        return !pd.getCompound(KEY).getList("forms", Tag.TAG_COMPOUND).isEmpty();
    }
}
