package net.shurui.shuruisutilities.runes;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.entity.player.ItemTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Draws the rune block on an armour tooltip: the slot pips, one arrow row per stat, and the equipment tier.
 *
 * <p>The rows are text, not a custom tooltip renderer, on purpose. A text tooltip composes with every other mod's
 * lines, survives being shown in any inventory or JEI-style screen, and needs no client-side rendering hook, where a
 * custom renderer would have to fight for the same space and would vanish anywhere it was not installed.
 *
 * <p>Filled arrows take the stat's own colour so the row reads at a glance, and unfilled ones are dark grey, exactly
 * as in the reference art. A negative row is drawn with down arrows in red.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class RuneTooltip
{
    private RuneTooltip() {}

    private static final String ARROW_UP = "↑";
    private static final String ARROW_DOWN = "↓";
    private static final String PIP_FULL = "◆";
    private static final String PIP_EMPTY = "◇";

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event)
    {
        ItemStack stack = event.getItemStack();
        if (!ArmorRunes.has(stack))
            return;
        List<Component> tip = event.getToolTip();

        tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.slots", pips(stack))
                .withStyle(ChatFormatting.GOLD));
        tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.affinity").withStyle(ChatFormatting.GOLD));
        for (RuneStat s : RuneStat.values())
            tip.add(row(stack, s));
        RuneTier tier = ArmorRunes.tier(stack);
        tip.add(Component.translatable("tooltip.dmz_ragnarok.rune.equipment_tier",
                Component.literal(tier.label()).withStyle(tier.colour())).withStyle(ChatFormatting.GOLD));

        // Removal warning, per socketed rune, so the risk is visible BEFORE the player opens a bench and finds out.
        int pct = Math.round(RuneExtraction.DAMAGE_CHANCE * 100.0f);
        for (ArmorRunes.Socket so : ArmorRunes.sockets(stack))
        {
            String key = RuneExtraction.wouldRiskDestruction(so.tier())
                    ? "tooltip.dmz_ragnarok.rune.remove_break"
                    : "tooltip.dmz_ragnarok.rune.remove_degrade";
            tip.add(Component.translatable(key,
                    Component.literal(so.stat().label()).withStyle(so.stat().colour()),
                    Component.literal(pct + "%").withStyle(ChatFormatting.RED))
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    /** Filled pip per socketed rune, hollow per free slot, coloured by whatever is in them. */
    private static MutableComponent pips(ItemStack stack)
    {
        List<RuneStat> socketed = ArmorRunes.socketStats(stack);
        MutableComponent out = Component.empty();
        for (int i = 0; i < ArmorRunes.SOCKETS; i++)
        {
            if (i < socketed.size())
                out.append(Component.literal(PIP_FULL + " ").withStyle(socketed.get(i).colour()));
            else
                out.append(Component.literal(PIP_EMPTY + " ").withStyle(ChatFormatting.DARK_GRAY));
        }
        return out;
    }

    /**
     * One stat's row: five arrow positions then the stat name. Positive rows fill from the left in the stat's
     * colour, negative rows fill in red with down arrows, and the remainder are dark grey, so the eye reads how far
     * from neutral a piece is without reading any numbers.
     */
    private static MutableComponent row(ItemStack stack, RuneStat stat)
    {
        int arrows = ArmorRunes.arrows(stack, stat);
        int filled = Math.min(ArmorRunes.MAX_ARROWS, Math.abs(arrows));
        boolean negative = arrows < 0;
        String glyph = negative ? ARROW_DOWN : ARROW_UP;
        ChatFormatting on = negative ? ChatFormatting.RED : stat.colour();

        MutableComponent out = Component.empty();
        for (int i = 0; i < ArmorRunes.MAX_ARROWS; i++)
        {
            boolean lit = i < filled;
            out.append(Component.literal(glyph + " ")
                    .withStyle(lit ? on : ChatFormatting.DARK_GRAY));
        }
        out.append(Component.literal(stat.label()).withStyle(ChatFormatting.WHITE));
        return out;
    }
}
