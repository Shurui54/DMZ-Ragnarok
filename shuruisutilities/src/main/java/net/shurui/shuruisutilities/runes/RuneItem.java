package net.shurui.shuruisutilities.runes;

import java.util.List;
import java.util.Random;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * One rune. A rune carries the stat it favours and its own rolled tier, which decides how hard it can push that stat
 * when socketed.
 *
 * <p>A rune with a null stat is DORMANT: the raw material. It is what a piece gives back when it breaks, and what a
 * player awakens into a stat rune, so a broken piece returns something useful without returning the roll itself.
 */
public class RuneItem extends Item
{
    private static final String TAG_TIER = "SURuneTier";

    private final RuneStat stat;
    private final RuneGrade grade;
    /** True for the one admin rune that fills EVERY stat rather than a single named one. */
    private final boolean allStats;

    public RuneItem(Properties props, RuneStat stat)
    {
        this(props, stat, RuneGrade.NORMAL);
    }

    public RuneItem(Properties props, RuneStat stat, RuneGrade grade)
    {
        this(props, stat, grade, false);
    }

    public RuneItem(Properties props, RuneStat stat, RuneGrade grade, boolean allStats)
    {
        super(props);
        this.stat = stat;
        this.grade = grade;
        this.allStats = allStats;
    }

    /** Whether this rune fills every stat. Only the all-stat admin rune does. */
    public boolean isAllStats()
    {
        return allStats;
    }

    /** Whether this rune fills its stat outright instead of rolling a tier and charging a downside. */
    public boolean isAdmin()
    {
        return grade == RuneGrade.ADMIN;
    }

    public RuneGrade grade()
    {
        return grade;
    }

    @Override
    public boolean isFoil(ItemStack stack)
    {
        // The sheen IS the grade's marking: all three grades share one piece of art, so the glint, and its colour,
        // is what tells them apart on sight. A dormant rune has no grade to advertise and stays dull.
        return (!isDormant() && grade.foil()) || super.isFoil(stack);
    }

    /** The stat this rune favours, or null for a dormant rune. */
    public RuneStat stat()
    {
        return stat;
    }

    public boolean isDormant()
    {
        // the all-stat admin rune also has no single stat, but it is the opposite of dormant: it is the strongest
        // rune there is. Without this it would be treated as a raw rune and be awakenable at the bench.
        return stat == null && !allStats;
    }

    /** Whether this stack has actually been rolled, as opposed to reading as the default. */
    public static boolean hasTier(ItemStack stack)
    {
        return stack != null && stack.hasTag() && stack.getTag().contains(TAG_TIER);
    }

    /** This rune's tier, or LOW for one that has never been rolled. Prefer {@link #ensureTier} before using it. */
    public static RuneTier tier(ItemStack stack)
    {
        if (stack == null || !stack.hasTag())
            return RuneTier.LOW;
        CompoundTag t = stack.getTag();
        return t.contains(TAG_TIER) ? RuneTier.byName(t.getString(TAG_TIER)) : RuneTier.LOW;
    }

    /**
     * Give a rune its tier if it has none, rolled from its own grade.
     *
     * <p>The tier lives in stack NBT, so anything that produces a rune WITHOUT going through {@link #rolled} hands
     * out a stack with no tag: the creative menu, {@code /give}, a loot table, another mod. Reading that as LOW is
     * how every greater rune in creative came out worth a single arrow no matter what its tooltip said, which made
     * the grades look broken and the armour barely move.
     *
     * <p>Rolling here rather than defaulting means the grade decides the tier exactly as it does for a crafted
     * rune, and it happens once: the stamp is what stops it re-rolling on the next call.
     *
     * @return the tier the stack now carries
     */
    public static RuneTier ensureTier(ItemStack stack, Random rng)
    {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof RuneItem rune) || rune.isDormant())
            return RuneTier.LOW;
        if (hasTier(stack))
            return tier(stack);
        RuneTier rolled = rune.grade().rollTier(rng);
        setTier(stack, rolled);
        return rolled;
    }

    public static void setTier(ItemStack stack, RuneTier tier)
    {
        if (stack != null && !stack.isEmpty() && tier != null)
            stack.getOrCreateTag().putString(TAG_TIER, tier.name());
    }

    /** A rune of this stat and grade, with a tier rolled inside that grade's band. */
    public static ItemStack rolled(RuneStat stat, RuneGrade grade, Random rng)
    {
        ItemStack s = new ItemStack(RuneItems.of(stat, grade).get());
        setTier(s, grade.rollTier(rng));
        return s;
    }

    /** Convenience for the ordinary grade. */
    public static ItemStack rolled(RuneStat stat, Random rng)
    {
        return rolled(stat, RuneGrade.NORMAL, rng);
    }

    /**
     * Roll the tier the first time this stack is held, so a rune from the creative menu, a command or a loot table
     * is a real rune rather than a blank one that reads as the lowest tier.
     *
     * <p>Server side only, and only for a stack that has never been stamped, so this is one tag check per rune per
     * tick and nothing else. The tooltip is what the player judges a rune by, so it has to be true before they act
     * on it, which rules out waiting until the rune is socketed.
     */
    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot,
            boolean selected)
    {
        if (!level.isClientSide && !isDormant() && !isAdmin() && !hasTier(stack))
            ensureTier(stack, ROLL);
    }

    /** Shared source for lazy tier rolls. A rune's tier is cosmetic-until-socketed, so it needs no seeded stream. */
    private static final Random ROLL = new Random();

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tip, TooltipFlag flag)
    {
        RuneTier t = tier(stack);
        if (isDormant())
        {
            tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.dormant").withStyle(ChatFormatting.DARK_GRAY));
            return;
        }
        // An ADMIN rune describes itself differently because it behaves differently: it charges nothing to the
        // opposite stat and it has no tier to roll, so printing either would be a lie. The all-stat one also has no
        // single stat to name, and reading `stat` for it is what crashed the creative inventory: the search tree
        // asks every item for its tooltip, so one null here took the whole screen down.
        if (isAdmin())
        {
            tip.add((allStats
                    ? Component.translatable("tooltip.dmz_ragnarok.rune.admin_all")
                    : Component.translatable("tooltip.dmz_ragnarok.rune.admin_stat",
                            Component.literal(stat.label()).withStyle(stat.colour())))
                    .withStyle(ChatFormatting.GOLD));
            tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.admin_free").withStyle(ChatFormatting.GRAY));
            tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.grade",
                    Component.literal(grade.label()).withStyle(grade.colour())).withStyle(ChatFormatting.GRAY));
            return;
        }
        tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.stat",
                Component.literal(stat.label()).withStyle(stat.colour())).withStyle(ChatFormatting.GRAY));
        // What it costs, on the same footing as what it gives: socketing always takes the price out of the opposite.
        tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.weakens",
                Component.literal(stat.opposite().label()).withStyle(ChatFormatting.RED))
                .withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.grade",
                Component.literal(grade.label()).withStyle(grade.colour())).withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.tier",
                Component.literal(t.label()).withStyle(t.colour())).withStyle(ChatFormatting.GRAY));
        tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.hint").withStyle(ChatFormatting.DARK_GRAY));
    }
}
