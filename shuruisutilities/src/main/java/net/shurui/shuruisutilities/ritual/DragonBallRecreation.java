package net.shurui.shuruisutilities.ritual;

import net.minecraft.network.chat.Component;

/**
 * The namekian half of the dragon ball cycle: paying to put back what a Super Saiyan 5 spent.
 *
 * <p>The shape of it mirrors the fusion deliberately. Same level floor, same level cost, same server wide
 * announcement, so the two ends of the cycle read as one bargain rather than two unrelated features: one race spends
 * the balls to become something, the other spends itself to bring them back.
 *
 * <p>Only the SHARED shape lives in core: the prompt the idol screen shows ({@link Prompt}, carried by
 * {@code PacketIdolPrompt}), the refusal reasons and their chat lines. Deciding and performing the recreation is the
 * Ragnarok Key's (feature {@code rituals}, see {@link net.shurui.shuruisutilities.api.key.RitualHooks}); without the
 * key the idol stays silent.
 */
public final class DragonBallRecreation
{
    private DragonBallRecreation() {}

    /** Level floor and cost. Held equal to the fusion's on purpose; see the class note. */
    public static final int REQUIRED_LEVEL = WishRitualManager.REQUIRED_LEVEL;
    public static final int LEVEL_COST = WishRitualManager.LEVEL_COST;

    /** Why a player cannot perform the ritual, or {@link Refusal#NONE}. Sent by ordinal in packet 70: never reorder. */
    public enum Refusal
    {
        NONE, NOT_NAMEKIAN, TOO_LOW_LEVEL, NO_IDOL, NOTHING_TO_RESTORE
    }

    /**
     * Everything the confirmation screen needs, decided on the server so the prompt cannot promise something the
     * ritual will then refuse.
     */
    public record Prompt(String set, int level, int requiredLevel, int levelCost, int ballCount, Refusal refusal)
    {
        public boolean allowed()
        {
            return refusal == Refusal.NONE;
        }
    }

    /** The chat line explaining a refusal, in the same words the screen uses. */
    public static Component refusalMessage(Prompt prompt)
    {
        return switch (prompt.refusal())
        {
            case NOT_NAMEKIAN -> Component.translatable("ritual.dmz_ragnarok.recreated.not_namekian");
            case TOO_LOW_LEVEL -> Component.translatable("ritual.dmz_ragnarok.recreated.level", prompt.requiredLevel());
            case NO_IDOL -> Component.translatable("ritual.dmz_ragnarok.recreated.no_idol");
            case NOTHING_TO_RESTORE -> Component.translatable("ritual.dmz_ragnarok.recreated.nothing");
            case NONE -> Component.empty();
        };
    }
}
