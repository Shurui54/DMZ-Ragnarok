package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;

/**
 * THE ONE PLACE that answers "may this cosmetic move". Every future transfer route must call it.
 *
 * <h2>Why this exists before anything can move</h2>
 * A cosmetic bought with real money that leaks into the trade economy is not a bug that can be patched after the
 * fact, because the copies are already out. So the chokepoint is built first, in milestone 1, with every route
 * named in {@link TransferRoute}, and each milestone that adds a way to move an item wires that one route in
 * rather than inventing its own check. If a second place in this codebase ever reads
 * {@link CosmeticDef#tradeable}, that is the bug: there must be exactly one answer.
 *
 * <h2>The default is NO, TWICE</h2>
 * {@link CosmeticDef#tradeable} defaults to false, decided by the owner, so a KIND of cosmetic is BOUND until an
 * admin deliberately ticks the box. Everything imported or converted therefore arrives bound and nothing becomes
 * auctionable on ship day by accident. Same default-deny posture as the key allow-lists.
 *
 * <p>{@link CosmeticOwnership#bound} is a SECOND default-deny on top of it, per COPY. A shop purchase sets it,
 * whatever the definition says, so a shop cannot become an infinite supply into the trade economy while a
 * crate-won copy of the very same hat stays tradeable. Both have to say yes.
 *
 * <h2>The LEDGER is the authority. The item's NBT is not.</h2>
 * A token carries a catalogue id and an instance id and nothing else that is trusted. Quality is written onto it
 * as {@link #TAG_QUALITY} for a tooltip, and that tag is DISPLAY ONLY: a forged one must buy nothing. Every
 * decision here re-reads the ownership row, because an item's NBT is whatever the last person to touch it says
 * it is. The same discipline the class already applies to {@code tradeable} applies to quality and to bound.
 *
 * <h2>It is cheap on the hot paths</h2>
 * {@link #mayMove} returns true immediately for any stack that is not a cosmetic token, at the cost of one NBT
 * key check, so it can sit on {@code ItemTossEvent} and a container-insert guard without being felt. It never
 * looks the catalogue up for an ordinary item.
 *
 * <h2>What a "cosmetic token" is</h2>
 * The item form of a tradeable cosmetic: an {@link ItemStack} whose NBT carries {@link #TAG_COSMETIC} (the
 * catalogue id) and {@link #TAG_INSTANCE} (the ownership instance id). The token ITEM itself is milestone 3; the
 * two tag names live here, now, because this class is what reads them and a later milestone inventing its own
 * names would mean this gate silently stopped recognising them. Nothing else may hardcode these strings.
 */
public final class CosmeticTransferGate
{
    private CosmeticTransferGate()
    {
    }

    /** NBT key on a cosmetic token holding the {@link CosmeticDef#id}. Never rename: it is persisted on items. */
    public static final String TAG_COSMETIC = "SUCosmetic";

    /** NBT key holding the {@link CosmeticOwnership#instanceId}. Never rename: it is persisted on items. */
    public static final String TAG_INSTANCE = "SUCosmeticInstance";

    /**
     * NBT key holding the {@link CosmeticQuality#key} of the copy, FOR A TOOLTIP AND NOTHING ELSE.
     *
     * <p>DISPLAY ONLY, and that is not a style note. It exists so a tooltip can colour a token without a ledger
     * lookup on every render tick. Nothing may gate, price, roll or grant on it, because an item's NBT is
     * whatever the last hand to touch it wrote: a forged {@code magic} here must buy exactly nothing. The
     * ownership row named by {@link #TAG_INSTANCE} is the authority, and {@link #mayMove} reads that.
     *
     * <p>Never rename: it is persisted on items. Same rule as the two above.
     */
    public static final String TAG_QUALITY = "SUCosmeticQuality";

    /**
     * May this stack move by this route?
     *
     * <p>True for everything that is not a cosmetic token, so a caller can put this in front of any item move
     * without special-casing. For a token it is {@link CosmeticDef#tradeable} on the CURRENT catalogue entry AND
     * {@link CosmeticOwnership#bound} on the copy's ledger row. Reading the CURRENT definition is what makes an
     * admin flipping a cosmetic back to bound take effect on tokens already in the world: they become redeem-only
     * and every route refuses them from that moment.
     *
     * <p>It also requires the row be ESCROWED, that is an OUTSTANDING token. A tradeable copy sitting in a wardrobe
     * is not escrowed, so there is no item to move; a token that has already been redeemed leaves its row not
     * escrowed, so a spent token refuses every route; and a forged item naming a real non-escrowed instance is
     * refused for the same reason. Being escrowed is the ledger's authoritative "this is a live token", which is
     * what the item's own NBT is not allowed to be.
     *
     * <p>Refused in every ambiguous case: a token naming a catalogue entry that no longer exists, a token naming
     * no ownership row, a row that has been revoked, a row that is not escrowed, and a call with no server to read
     * a ledger from. None of those can be SHOWN to be a live tradeable token, and refusing is the direction that
     * cannot leak.
     *
     * <p>Still cheap on the hot paths. A stack that is not a token costs one NBT key check, and a token on a
     * bound cosmetic costs one catalogue lookup, so the ledger is only ever read for a token that has already
     * passed both, at most once per attempted move.
     *
     * @param actor who is trying to move it, for logging and messages. May be null for a server-driven move.
     */
    public static boolean mayMove(ItemStack stack, TransferRoute route, ServerPlayer actor)
    {
        String id = cosmeticIdOf(stack);
        if (id == null)
            return true; // not a cosmetic token: this gate has no opinion
        CosmeticDef def = CosmeticCatalog.get(id);
        if (def == null)
            return false; // unknown entry: cannot be shown tradeable, so it does not move
        if (!def.tradeable)
            return false; // the KIND is bound, so no copy of it moves and the row need not be read
        // Past here the definition says yes, so the COPY has to be asked, which needs the ledger. The order
        // matters: the two cheap checks above answer for every token on a bound cosmetic without a lookup.
        MinecraftServer server = actor != null ? actor.getServer() : ServerLifecycleHooks.getCurrentServer();
        if (server == null)
            return false; // no server means no ledger means no answer, and refusing is the direction that cannot leak
        UUID instance = instanceIdOf(stack);
        if (instance == null)
            return false; // a token naming no row is a token nothing can vouch for
        CosmeticOwnership row = CosmeticLedgerData.get(server).row(instance);
        if (row == null || !row.live())
            return false; // unknown or revoked: same reasoning as an unknown catalogue entry
        if (!row.escrowed)
            return false; // spent or forged: an outstanding token is escrowed, so anything else is not vouched for
        return !row.bound;
    }

    /**
     * The quality written on a token FOR DISPLAY, defaulting to NORMAL.
     *
     * <p>Never an input to a decision. See {@link #TAG_QUALITY}: the tag is unsigned player-writable data and
     * anything that gated on it would be gating on a claim rather than on a fact. Ask the ledger instead.
     */
    public static CosmeticQuality displayQualityOf(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return CosmeticQuality.NORMAL;
        CompoundTag tag = stack.getTag();
        return tag == null ? CosmeticQuality.NORMAL : CosmeticQuality.byKey(tag.getString(TAG_QUALITY));
    }

    /**
     * The catalogue id carried by this stack, or null when it is not a cosmetic token.
     *
     * <p>One NBT key lookup, no catalogue access, so this is the cheap test callers on hot paths should use when
     * they only need to know "is this one of ours".
     */
    public static String cosmeticIdOf(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(TAG_COSMETIC))
            return null;
        String id = tag.getString(TAG_COSMETIC);
        return id == null || id.isBlank() ? null : id;
    }

    /** The ownership instance carried by this stack, or null. */
    public static UUID instanceIdOf(ItemStack stack)
    {
        if (stack == null || stack.isEmpty())
            return null;
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.hasUUID(TAG_INSTANCE))
            return null;
        return tag.getUUID(TAG_INSTANCE);
    }

    /** Whether this stack is a cosmetic token at all. */
    public static boolean isToken(ItemStack stack)
    {
        return cosmeticIdOf(stack) != null;
    }

    /**
     * The message to show the actor when {@link #mayMove} said no.
     *
     * <p>Named rather than generic on purpose: "this cosmetic is bound to you" is an answer, while a silently
     * cancelled action is a bug report. The route is included so the same refusal in a trade and in a container
     * do not read identically when only one of them is the surprise.
     */
    public static net.minecraft.network.chat.MutableComponent refusal(ItemStack stack, TransferRoute route)
    {
        String id = cosmeticIdOf(stack);
        CosmeticDef def = id == null ? null : CosmeticCatalog.get(id);
        String name = def == null || def.displayName == null || def.displayName.isBlank()
                ? (id == null ? "" : id)
                : def.displayName;
        return Component.translatable("message.dmz_ragnarok.cosmetics.bound", name,
                Component.translatable(route == null ? TransferRoute.TRADE.langKey() : route.langKey()));
    }
}
