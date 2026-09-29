package net.shurui.shuruisutilities.staff;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.common.ForgeConfigSpec.Builder;

import net.shurui.shuruisutilities.core.config.ConfigBase;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The parsed settings for the weekly clocked-hours goal, and nothing else. The behaviour lives in
 * {@code StaffGoalManager} and in {@code ModuleStaff}'s tick and login hooks; this owns only the config surface.
 *
 * <p>Registered through the suite's module launcher (see {@code ModuleStaffGoal}), so it lands as
 * {@code StaffGoal.toml} in the ShuruisUtilities config directory. It is COMMON, a server-only operator setting with
 * no client half.
 *
 * <h2>Why the zone defaults to UTC</h2>
 * The week boundary must be one instant that all three shards agree on, or a shift that spans a Monday would be split
 * at a different moment on each server and the network total would not add up. UTC has no daylight-saving transitions,
 * so "Monday 00:00 UTC" is the same instant everywhere forever. An operator may set a local zone but is opting into
 * the twice-a-year divergence knowingly; an unparseable id falls back to UTC with a loud error rather than guessing.
 */
public final class StaffGoalConfig
{
    private StaffGoalConfig() {}

    private static final String CATEGORY = "StaffGoal";

    /** The five senior roles the owner named: exempt from the goal, and the ones notified when it is missed. */
    private static final List<String> DEFAULT_EXEMPT =
            List.of("head-mod", "admin", "head-admin", "server-manager", "owner");

    /** Master switch. True by default: the owner asked for the goal, so it runs once the staff module is unlocked. */
    public static volatile boolean enabled = true;

    /** Hours a shift staffer must clock on duty, network-wide, in a Monday-to-Monday week to meet the goal. */
    public static volatile int goalHours = 10;

    /** The resolved week-boundary zone, never null. UTC unless an operator set a valid other zone. */
    public static volatile ZoneId zone = ZoneOffset.UTC;

    /** Role group ids exempt from the goal, and the roles notified when another staffer misses it. Lower case. */
    public static volatile Set<String> exemptGroups = new HashSet<>(DEFAULT_EXEMPT);

    static ForgeConfigSpec.BooleanValue SUenabled;
    static ForgeConfigSpec.IntValue SUgoalHours;
    static ForgeConfigSpec.ConfigValue<String> SUtimeZone;
    static ForgeConfigSpec.ConfigValue<List<? extends String>> SUexempt;

    public static void load(Builder BUILDER, boolean isReload)
    {
        BUILDER.comment(
                "The weekly clocked-in-hours goal for staff. Every shift staffer is expected to clock at least",
                "GoalHours on duty across the network each week (Monday to Monday, in TimeZone below). When a week",
                "ends and a staffer fell short, the senior roles in ExemptRoles are notified on their next login.",
                "Those same roles are themselves exempt from the goal.")
                .push(CATEGORY);

        SUenabled = BUILDER
                .comment("Master switch for the weekly goal. When false, no week is evaluated and no one is notified.",
                        "Time is still recorded, so turning it back on loses nothing.")
                .define("Enabled", true);

        SUgoalHours = BUILDER
                .comment("Hours a shift staffer must clock on duty each week, summed across every server, to meet the",
                        "goal. The default is 10.")
                .defineInRange("GoalHours", 10, 1, 168);

        SUexempt = BUILDER
                .comment("Roles exempt from the goal, AND the roles notified when another staffer misses it. Use each",
                        "role's group id, lower case and hyphenated. The valid ids are: owner, server-manager,",
                        "head-admin, admin, head-mod, head-builder, mod, builder, trial-mod, helper. An id that is not",
                        "one of these is ignored.")
                .defineList("ExemptRoles", DEFAULT_EXEMPT, ConfigBase.stringValidator);

        SUtimeZone = BUILDER
                .comment("Zone the Monday-to-Monday week boundary is read in. Defaults to UTC on purpose: on a shard",
                        "network all servers must agree on one instant for the boundary, and a daylight-saving zone",
                        "would make them disagree twice a year. If you set a local zone you accept that risk. An id",
                        "java cannot parse falls back to UTC with an error in the log.")
                .define("TimeZone", "UTC");

        BUILDER.pop();
    }

    public static void bakeConfig(boolean reload)
    {
        enabled = SUenabled.get();
        goalHours = SUgoalHours.get();
        zone = resolveZone(SUtimeZone.get());
        Set<String> fresh = new HashSet<>();
        for (String s : SUexempt.get())
            if (s != null && !s.isBlank())
                fresh.add(s.trim().toLowerCase(Locale.ROOT));
        exemptGroups = fresh;
    }

    /** The goal expressed in milliseconds, which is what the banked time is measured in. */
    public static long goalMillis()
    {
        return (long) goalHours * 60L * 60L * 1000L;
    }

    /** Whether this role is exempt from the goal (and is therefore a notification recipient). */
    public static boolean isExempt(StaffRole role)
    {
        return role != null && exemptGroups.contains(role.group());
    }

    private static ZoneId resolveZone(String id)
    {
        String raw = id == null ? "" : id.trim();
        if (raw.isEmpty())
            return ZoneOffset.UTC;
        try
        {
            return ZoneId.of(raw);
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.error("[staff] TimeZone '{}' is not a valid zone id; falling back to UTC. Set a real "
                    + "id (for example UTC, or Europe/London) if you meant something else.", raw);
            return ZoneOffset.UTC;
        }
    }
}
