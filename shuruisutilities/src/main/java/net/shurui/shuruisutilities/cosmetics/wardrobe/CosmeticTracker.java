package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.Locale;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import net.shurui.shuruisutilities.tasks.TaskGoal;

/**
 * One counter an admin has attached to a cosmetic, as DATA.
 *
 * <h2>One condition model, two consumers</h2>
 * The counting condition is {@link TaskGoal} plus {@link #target}, exactly the pair the task system already uses,
 * rather than a second parallel notion of "a thing that can be counted". TaskGoal's own JavaDoc says adding a kind
 * is meant to be cheap and that nothing else keys off the enum, so a kind added for a tracker is a task condition
 * for free and the other way round. If this had its own enum, every future kind would have to be added twice and
 * the two lists would drift.
 *
 * <p>Nothing in milestone 1 increments a tracker. The record is defined, edited, persisted and synced now so the
 * counter that arrives later has somewhere to write and the admin's work is not redone.
 *
 * <p>Plain-data object (public fields, explicit save/load/encode/decode) matching {@code TaskDef}'s shape, so the
 * in game editor can drive it and a new field is four small edits.
 */
public class CosmeticTracker
{
    /**
     * Stable id, lowercase, unique within ONE cosmetic definition.
     *
     * <p>PERSISTED twice over: in the wearer's chosen-tracker field and, once counting exists, as the key of the
     * per-instance counter. Renaming one therefore resets everybody's count on it, which is why the editor makes
     * a rename a delete plus an add rather than a field.
     */
    public String id = "";

    /** What the wearer sees, for example "Saibamen slain". Supports the suite's &amp; colour codes. */
    public String label = "";

    /** The condition kind. The SAME enum the task board counts against. */
    public TaskGoal goal = TaskGoal.KILL;

    /** Entity / item / block / dimension id counted, or blank for "anything of that kind". */
    public String target = "";

    /**
     * Stop counting at this value. 0 means uncapped.
     *
     * <p>A cap exists so a cosmetic can read "100 / 100" and stay there rather than climbing forever, which is
     * the difference between a badge and a scoreboard.
     */
    public long cap = 0L;

    /**
     * How the number is presented, with one {@code %s} where the count goes, for example {@code "%s slain"}.
     *
     * <p>Presentation is the admin's, not the code's: a tracker that reads "3 souls taken" and one that reads
     * "3 blocks mined" differ only by this string, and hardcoding it would mean a code change per cosmetic.
     */
    public String format = "%s";

    public CosmeticTracker()
    {
    }

    public CosmeticTracker(String id)
    {
        this.id = id == null ? "" : id;
    }

    /** A deep copy, so an editor screen can be cancelled without having mutated the live catalogue. */
    public CosmeticTracker copy()
    {
        CosmeticTracker c = new CosmeticTracker(id);
        c.label = label;
        c.goal = goal;
        c.target = target;
        c.cap = cap;
        c.format = format;
        return c;
    }

    /** Fill in anything a hand-edited or older file left null, so the rest of the system never guards for it. */
    public CosmeticTracker normalise()
    {
        id = sanitizeId(id);
        if (label == null)
            label = "";
        if (goal == null)
            goal = TaskGoal.KILL;
        if (target == null)
            target = "";
        cap = Math.max(0L, cap);
        // A blank format would render a tracker as an empty string, which reads as a bug rather than as a choice.
        if (format == null || format.isBlank())
            format = "%s";
        return this;
    }

    /**
     * Normalise a typed tracker id: lowercase, letters, digits and underscore only.
     *
     * <p>Public because the editor validates a typed id with it BEFORE sending, so an admin is told no on the
     * screen rather than finding out later that their id silently became something else.
     */
    public static String sanitizeId(String raw)
    {
        if (raw == null)
            return "";
        StringBuilder sb = new StringBuilder(raw.length());
        for (char c : raw.trim().toLowerCase(Locale.ROOT).toCharArray())
        {
            if (Character.isLetterOrDigit(c) || c == '_')
                sb.append(c);
            else if (c == ' ' || c == '-')
                sb.append('_');
        }
        return sb.toString();
    }

    /** "kill minecraft:zombie / 100", for an editor list row. */
    public String summary()
    {
        StringBuilder sb = new StringBuilder(goal.key);
        if (goal.usesTarget() && target != null && !target.isBlank())
            sb.append(' ').append(target);
        if (cap > 0L)
            sb.append(" / ").append(cap);
        return sb.toString();
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(id);
        buf.writeUtf(label);
        buf.writeUtf(goal.key);
        buf.writeUtf(target);
        buf.writeLong(cap);
        buf.writeUtf(format);
    }

    public static CosmeticTracker decode(FriendlyByteBuf buf)
    {
        CosmeticTracker t = new CosmeticTracker();
        t.id = buf.readUtf();
        t.label = buf.readUtf();
        t.goal = TaskGoal.byKey(buf.readUtf());
        t.target = buf.readUtf();
        t.cap = buf.readLong();
        t.format = buf.readUtf();
        return t.normalise();
    }

    /**
     * Serialise to NBT for the cross-server state sync. Deterministic field order so the sync's content hash only
     * moves on a real edit, never on re-serialisation of the same values.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        t.putString("label", label == null ? "" : label);
        t.putString("goal", (goal == null ? TaskGoal.KILL : goal).key);
        t.putString("target", target == null ? "" : target);
        t.putLong("cap", cap);
        t.putString("format", format == null ? "%s" : format);
        return t;
    }

    public static CosmeticTracker fromNbt(CompoundTag t)
    {
        CosmeticTracker c = new CosmeticTracker();
        c.id = t.getString("id");
        c.label = t.getString("label");
        c.goal = TaskGoal.byKey(t.getString("goal"));
        c.target = t.getString("target");
        c.cap = t.getLong("cap");
        c.format = t.getString("format");
        return c.normalise();
    }
}
