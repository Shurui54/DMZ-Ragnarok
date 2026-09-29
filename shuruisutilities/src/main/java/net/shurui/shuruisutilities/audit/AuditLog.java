package net.shurui.shuruisutilities.audit;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * The one place an "who did what to whose things" line is written.
 *
 * <p>Every line starts {@value #PREFIX}, so the whole audit trail comes back out of a server log with a single
 * grep no matter which of the five source trees wrote it. The other addons deliberately do NOT call through
 * here: they log the same prefix through their own logger, because a shared prefix costs nothing and a shared
 * dependency does.
 *
 * <p>This exists because of a real incident. On 2026-08-22 an admin's backpack was emptied, and answering "who
 * opened it" was impossible from the server log: the suite logged no container access, no item use and no item
 * movement, and the server had command logging switched off, so 41,553 lines of debug log held nothing but the
 * victim asking in chat who had done it. Sophisticated Backpacks turned out to keep its own access log, which is
 * what actually answered it, and this is the equivalent for our own content.
 *
 * <p>This is diagnostics, not a feature, so it is deliberately NOT key gated: a server that cannot answer
 * "who took this" is a server that cannot be moderated, whatever tier it is running.
 */
public final class AuditLog
{
    private AuditLog() {}

    public static final String PREFIX = "[audit] ";

    public static void log(String format, Object... args)
    {
        LoggingHandler.sulog.info(PREFIX + format, args);
    }

    /** Dimension and rounded position, which is what a moderator actually needs to go and look. */
    public static String where(Entity entity)
    {
        if (entity == null)
            return "nowhere";
        return String.format("%s %d, %d, %d", entity.level().dimension().location(), Math.round(entity.getX()),
                Math.round(entity.getY()), Math.round(entity.getZ()));
    }

    /** An item as {@code count x registry:id}, since a display name can be renamed to anything by anybody. */
    public static String describe(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return "an empty hand";
        return stack.getCount() + " x "
                + net.minecraftforge.registries.ForgeRegistries.ITEMS.getKey(stack.getItem());
    }

    public static String name(Entity entity)
    {
        return entity == null ? "?" : entity.getName().getString();
    }
}
