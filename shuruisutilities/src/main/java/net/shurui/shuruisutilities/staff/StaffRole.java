package net.shurui.shuruisutilities.staff;

import java.util.Locale;

/**
 * The staff roles, and what each one is allowed to skip.
 *
 * <p>Every constant names a permission group that already exists on the server; the enum adds nothing to the
 * permission system and owns no perms of its own. The group is still the only thing that grants anything, which is
 * what lets clocking in and out be nothing more cunning than adding and removing that group.
 *
 * <h2>Three bands, and why the split is where it is</h2>
 * <ul>
 *   <li>SHIFT roles clock in to be staff at all. Their group is off them whenever they are not on duty, so a
 *       moderator who is playing rather than working has a player's powers and a player's nameplate.</li>
 *   <li>OVERSIGHT roles do not clock in and are never touched by any of this. They are the people the system
 *       reports TO, and a system that can strip the owner's group is one bad clock away from locking everybody
 *       out of their own server.</li>
 *   <li>Within the shift band, {@link #autoRemovable} decides who the seven day sweep may act on by itself.
 *       Senior roles are flagged for a person to look at instead, because losing a head builder to a fortnight's
 *       holiday is a worse outcome than carrying an inactive one for another week.</li>
 * </ul>
 */
public enum StaffRole
{
    OWNER("owner", 100, false, false, true),
    SERVER_MANAGER("server-manager", 90, false, false, true),
    HEAD_ADMIN("head-admin", 80, false, false, true),

    ADMIN("admin", 70, true, false, true),
    HEAD_MOD("head-mod", 60, true, false, false),
    HEAD_BUILDER("head-builder", 55, true, false, false),
    MOD("mod", 40, true, true, false),
    BUILDER("builder", 35, true, true, false),
    TRIAL_MOD("trial-mod", 25, true, true, false),
    // Leads the helpers. Sits above helper and below trial mod, matching the Discord role order. Senior, so the
    // seven day sweep flags it for a person instead of removing it.
    HEAD_HELPER("head-helper", 22, true, false, false),
    HELPER("helper", 20, true, true, false);

    /** The permission group this role IS. Lower case, hyphenated, exactly as the group is named on the server. */
    private final String group;

    /** Seniority. Only ever compared, never displayed, so the gaps between the numbers mean nothing. */
    private final int tier;

    /** Whether being this role requires clocking in before the group is granted. */
    private final boolean shift;

    /** Whether the inactivity sweep may remove this role on its own authority. */
    private final boolean autoRemovable;

    /** Whether this role may approve or deny another staff member's completed task. */
    private final boolean reviewer;

    StaffRole(String group, int tier, boolean shift, boolean autoRemovable, boolean reviewer)
    {
        this.group = group;
        this.tier = tier;
        this.shift = shift;
        this.autoRemovable = autoRemovable;
        this.reviewer = reviewer;
    }

    public String group()
    {
        return group;
    }

    public int tier()
    {
        return tier;
    }

    /** Clocks in and out; holds its permission group only while on duty. */
    public boolean isShift()
    {
        return shift;
    }

    /** The seven day sweep may strip this role without asking anybody. */
    public boolean isAutoRemovable()
    {
        return autoRemovable;
    }

    /** Admin and above: gets the review notification and may approve or deny a completion. */
    public boolean canReview()
    {
        return reviewer;
    }

    /** Reviewers are also the people who may write tasks and decide which roles they land on. */
    public boolean canAssignTasks()
    {
        return reviewer;
    }

    /**
     * Inactive for too long but too senior to be stripped automatically, so the sweep reports them instead.
     *
     * <p>Exactly the shift roles the sweep may not remove. Oversight roles are excluded because they are never
     * inactive as far as this system is concerned: they do not clock in, so there is no clock to have gone quiet.
     */
    public boolean needsManualReview()
    {
        return shift && !autoRemovable;
    }

    /**
     * Team leads who can see every staff task that belongs to the roles below them, whatever state it is in, not
     * just the open jobs their own role may take: owner, server manager, head admin, head mod and head helper.
     */
    public boolean seesTasksBelow()
    {
        return this == OWNER || this == SERVER_MANAGER || this == HEAD_ADMIN || this == HEAD_MOD || this == HEAD_HELPER;
    }

    /** The name a player sees. Derived rather than stored so it can never drift from the group name. */
    public String display()
    {
        StringBuilder out = new StringBuilder(group.length());
        boolean upper = true;
        for (char c : group.toCharArray())
        {
            if (c == '-' || c == '_')
            {
                out.append(' ');
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }

    /** The role owning this permission group, or null if the group is not a staff group at all. */
    public static StaffRole byGroup(String group)
    {
        if (group == null)
            return null;
        String needle = group.trim().toLowerCase(Locale.ROOT);
        for (StaffRole role : values())
            if (role.group.equals(needle))
                return role;
        return null;
    }

    /** Same lookup, but tolerant of a player typing {@code head_mod} or {@code HeadMod} at a command prompt. */
    public static StaffRole byLooseName(String name)
    {
        if (name == null)
            return null;
        String needle = name.trim().toLowerCase(Locale.ROOT).replace('_', '-').replace(" ", "-");
        for (StaffRole role : values())
            if (role.group.equals(needle) || role.name().toLowerCase(Locale.ROOT).equals(needle.replace('-', '_')))
                return role;
        return null;
    }
}
