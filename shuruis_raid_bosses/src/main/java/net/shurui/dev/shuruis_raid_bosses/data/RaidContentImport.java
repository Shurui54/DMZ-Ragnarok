package net.shurui.dev.shuruis_raid_bosses.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.server.MinecraftServer;

import net.shurui.dev.sdu.api.ContentImportHook;
import net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef;
import net.shurui.dev.shuruis_raid_bosses.rift.RiftDef;
import net.shurui.dev.shuruis_raid_bosses.rift.RiftDefs;

/**
 * The Raids side of {@link ContentImportHook}: parses an SNBT raid or rift payload from an event bundle (the
 * built-in Halloween pack ships {@code pumpkin_king} and {@code haunted_tear} this way) and stores it in this
 * module's own {@link RaidData} / {@link RiftDefs}. Registered once from the module constructor; when Raids is
 * absent nothing registers, so the key's import of a raid or rift is a no-op that the command reports.
 *
 * <p>The payload is the exact NBT shape {@link RaidBossDef#save()} / {@link RiftDef#save()} write, rendered as
 * SNBT, so a def a builder made in-game can be exported and re-imported unchanged. Loading through the def's own
 * {@code load} keeps every field default and migration guard in one place.
 *
 * <p>It also lists this module's eventOnly raid and rift ids for the admin event editor's pickers.
 */
public final class RaidContentImport implements ContentImportHook.Provider
{
    private RaidContentImport() {}

    /** Register the provider with core. Call once from the module constructor. */
    public static void register()
    {
        ContentImportHook.register(new RaidContentImport());
    }

    @Override
    public boolean importRaid(MinecraftServer server, String snbt)
    {
        if (server == null || snbt == null || snbt.isBlank())
        {
            return false;
        }
        CompoundTag tag = parse(snbt);
        // A raid tag always carries "raidType"; a rift tag never does (its own encounter is under "encounter",
        // and it carries the rift-only "minSpawnMinutes"). Requiring the raid marker keeps the two apart.
        if (tag == null || !tag.contains("id") || tag.getString("id").isBlank()
                || !tag.contains("raidType") || tag.contains("minSpawnMinutes"))
        {
            return false;
        }
        RaidBossDef def = RaidBossDef.load(tag);
        if (def.id == null || def.id.isBlank())
        {
            return false;
        }
        RaidData.get(server).putDef(def);
        return true;
    }

    @Override
    public boolean importRift(MinecraftServer server, String snbt)
    {
        if (server == null || snbt == null || snbt.isBlank())
        {
            return false;
        }
        CompoundTag tag = parse(snbt);
        // A rift tag always carries the rift-only "minSpawnMinutes" and never a raid's top-level "raidType".
        if (tag == null || !tag.contains("id") || tag.getString("id").isBlank()
                || !tag.contains("minSpawnMinutes") || tag.contains("raidType"))
        {
            return false;
        }
        RiftDef def = RiftDef.load(tag);
        if (def.id == null || def.id.isBlank())
        {
            return false;
        }
        RiftDefs.get(server).putDef(def);
        return true;
    }

    @Override
    public java.util.List<String> eventOnlyRaids(MinecraftServer server)
    {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (server == null)
        {
            return out;
        }
        for (RaidBossDef def : RaidData.get(server).allDefs().values())
        {
            if (def != null && def.eventOnly && def.id != null && !def.id.isBlank())
            {
                out.add(def.id);
            }
        }
        return out;
    }

    @Override
    public java.util.List<String> eventOnlyRifts(MinecraftServer server)
    {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (server == null)
        {
            return out;
        }
        for (RiftDef def : RiftDefs.get(server).allDefs().values())
        {
            if (def != null && def.eventOnly && def.id != null && !def.id.isBlank())
            {
                out.add(def.id);
            }
        }
        return out;
    }

    private static CompoundTag parse(String snbt)
    {
        try
        {
            return TagParser.parseTag(snbt.trim());
        }
        catch (Exception e)
        {
            return null;
        }
    }
}
