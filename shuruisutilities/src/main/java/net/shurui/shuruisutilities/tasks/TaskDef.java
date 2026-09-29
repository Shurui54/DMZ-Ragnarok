package net.shurui.shuruisutilities.tasks;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * One entry in a board's pool: a task an admin has written, which a player's slot may roll.
 *
 * <p>A definition is a TEMPLATE, never a player's copy. Nothing here changes as somebody plays; their progress
 * and slot state live in their own record and only reference this by {@link #id}. That is what lets an admin
 * retune a task's reward without disturbing anyone part-way through it, and what makes a task that is deleted
 * from the pool degrade to "this slot rolls something else next reset" rather than corrupting a save.
 *
 * <p>Plain-data object (public fields, explicit save/load/encode/decode) matching the shape every other editable
 * config in the suite uses, so the {@code /rggui admin} editor can drive it and a new field is four small edits.
 */
public class TaskDef
{
    /** Stable id, lowercase. PERSISTED in every player's slot, so renaming one orphans anybody holding it. */
    public String id = "";

    /** Which board this task belongs to. A task is only ever rolled by its own period's slots. */
    public TaskPeriod period = TaskPeriod.DAILY;

    /** Shown on the row. Supports the suite's &amp; colour codes. */
    public String name = "New Task";

    /** The line under the name, and what the quest info banner shows while it is active. */
    public String description = "";

    public TaskGoal goal = TaskGoal.KILL;

    /** Entity / item / block / dimension id the goal counts, or blank for "anything of that kind". */
    public String target = "";

    /** How many times it has to happen. Clamped to at least 1 on load; a zero-goal task would be born complete. */
    public int amount = 1;

    /** Zeni paid on claim. */
    public long rewardZeni = 0L;

    /** DMZ Training Points paid on claim. */
    public int rewardTp = 0;

    /**
     * Console commands run on claim, one per entry, with {@code %player%} substituted.
     *
     * <p>The escape hatch that keeps this system from needing to know about every other one: a task can hand out
     * an item, a title, a rank or a key by running the command that already does it, rather than this class
     * growing a field per reward type in the suite.
     */
    public final List<String> rewardCommands = new ArrayList<>();

    /**
     * How often this task may be rolled, relative to the rest of the pool. 0 takes it out of rotation without
     * deleting it, which is how a seasonal task is parked rather than lost.
     */
    public int weight = 10;

    public TaskDef()
    {
    }

    public TaskDef(String id, TaskPeriod period)
    {
        this.id = id == null ? "" : id;
        this.period = period == null ? TaskPeriod.DAILY : period;
    }

    /** A deep copy, so an editor screen can be cancelled without having mutated the live pool. */
    public TaskDef copy()
    {
        TaskDef c = new TaskDef(id, period);
        c.name = name;
        c.description = description;
        c.goal = goal;
        c.target = target;
        c.amount = amount;
        c.rewardZeni = rewardZeni;
        c.rewardTp = rewardTp;
        c.rewardCommands.addAll(rewardCommands);
        c.weight = weight;
        return c;
    }

    /** Fill in anything a hand-edited or older file left null, so the rest of the system never guards for it. */
    public TaskDef normalise()
    {
        if (id == null)
            id = "";
        if (period == null)
            period = TaskPeriod.DAILY;
        if (name == null)
            name = "";
        if (description == null)
            description = "";
        if (goal == null)
            goal = TaskGoal.KILL;
        if (target == null)
            target = "";
        // A task whose goal is zero would be complete the moment it was rolled, and would sit there paying out
        // for nothing; a negative one could never complete at all.
        amount = Math.max(1, amount);
        rewardZeni = Math.max(0L, rewardZeni);
        rewardTp = Math.max(0, rewardTp);
        weight = Math.max(0, weight);
        return this;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(id);
        buf.writeUtf(period.key);
        buf.writeUtf(name);
        buf.writeUtf(description);
        buf.writeUtf(goal.key);
        buf.writeUtf(target);
        buf.writeInt(amount);
        buf.writeLong(rewardZeni);
        buf.writeInt(rewardTp);
        buf.writeInt(rewardCommands.size());
        for (String c : rewardCommands)
            buf.writeUtf(c);
        buf.writeInt(weight);
    }

    public static TaskDef decode(FriendlyByteBuf buf)
    {
        TaskDef d = new TaskDef();
        d.id = buf.readUtf();
        d.period = TaskPeriod.byKey(buf.readUtf());
        d.name = buf.readUtf();
        d.description = buf.readUtf();
        d.goal = TaskGoal.byKey(buf.readUtf());
        d.target = buf.readUtf();
        d.amount = buf.readInt();
        d.rewardZeni = buf.readLong();
        d.rewardTp = buf.readInt();
        int n = buf.readInt();
        for (int i = 0; i < n; i++)
            d.rewardCommands.add(buf.readUtf());
        d.weight = buf.readInt();
        return d.normalise();
    }

    /**
     * Serialise this definition to NBT for the cross-server state sync. Deterministic field order so the sync's
     * content hash only moves on a real edit, never on re-serialisation of the same values.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        t.putString("period", (period == null ? TaskPeriod.DAILY : period).key);
        t.putString("name", name == null ? "" : name);
        t.putString("description", description == null ? "" : description);
        t.putString("goal", (goal == null ? TaskGoal.KILL : goal).key);
        t.putString("target", target == null ? "" : target);
        t.putInt("amount", amount);
        t.putLong("rewardZeni", rewardZeni);
        t.putInt("rewardTp", rewardTp);
        ListTag cmds = new ListTag();
        for (String c : rewardCommands)
            cmds.add(StringTag.valueOf(c == null ? "" : c));
        t.put("rewardCommands", cmds);
        t.putInt("weight", weight);
        return t;
    }

    public static TaskDef fromNbt(CompoundTag t)
    {
        TaskDef d = new TaskDef();
        d.id = t.getString("id");
        d.period = TaskPeriod.byKey(t.getString("period"));
        d.name = t.getString("name");
        d.description = t.getString("description");
        d.goal = TaskGoal.byKey(t.getString("goal"));
        d.target = t.getString("target");
        d.amount = t.getInt("amount");
        d.rewardZeni = t.getLong("rewardZeni");
        d.rewardTp = t.getInt("rewardTp");
        ListTag cmds = t.getList("rewardCommands", Tag.TAG_STRING);
        for (int i = 0; i < cmds.size(); i++)
            d.rewardCommands.add(cmds.getString(i));
        d.weight = t.getInt("weight");
        return d.normalise();
    }
}
