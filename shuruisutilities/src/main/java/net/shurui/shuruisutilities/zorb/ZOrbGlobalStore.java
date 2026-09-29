package net.shurui.shuruisutilities.zorb;

import java.io.File;

import net.minecraft.nbt.CompoundTag;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The single server-wide {@link ZOrbGlobals}, persisted to an EXPLICIT file, {@code <SUdir>/zorbs.json}. It is
 * not a Forge config (it must be readable long before configs load) and not a SavedData (it is admin state, not
 * world state), so it is a plain Gson file exactly like {@code tpboost.json}.
 *
 * <p>Keyless: nothing calls {@link #load()} on a keyless server except the NPC-region module boot, which reads the
 * file if present and otherwise holds harmless defaults in memory; no keyless path ever writes it. The file only
 * gains content once an admin saves through the Z orb editor (which the save packet refuses without the key).
 *
 * <p>{@link #toNbt()} / {@link #applyNbt(CompoundTag)} give the Ragnarok Key a stable, single-key NBT form to
 * register with {@code ShardStateSync} under {@code zorbs:globals}, so the globals travel the shard network.
 */
public final class ZOrbGlobalStore
{
    private ZOrbGlobalStore() {}

    private static volatile ZOrbGlobals globals = new ZOrbGlobals();

    private static File saveFile()
    {
        return new File(ShuruisUtilities.getSUDirectory(), "zorbs.json");
    }

    /** The live globals (never null). Defaults until {@link #load()} runs. */
    public static ZOrbGlobals get()
    {
        return globals;
    }

    /** Replace the live globals with a sanitised copy, then persist. */
    public static void set(ZOrbGlobals next)
    {
        if (next == null)
            return;
        next.sanitize();
        globals = next;
        save();
    }

    /** Read the file into memory, falling back to defaults when it is absent or unreadable. */
    public static void load()
    {
        try
        {
            File f = saveFile();
            if (f.exists())
            {
                ZOrbGlobals loaded = DataManager.load(ZOrbGlobals.class, f);
                if (loaded != null)
                {
                    loaded.sanitize();
                    globals = loaded;
                    return;
                }
            }
            globals = new ZOrbGlobals();
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ZOrbs] Could not load zorbs.json, using defaults: {}", t.toString());
            globals = new ZOrbGlobals();
        }
    }

    /** Persist the live globals to {@code <SUdir>/zorbs.json}. */
    public static void save()
    {
        try
        {
            DataManager.save(globals, saveFile());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[ZOrbs] Could not save zorbs.json: {}", t.toString());
        }
    }

    /**
     * The live globals as a single-key CompoundTag (JSON under {@code json}), for the shard state sync. One stable
     * key keeps the change-detection hash deterministic.
     */
    public static CompoundTag toNbt()
    {
        CompoundTag tag = new CompoundTag();
        tag.putString("json", DataManager.getGson().toJson(globals));
        return tag;
    }

    /**
     * Apply globals delivered by the shard network, then persist locally so this server keeps them across a
     * restart. Throws when the payload cannot be parsed, so the sync retries rather than dropping the edit.
     */
    public static void applyNbt(CompoundTag tag)
    {
        if (tag == null || !tag.contains("json"))
            return;
        ZOrbGlobals incoming = DataManager.getGson().fromJson(tag.getString("json"), ZOrbGlobals.class);
        if (incoming == null)
            throw new IllegalStateException("zorbs:globals payload did not parse");
        incoming.sanitize();
        globals = incoming;
        save();
    }
}
