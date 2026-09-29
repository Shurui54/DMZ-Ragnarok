package net.shurui.dev.shuruis_dmz_dungeons.block;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.util.RandomSource;

import java.util.List;

// one weighted enemy preset for a dungeon floor. a THIN WRAPPER around a full SpawnerConfig plus a selection weight,
// so every SpawnerConfig field, editor and spawn path is reused. no separate name field: the row label reuses
// SpawnerConfig.displayName. Works with sdu absent (SpawnerConfig carries only primitives + vanilla ids).
public class SpawnerPreset {

    // the wrapped spawner: entity, model, stats, count, conditions, rewards and disguise all live here. Never null.
    public SpawnerConfig config = new SpawnerConfig();
    // weighted-random selection weight; higher means picked more often. clamped up to 1 at pick time (weightedPick),
    // so a stored 0/negative weight never divides by zero or throws.
    public int weight = 1;

    public SpawnerPreset() {
    }

    public SpawnerPreset(SpawnerConfig config, int weight) {
        this.config = config == null ? new SpawnerConfig() : config;
        this.weight = weight;
    }

    // deep copy: the wrapped SpawnerConfig is copied in full so the two presets never share mutable state.
    public SpawnerPreset copy() {
        return new SpawnerPreset((config == null ? new SpawnerConfig() : config).copy(), weight);
    }

    public CompoundTag save(CompoundTag tag) {
        tag.put("Config", (config == null ? new SpawnerConfig() : config).save(new CompoundTag()));
        tag.putInt("Weight", weight);
        return tag;
    }

    public static SpawnerPreset load(CompoundTag tag) {
        SpawnerPreset p = new SpawnerPreset();
        p.config = tag.contains("Config") ? SpawnerConfig.load(tag.getCompound("Config")) : new SpawnerConfig();
        p.weight = tag.contains("Weight") ? tag.getInt("Weight") : 1;
        return p;
    }

    public void encode(FriendlyByteBuf buf) {
        (config == null ? new SpawnerConfig() : config).encode(buf);
        buf.writeInt(weight);
    }

    public static SpawnerPreset decode(FriendlyByteBuf buf) {
        SpawnerPreset p = new SpawnerPreset();
        p.config = SpawnerConfig.decode(buf);
        p.weight = buf.readInt();
        return p;
    }

    // pick one preset weighted by weight. the seam generation calls to choose which enemy family fills a spawn marker.
    // null/empty -> null; weights clamp up to 1 so a zero/negative never divides by zero.
    public static SpawnerPreset weightedPick(List<SpawnerPreset> presets, RandomSource random) {
        if (presets == null || presets.isEmpty()) {
            return null;
        }
        if (presets.size() == 1) {
            return presets.get(0);
        }
        int total = 0;
        for (SpawnerPreset p : presets) {
            total += Math.max(1, p == null ? 1 : p.weight);
        }
        int r = random.nextInt(total);
        int acc = 0;
        for (SpawnerPreset p : presets) {
            acc += Math.max(1, p == null ? 1 : p.weight);
            if (r < acc) {
                return p;
            }
        }
        return presets.get(presets.size() - 1);
    }
}
