package net.shurui.shuruisutilities.staff;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;

/**
 * One piece of work: what it is, who it is for, who took it, and how far along it is.
 *
 * <p>A Gson document with public fields rather than an encapsulated object, matching the other persisted models in
 * this mod. Nothing outside {@code StaffTaskManager} should be writing to it.
 */
public class StaffTask
{
    /** The editor id of the staff task board (the hub row, its screens and their actions). */
    public static final String EDITOR = "stafftasks";

    /**
     * Where a task is in its life.
     *
     * <p>DENIED is not an end state and is not stored: a denial hands the task back to the person who took it, so
     * the status returns to {@link #ACCEPTED} and the denial survives as a note and a count. A task that ended
     * because the work was wrong should be finishable, not dead.
     */
    public enum Status
    {
        /** Written, assigned to one or more roles, waiting for somebody to take it. */
        OPEN,
        /** Taken by one staff member. Theirs until they finish it or drop it. */
        ACCEPTED,
        /** They say it is done. Waiting on a reviewer. */
        COMPLETED,
        /** A reviewer agreed. The only state that counts towards anybody's approved total. */
        APPROVED
    }

    public String id = UUID.randomUUID().toString();

    public String title = "";

    public String description = "";

    /** Role enum names this task may be taken by. Empty means any staff member may take it. */
    public List<String> roles = new ArrayList<>();

    public String status = Status.OPEN.name();

    public String createdBy = "";
    public String createdByName = "";
    public long createdAt = System.currentTimeMillis();

    /** Who holds it. Null or empty whenever the status is OPEN. */
    public String acceptedBy = "";
    public String acceptedByName = "";
    public long acceptedAt = 0L;

    public long completedAt = 0L;

    public String reviewedBy = "";
    public String reviewedByName = "";
    public long reviewedAt = 0L;

    /** Why the last reviewer sent it back, shown to whoever holds it so they know what to change. */
    public String lastDenialReason = "";
    public int denials = 0;

    public Status status()
    {
        try
        {
            return Status.valueOf(status);
        }
        catch (IllegalArgumentException e)
        {
            return Status.OPEN;
        }
    }

    public void setStatus(Status s)
    {
        status = s.name();
    }

    public UUID acceptedById()
    {
        if (acceptedBy == null || acceptedBy.isEmpty())
            return null;
        try
        {
            return UUID.fromString(acceptedBy);
        }
        catch (IllegalArgumentException e)
        {
            return null;
        }
    }

    /**
     * Whether this role may take this task.
     *
     * <p>An empty role list is deliberately "anybody on the roster" rather than "nobody": a task written without
     * naming a role is a task somebody forgot to narrow, and leaving it unclaimable would hide it forever.
     */
    public boolean isForRole(StaffRole role)
    {
        if (role == null)
            return false;
        if (roles == null || roles.isEmpty())
            return true;
        for (String r : roles)
            if (role.name().equals(r))
                return true;
        return false;
    }

    /** Serialise every field to NBT, deterministic order so the sync hash only moves on a real edit. */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        t.putString("title", title == null ? "" : title);
        t.putString("description", description == null ? "" : description);
        ListTag rl = new ListTag();
        if (roles != null)
            for (String r : roles)
                rl.add(StringTag.valueOf(r == null ? "" : r));
        t.put("roles", rl);
        t.putString("status", status == null ? Status.OPEN.name() : status);
        t.putString("createdBy", createdBy == null ? "" : createdBy);
        t.putString("createdByName", createdByName == null ? "" : createdByName);
        t.putLong("createdAt", createdAt);
        t.putString("acceptedBy", acceptedBy == null ? "" : acceptedBy);
        t.putString("acceptedByName", acceptedByName == null ? "" : acceptedByName);
        t.putLong("acceptedAt", acceptedAt);
        t.putLong("completedAt", completedAt);
        t.putString("reviewedBy", reviewedBy == null ? "" : reviewedBy);
        t.putString("reviewedByName", reviewedByName == null ? "" : reviewedByName);
        t.putLong("reviewedAt", reviewedAt);
        t.putString("lastDenialReason", lastDenialReason == null ? "" : lastDenialReason);
        t.putInt("denials", denials);
        return t;
    }

    public static StaffTask fromNbt(CompoundTag t)
    {
        StaffTask s = new StaffTask();
        s.id = t.getString("id");
        s.title = t.getString("title");
        s.description = t.getString("description");
        s.roles = new ArrayList<>();
        ListTag rl = t.getList("roles", Tag.TAG_STRING);
        for (int i = 0; i < rl.size(); i++)
            s.roles.add(rl.getString(i));
        s.status = t.getString("status");
        s.createdBy = t.getString("createdBy");
        s.createdByName = t.getString("createdByName");
        s.createdAt = t.getLong("createdAt");
        s.acceptedBy = t.getString("acceptedBy");
        s.acceptedByName = t.getString("acceptedByName");
        s.acceptedAt = t.getLong("acceptedAt");
        s.completedAt = t.getLong("completedAt");
        s.reviewedBy = t.getString("reviewedBy");
        s.reviewedByName = t.getString("reviewedByName");
        s.reviewedAt = t.getLong("reviewedAt");
        s.lastDenialReason = t.getString("lastDenialReason");
        s.denials = t.getInt("denials");
        return s;
    }

    /** The roles this task is for, as something printable. */
    public List<StaffRole> roleList()
    {
        List<StaffRole> out = new ArrayList<>();
        if (roles == null)
            return out;
        for (String r : roles)
        {
            try
            {
                out.add(StaffRole.valueOf(r));
            }
            catch (IllegalArgumentException ignored)
            {
            }
        }
        return out;
    }
}
