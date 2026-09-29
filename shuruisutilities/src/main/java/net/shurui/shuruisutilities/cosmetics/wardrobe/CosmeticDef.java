package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * One cosmetic, as an admin edits it. The heart of the system.
 *
 * <h2>This is DATA, not a registry</h2>
 * Which item counts as a head or a back, which qualities it may exist at, what it tracks and whether it may be
 * traded are all fields here, edited in game through the hub editor and persisted server side. There is
 * deliberately no hardcoded table of cosmetics anywhere and no JSON shipped in the jar: adding a hat must never
 * need a build. The only hardcoded parts of the feature are the ones that have to be code, and they are named in
 * {@link CosmeticSlot} and {@link CosmeticQuality}.

 * <h2>The definition says what a copy MAY be, never what a copy IS</h2>
 * {@link #allowedQualities} is an eligibility SET, which is the question a crate asks before it rolls. The
 * quality of one person's copy lives on their ownership row ({@link CosmeticOwnership#quality}), because a
 * definition is shared by everybody and a counter, an effect roll and a bound flag are facts about one copy.
 *
 * <h2>A definition is a TEMPLATE</h2>
 * Nothing here changes as somebody plays. Ownership rows and equipped slots reference a definition by {@link #id}
 * alone, which is what lets an admin retune a cosmetic's offsets or trackers without disturbing anybody wearing
 * it, and what makes a deleted definition degrade to "that slot renders nothing" rather than corrupting a save.
 * Same contract {@code TaskDef} states for tasks.
 *
 * <p>Plain-data object (public fields, explicit save/load/encode/decode) matching the shape every other editable
 * config in the suite uses, so the editor can drive it and a new field is four small edits.
 *
 * <h2>Adding a field later is free</h2>
 * Every persisted and wire form here is keyed by NAME, never by position or ordinal, so a field added in a later
 * milestone (a shop price, a mount or pet discriminator) reads as its default on an older record and needs no
 * migration. That is the same property that lets {@link CosmeticSlot#BODY} be switched on later, and it is why
 * fields that belong to unbuilt milestones are deliberately NOT pre-declared here.
 */
public class CosmeticDef
{
    /**
     * Stable lowercase id.
     *
     * <p>PERSISTED in every ownership row and in every player's equipped map, so renaming one orphans everybody
     * holding it. The editor therefore refuses to edit the id: a rename is a delete plus a new entry, which at
     * least makes the loss visible. Same rule, and the same reason, as {@code TaskDef.id}.
     */
    public String id = "";

    /** Shown in the wardrobe and the editor list. Supports the suite's &amp; colour codes. */
    public String displayName = "New Cosmetic";

    /** The line under the name in the wardrobe. */
    public String description = "";

    public CosmeticSlot slot = CosmeticSlot.HEAD;

    /**
     * Which qualities a copy of this cosmetic may be minted at. DEFAULT {@code { NORMAL }}.
     *
     * <p>An eligibility set, not a quality. A crate asks "may this roll Magic" and gets its answer from here; the
     * copy that comes out records its own quality on its ownership row. Default-deny in the useful direction: a
     * newly created cosmetic can only ever be Normal until an admin deliberately widens it, so nothing becomes
     * rollable as Magic by accident.
     *
     * <p>Never empty after {@link #normalise()}. A cosmetic eligible for no quality at all could not be granted
     * by anything, and an admin would have no way to see why.
     *
     * <p>PERSISTED THREE WAYS AND THEY DIFFER, deliberately: Gson writes the enum CONSTANT NAMES into
     * {@code cosmetics.json} (the same thing it already does for {@link #slot}), while NBT and the wire write the
     * stable lowercase {@link CosmeticQuality#key}s. Both are pinned. See {@link CosmeticQuality}'s class note.
     */
    public Set<CosmeticQuality> allowedQualities = CosmeticQuality.defaultSet();

    /**
     * Which effect pool a Magic roll on this item draws from when the thing doing the rolling does not say.
     * Blank means none.
     *
     * <p>THE DEFAULT FOR A FUTURE ROLL, not a record of a past one. Do not confuse it with
     * {@link CosmeticOwnership#effectPoolId}, which is HISTORY: that field says which pool one particular copy
     * actually came out of, and it never changes again once minted. This one is a lookup consulted at roll time
     * and an admin may retune it whenever they like without touching a single copy already in the world.
     *
     * <p>It exists because a crate names its own pool, and not everything that grants a Magic effect is a crate.
     * A consumable applied to a hat won months ago, traded twice and re-granted by a command has no crate to ask,
     * so the ITEM has to be able to answer for itself. A crate may still override this at roll time; this is the
     * fallback, not a constraint.
     *
     * <p>Nothing rolls anything yet and no pool record exists yet. The field is here now because adding it later
     * is a data migration and adding it today is free, which is the same reasoning behind every other field on
     * this record that an unbuilt milestone will read.
     */
    public String defaultEffectPoolId = "";

    /**
     * The retired {@code tier} key, read once from an older {@code cosmetics.json} and then dropped.
     *
     * <p>NOT a field anybody may use. It exists only so Gson has somewhere to put the old {@code "tier"} string
     * when it loads a file written before the rename; {@link #normalise()} folds it into
     * {@link #allowedQualities} and nulls it, and Gson omits nulls, so the key disappears from the file on the
     * next save. Delete this field once no live server can still be holding a pre-rename catalogue.
     */
    private String tier;

    /**
     * Parked without being deleted, the same idea as {@code TaskDef.weight == 0}.
     *
     * <p>A disabled entry keeps every ownership row pointing at it and simply stops being offered or worn, which
     * is how a seasonal set is taken down for a year rather than destroyed.
     */
    public boolean enabled = true;

    /**
     * Whether this cosmetic may ever leave the account that owns it.
     *
     * <p>DEFAULT FALSE, decided by the owner. A newly created entry is BOUND until an admin deliberately ticks
     * the box, which is the same default-deny posture the key allow-lists take, and it means nothing converted
     * or imported becomes tradeable on ship day by accident.
     *
     * <p>FOR THE ADMIN READING THIS IN THE EDITOR: ticking tradeable lets this cosmetic be minted as an item,
     * which can then be traded, auctioned for Zeni, mailed and dropped. A cosmetic bought with real money
     * therefore becomes convertible into the in game economy. Tick it only when that is intended.
     *
     * <p>Every route by which a cosmetic could move asks {@link CosmeticTransferGate}, which reads this field.
     * Nothing else is allowed to consult it directly, so there is one answer to "may this move" in the codebase.
     */
    public boolean tradeable = false;

    /**
     * The ITEM this cosmetic is drawn as on other people's screens, for example {@code dmz_ragnarok:bat_hat}.
     *
     * <h2>An item id, not a model path</h2>
     * This said "resource path of the baked model" and gave {@code dmz_ragnarok:item/cosmetic/bat_hat} as its
     * example, and that cannot be drawn by anything. A model is only baked if something registered it with
     * {@code ModelEvent.RegisterAdditional}, and the catalogue that would name it arrives at LOGIN, long after
     * baking; nothing could ever have registered an id an admin had not typed yet. An ITEM id is bakeable by
     * definition, is what the suite draws in every other slot, and is what a render layer would attach to a bone
     * anyway. The convention was corrected on 2026-09-21, before any definition existed to migrate.
     *
     * <p>A value still written the old way is read leniently: the reader tries the whole string as an item id
     * and then its last path segment in the same namespace, so {@code x:item/cosmetic/bat_hat} finds
     * {@code x:bat_hat} if that item exists.
     *
     * <p>Free text, because an item id is not knowable from a fixed list on the client and an admin may point at
     * any mod's item. An id that resolves to nothing is not an error: the wardrobe draws the cosmetic's NAME
     * instead, which is honest about there being no art yet.
     */
    public String modelId = "";

    /** First person / wearer variant. Blank falls back to {@link #modelId}. Never used for a menu icon. */
    public String modelSelfId = "";

    /** Wardrobe preview variant, and the wardrobe tile's icon. Blank falls back to {@link #modelId}. */
    public String modelPreviewId = "";

    /**
     * Which vanilla {@code PlayerModel} part this hangs off, blank for the slot's default.
     *
     * <p>A vanilla part name rather than a DragonMineZ geo bone on purpose: the supported attachment path runs
     * through a vanilla render layer posed from the geo bones, so a vanilla part is what the renderer will
     * actually have in its hand. Recorded now so the M2 renderer does not have to reinterpret old records.
     */
    public String attachBone = "";

    /** Third person translate, x y z. Always length 3 after {@link #normalise()}. */
    public float[] offset = new float[3];

    /** First person translate, x y z. Always length 3 after {@link #normalise()}. */
    public float[] offsetSelf = new float[3];

    /** Rotation in degrees, x y z. Always length 3 after {@link #normalise()}. */
    public float[] rotation = new float[3];

    /** Uniform scale. Clamped away from zero on load: a zero-scale cosmetic is invisible for no visible reason. */
    public float scale = 1.0F;

    /** Suppress the vanilla helmet while this is worn. Only meaningful on {@link CosmeticSlot#HEAD}. */
    public boolean hideHelmet = false;

    /**
     * Suppress DragonMineZ's hair while this is worn, for a HEAD piece that fully encloses the head (a pumpkin
     * head, a full mask). Only meaningful on {@link CosmeticSlot#HEAD}; inert on any other slot.
     *
     * <p>DEFAULT FALSE. The seeded Halloween heads that enclose the head are stamped true by a one-time
     * migration in {@link CosmeticCatalog}; every hat that merely sits on top leaves it false so the hair still
     * shows under the brim. A per-player client switch ({@code CosmeticRenderOptions.hideHairUnderHelmets})
     * decides whether a client honours it at all, so a player who wants their hair back can keep it.
     *
     * <p>Additive and keyed by NAME on every persisted and wire form, so an older record reads it as false and
     * needs no migration beyond the seed stamp, exactly as the class note promises for a new field.
     */
    public boolean hidesHair = false;

    /**
     * Counters attached to this cosmetic. The MENU of counters a Super copy can offer.
     *
     * <p>The ADMIN defines which trackers exist; the WEARER picks which one is displayed, stored per player in
     * {@link CosmeticWardrobeData}; the COUNT itself lives per copy, on the ownership row. Retained whatever
     * {@link #allowedQualities} says, so narrowing eligibility and widening it again does not silently destroy
     * the list.
     */
    public final List<CosmeticTracker> trackers = new ArrayList<>();

    /** Free-text rarity label, for the wardrobe colour and, later, a crate reveal. Never a permission. */
    public String rarity = "";

    /**
     * Free-text EVENT (collection) label, for example {@code "Halloween"}. Blank means the cosmetic belongs to no
     * event.
     *
     * <p>A grouping axis, never a permission and never a gate: the editor and the player-facing lists group and
     * filter by it, and that is all it does. It is a DIFFERENT field from {@link #rarity}, which colours a tile;
     * an early build stuffed the collection name into {@code rarity} as a stand-in and this field replaces that,
     * with a one-time migration in {@link CosmeticCatalog} folding the old {@code "Halloween 2026"} rarity across.
     *
     * <p>Added on 2026-09-22. Additive and keyed by NAME everywhere, so an older record reads it as blank and no
     * migration is needed beyond the rarity fold, exactly as the class note promises for a new field.
     */
    public String event = "";

    /**
     * The triggered animation this cosmetic plays, or null. Meaningful ONLY when {@link #slot} is
     * {@link CosmeticSlot#triggered()}; on a worn slot it is inert and normally left null. Added as a whole
     * sub-record rather than as loose fields so that, like every other field here, an older record reads it as
     * absent and needs no migration. Rides the catalogue packet and the state sync for free.
     */
    public CosmeticAnimation animation;

    /**
     * The movement of a mount, or null. Meaningful ONLY when {@link #slot} is {@link CosmeticSlot#MOUNT}; on any
     * other slot it is inert and normally left null. Carries whether the mount flies or is ground based and how fast
     * it drives, the two things the owner wanted an operator to set. Added as a whole sub-record rather than as loose
     * fields so that, like every other field here, an older record reads it as absent and needs no migration: when it
     * is null the mount entity falls back to {@code CosmeticMountType}'s seeded table. Rides the catalogue packet and
     * the state sync for free.
     */
    public CosmeticMount mount;

    /**
     * How an {@link CosmeticSlot#ACCESSORY} cosmetic is drawn: held in the hand ({@link CosmeticAccessoryStyle#HAND},
     * shown only while a ki weapon is drawn), floating on a string ({@link CosmeticAccessoryStyle#FLOAT}) or carried
     * in the hand at all times ({@link CosmeticAccessoryStyle#CARRIED}). Meaningful ONLY on the accessory slot; on
     * any other slot it is inert.
     *
     * <p>Added on 2026-09-22. Additive and keyed by NAME on every persisted and wire form, so an older record reads
     * it as {@link CosmeticAccessoryStyle#CARRIED} (the prior always-in-hand behaviour) and needs no migration
     * beyond the seed stamp, exactly as {@link #hidesHair} and {@link #mount} did. Never null after
     * {@link #normalise()}.
     */
    public CosmeticAccessoryStyle accessoryStyle = CosmeticAccessoryStyle.CARRIED;

    /**
     * Console commands run the first time this is unlocked for a player, with {@code %player%} substituted.
     *
     * <p>The escape hatch that keeps this system from needing to know about every other one, copied verbatim from
     * {@code TaskDef.rewardCommands}: a cosmetic can hand out a title, a rank or a key by running the command
     * that already does it, rather than this class growing a field per suite feature.
     */
    public final List<String> grantCommands = new ArrayList<>();

    /**
     * The lowest Patreon tier (a {@code PatreonTiers} id such as {@code "elite_warrior"}) whose ACTIVE patrons are
     * entitled to this cosmetic, or null when this is not a Patreon cosmetic. A supporter at that tier or above holds
     * it for as long as their effective tier (grace window included) says so, and loses it when the pledge lapses;
     * {@code PatreonWardrobe.reconcile} turns that into the bound {@code patreon} ledger row. An id that is not on
     * the fixed ladder unlocks nothing.
     *
     * <p>Added on 2026-09-28 (S17q). Additive and keyed by NAME: NULL rather than blank when unset, so Gson leaves it
     * out of {@code cosmetics.json} and {@link #toNbt()} leaves it out of the shard tag, and a catalogue with no
     * Patreon cosmetic keeps the exact bytes and content hash it had before. An older record reads it as absent.
     */
    public String patreonTier;

    public CosmeticDef()
    {
    }

    public CosmeticDef(String id, CosmeticSlot slot)
    {
        this.id = id == null ? "" : id;
        this.slot = slot == null ? CosmeticSlot.HEAD : slot;
    }

    /** A deep copy, so an editor screen can be cancelled without having mutated the live catalogue. */
    public CosmeticDef copy()
    {
        CosmeticDef c = new CosmeticDef(id, slot);
        c.displayName = displayName;
        c.description = description;
        c.allowedQualities = CosmeticQuality.canonical(allowedQualities);
        c.defaultEffectPoolId = defaultEffectPoolId;
        c.enabled = enabled;
        c.tradeable = tradeable;
        c.modelId = modelId;
        c.modelSelfId = modelSelfId;
        c.modelPreviewId = modelPreviewId;
        c.attachBone = attachBone;
        c.offset = offset == null ? new float[3] : offset.clone();
        c.offsetSelf = offsetSelf == null ? new float[3] : offsetSelf.clone();
        c.rotation = rotation == null ? new float[3] : rotation.clone();
        c.scale = scale;
        c.hideHelmet = hideHelmet;
        c.hidesHair = hidesHair;
        for (CosmeticTracker t : trackers)
            c.trackers.add(t.copy());
        c.rarity = rarity;
        c.event = event;
        c.animation = animation == null ? null : animation.copy();
        c.mount = mount == null ? null : mount.copy();
        c.accessoryStyle = accessoryStyle;
        c.grantCommands.addAll(grantCommands);
        c.patreonTier = patreonTier;
        return c;
    }

    /** Fill in anything a hand-edited or older file left null, so the rest of the system never guards for it. */
    public CosmeticDef normalise()
    {
        id = sanitizeId(id);
        if (displayName == null)
            displayName = "";
        if (description == null)
            description = "";
        if (slot == null)
            slot = CosmeticSlot.HEAD;
        migrateLegacyTier();
        allowedQualities = CosmeticQuality.canonical(allowedQualities);
        if (defaultEffectPoolId == null)
            defaultEffectPoolId = "";
        defaultEffectPoolId = defaultEffectPoolId.trim();
        if (modelId == null)
            modelId = "";
        if (modelSelfId == null)
            modelSelfId = "";
        if (modelPreviewId == null)
            modelPreviewId = "";
        if (attachBone == null)
            attachBone = "";
        if (rarity == null)
            rarity = "";
        if (event == null)
            event = "";
        event = event.trim();
        if (animation != null)
            animation.normalise();
        if (mount != null)
            mount.normalise();
        if (accessoryStyle == null)
            accessoryStyle = CosmeticAccessoryStyle.CARRIED;
        offset = three(offset);
        offsetSelf = three(offsetSelf);
        rotation = three(rotation);
        // Zero or negative scale draws nothing, with no error anywhere to explain why, so it is corrected rather
        // than accepted. The upper bound is generous; it exists only to stop a typo eating the screen.
        if (!(scale > 0.0F) || Float.isNaN(scale) || Float.isInfinite(scale))
            scale = 1.0F;
        scale = Math.min(scale, 16.0F);
        // Trackers are deduplicated by id and dropped when blank: two with the same id would make the wearer's
        // chosen-tracker field ambiguous, and the wearer would never be told which one they got.
        List<CosmeticTracker> kept = new ArrayList<>(trackers.size());
        List<String> seen = new ArrayList<>(trackers.size());
        for (CosmeticTracker t : trackers)
        {
            if (t == null)
                continue;
            t.normalise();
            if (t.id.isBlank() || seen.contains(t.id))
                continue;
            seen.add(t.id);
            kept.add(t);
        }
        trackers.clear();
        trackers.addAll(kept);
        grantCommands.removeIf(c -> c == null || c.isBlank());
        patreonTier = normaliseTier(patreonTier);
        return this;
    }

    /**
     * Fold a pre-rename {@code tier} key into {@link #allowedQualities}, once, then forget it.
     *
     * <p>Keyed on {@link #allowedQualities} being ABSENT rather than on a version number, which is what makes it
     * idempotent: a record that already carries an eligibility set is never touched, so running it on every load
     * and on every decode costs nothing and can never widen a set an admin deliberately narrowed.
     *
     * <p>An unrecognised key is LOGGED rather than defaulted. The retired {@code CosmeticTier.byKey} never threw,
     * so an unknown value silently became the lowest tier, and during a rename a silent default is silent data
     * loss: an admin would find their Magic-eligible hat had quietly become Normal-only with nothing anywhere
     * saying so.
     */
    private void migrateLegacyTier()
    {
        boolean absent = allowedQualities == null || allowedQualities.isEmpty();
        if (absent && tier != null && !tier.isBlank())
        {
            Set<CosmeticQuality> migrated = CosmeticQuality.fromLegacyTierKey(tier);
            if (migrated == null)
                LoggingHandler.sulog.warn(
                        "[Cosmetics] '{}' carries an unrecognised legacy tier '{}'. It is now Normal only; widen "
                                + "it in the editor if it should roll Super or Magic.",
                        id, tier);
            else
                allowedQualities = migrated;
        }
        // Cleared whether or not it was understood, so the key leaves the file on the next save and a second pass
        // cannot re-run the migration over an eligibility set an admin has since edited.
        tier = null;
    }

    /** Whether this is a Patreon cosmetic, one an active patron at {@link #patreonTier} or above is entitled to. */
    public boolean patreonGated()
    {
        return patreonTier != null;
    }

    /** A Patreon tier id as stored: trimmed and lower-cased, with a blank folded to null (not a Patreon cosmetic). */
    public static String normaliseTier(String raw)
    {
        if (raw == null)
            return null;
        String t = raw.trim().toLowerCase(Locale.ROOT);
        return t.isEmpty() ? null : t;
    }

    private static float[] three(float[] in)
    {
        float[] out = new float[3];
        if (in != null)
            for (int i = 0; i < 3 && i < in.length; i++)
                out[i] = Float.isNaN(in[i]) || Float.isInfinite(in[i]) ? 0.0F : in[i];
        return out;
    }

    /**
     * Normalise a typed cosmetic id: lowercase, letters, digits and underscore only.
     *
     * <p>The id is a key in the catalogue file, in every ownership row and in every equipped map, so a stray
     * space or capital would be a lookup that quietly fails later rather than an error the admin sees now.
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

    /** Whether a copy of this cosmetic may be minted at this quality. The question a crate asks before it rolls. */
    public boolean allows(CosmeticQuality quality)
    {
        return quality != null && allowedQualities != null && allowedQualities.contains(quality);
    }

    /** {@code "normal,super"}, in canonical order, for an editor row or a chat line. */
    public String qualitySummary()
    {
        return CosmeticQuality.join(allowedQualities);
    }

    /** The tracker with this id, or null. */
    public CosmeticTracker tracker(String trackerId)
    {
        if (trackerId == null || trackerId.isBlank())
            return null;
        for (CosmeticTracker t : trackers)
            if (t.id.equals(trackerId))
                return t;
        return null;
    }

    /**
     * Whether a player may have this equipped right now.
     *
     * <p>Asks the definition only. Whether they OWN it is a separate question, answered by
     * {@link CosmeticLedgerData}, and whether the module is switched on is a third, answered by
     * {@link WardrobeManager}. Keeping the three apart is what stops a disabled cosmetic silently deleting
     * somebody's ownership row.
     */
    public boolean wearable()
    {
        // A triggered animation stays wearable even when its home slot is one of the retired single-direction ones
        // (join, leave, tp_depart, tp_arrive): it is equippable into a usable paired triggered slot regardless. A
        // worn cosmetic still needs its own slot usable.
        return enabled && slot != null && (slot.usable() || slot.triggered());
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(id);
        buf.writeUtf(displayName);
        buf.writeUtf(description);
        buf.writeUtf(slot.key);
        // A COUNTED LIST of key strings, never a fixed-width block: a client and a server disagreeing about how
        // many qualities exist degrade rather than desynchronise, which is the same rule CosmeticSlot states.
        List<String> qualityKeys = CosmeticQuality.keysOf(allowedQualities);
        buf.writeVarInt(qualityKeys.size());
        for (String k : qualityKeys)
            buf.writeUtf(k);
        buf.writeUtf(defaultEffectPoolId == null ? "" : defaultEffectPoolId);
        buf.writeBoolean(enabled);
        buf.writeBoolean(tradeable);
        buf.writeUtf(modelId);
        buf.writeUtf(modelSelfId);
        buf.writeUtf(modelPreviewId);
        buf.writeUtf(attachBone);
        for (int i = 0; i < 3; i++)
            buf.writeFloat(offset[i]);
        for (int i = 0; i < 3; i++)
            buf.writeFloat(offsetSelf[i]);
        for (int i = 0; i < 3; i++)
            buf.writeFloat(rotation[i]);
        buf.writeFloat(scale);
        buf.writeBoolean(hideHelmet);
        buf.writeBoolean(hidesHair);
        buf.writeVarInt(trackers.size());
        for (CosmeticTracker t : trackers)
            t.encode(buf);
        buf.writeUtf(rarity);
        buf.writeUtf(event);
        // The animation rides the wire so a client can draw it: it is presentation, not server business like the
        // grant commands below. A counted-presence boolean keeps an older/newer peer decoding cleanly.
        if (animation != null)
        {
            buf.writeBoolean(true);
            animation.encode(buf);
        }
        else
        {
            buf.writeBoolean(false);
        }
        // The mount movement rides the wire so a client can drive and draw a summoned mount from its synced def. A
        // counted-presence boolean keeps an older/newer peer decoding cleanly, exactly as the animation above.
        if (mount != null)
        {
            buf.writeBoolean(true);
            mount.encode(buf);
        }
        else
        {
            buf.writeBoolean(false);
        }
        // The accessory style rides the wire so a client can pick the hand/float/carried render path off the synced
        // catalogue. Appended LAST, after the mount block the mount work appends, and written as its stable key
        // string so an older/newer peer decodes cleanly, exactly as the slot key does above.
        buf.writeUtf((accessoryStyle == null ? CosmeticAccessoryStyle.CARRIED : accessoryStyle).key);
        // The Patreon tier gate, appended after the accessory style so nothing before it moves. Blank means not a
        // Patreon cosmetic. It rides the wire so a client can label a cosmetic as a supporter reward.
        buf.writeUtf(patreonTier == null ? "" : patreonTier);
        // grantCommands are deliberately NOT on the wire: they are console commands, which are server business,
        // and a client has no use for them beyond learning what the server will run on its behalf.
    }

    public static CosmeticDef decode(FriendlyByteBuf buf)
    {
        CosmeticDef d = new CosmeticDef();
        d.id = buf.readUtf();
        d.displayName = buf.readUtf();
        d.description = buf.readUtf();
        d.slot = CosmeticSlot.byKey(buf.readUtf());
        int qn = buf.readVarInt();
        List<String> qualityKeys = new ArrayList<>(Math.max(0, qn));
        for (int i = 0; i < qn; i++)
            qualityKeys.add(buf.readUtf());
        d.allowedQualities = CosmeticQuality.setFromKeys(qualityKeys);
        d.defaultEffectPoolId = buf.readUtf();
        d.enabled = buf.readBoolean();
        d.tradeable = buf.readBoolean();
        d.modelId = buf.readUtf();
        d.modelSelfId = buf.readUtf();
        d.modelPreviewId = buf.readUtf();
        d.attachBone = buf.readUtf();
        for (int i = 0; i < 3; i++)
            d.offset[i] = buf.readFloat();
        for (int i = 0; i < 3; i++)
            d.offsetSelf[i] = buf.readFloat();
        for (int i = 0; i < 3; i++)
            d.rotation[i] = buf.readFloat();
        d.scale = buf.readFloat();
        d.hideHelmet = buf.readBoolean();
        d.hidesHair = buf.readBoolean();
        int n = buf.readVarInt();
        for (int i = 0; i < n; i++)
            d.trackers.add(CosmeticTracker.decode(buf));
        d.rarity = buf.readUtf();
        d.event = buf.readUtf();
        if (buf.readBoolean())
            d.animation = CosmeticAnimation.decode(buf);
        if (buf.readBoolean())
            d.mount = CosmeticMount.decode(buf);
        d.accessoryStyle = CosmeticAccessoryStyle.byKey(buf.readUtf());
        d.patreonTier = buf.readUtf();
        return d.normalise();
    }

    /**
     * Serialise to NBT for the cross-server state sync. Deterministic field order, and lists in their stored
     * order, so the sync's content hash only moves on a real edit and two converged servers produce identical
     * bytes rather than republishing each other forever.
     */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", id == null ? "" : id);
        t.putString("displayName", displayName == null ? "" : displayName);
        t.putString("description", description == null ? "" : description);
        t.putString("slot", (slot == null ? CosmeticSlot.HEAD : slot).key);
        // Canonical (declaration) order, so two servers holding the same eligibility write the same bytes
        // whatever order an admin ticked the boxes in and the sync's content hash only moves on a real edit.
        ListTag ql = new ListTag();
        for (String k : CosmeticQuality.keysOf(allowedQualities))
            ql.add(StringTag.valueOf(k));
        t.put("qualities", ql);
        t.putString("defaultEffectPoolId", defaultEffectPoolId == null ? "" : defaultEffectPoolId);
        t.putBoolean("enabled", enabled);
        t.putBoolean("tradeable", tradeable);
        t.putString("modelId", modelId == null ? "" : modelId);
        t.putString("modelSelfId", modelSelfId == null ? "" : modelSelfId);
        t.putString("modelPreviewId", modelPreviewId == null ? "" : modelPreviewId);
        t.putString("attachBone", attachBone == null ? "" : attachBone);
        t.putIntArray("offset", bits(offset));
        t.putIntArray("offsetSelf", bits(offsetSelf));
        t.putIntArray("rotation", bits(rotation));
        t.putFloat("scale", scale);
        t.putBoolean("hideHelmet", hideHelmet);
        t.putBoolean("hidesHair", hidesHair);
        ListTag tl = new ListTag();
        for (CosmeticTracker tr : trackers)
            tl.add(tr.toNbt());
        t.put("trackers", tl);
        t.putString("rarity", rarity == null ? "" : rarity);
        t.putString("event", event == null ? "" : event);
        if (animation != null)
            t.put("animation", animation.toNbt());
        if (mount != null)
            t.put("mount", mount.toNbt());
        t.putString("accessoryStyle", (accessoryStyle == null ? CosmeticAccessoryStyle.CARRIED : accessoryStyle).key);
        ListTag cmds = new ListTag();
        for (String c : grantCommands)
            cmds.add(StringTag.valueOf(c == null ? "" : c));
        t.put("grantCommands", cmds);
        // Only when set, so a catalogue with no Patreon cosmetic writes the same bytes (and the shard sync the same
        // content hash) as before the field existed.
        if (patreonTier != null && !patreonTier.isBlank())
            t.putString("patreonTier", patreonTier);
        return t;
    }

    public static CosmeticDef fromNbt(CompoundTag t)
    {
        CosmeticDef d = new CosmeticDef();
        d.id = t.getString("id");
        d.displayName = t.getString("displayName");
        d.description = t.getString("description");
        d.slot = CosmeticSlot.byKey(t.getString("slot"));
        if (t.contains("qualities", Tag.TAG_LIST))
        {
            ListTag ql = t.getList("qualities", Tag.TAG_STRING);
            List<String> qualityKeys = new ArrayList<>(ql.size());
            for (int i = 0; i < ql.size(); i++)
                qualityKeys.add(ql.getString(i));
            d.allowedQualities = CosmeticQuality.setFromKeys(qualityKeys);
        }
        else
        {
            // No eligibility set means a tag written before the rename. Left EMPTY here on purpose so
            // normalise() runs the one migration, rather than defaulting twice in two places.
            d.allowedQualities = new java.util.LinkedHashSet<>();
            d.tier = t.getString("tier");
        }
        d.defaultEffectPoolId = t.getString("defaultEffectPoolId");
        d.enabled = !t.contains("enabled") || t.getBoolean("enabled");
        d.tradeable = t.getBoolean("tradeable");
        d.modelId = t.getString("modelId");
        d.modelSelfId = t.getString("modelSelfId");
        d.modelPreviewId = t.getString("modelPreviewId");
        d.attachBone = t.getString("attachBone");
        d.offset = floats(t.getIntArray("offset"));
        d.offsetSelf = floats(t.getIntArray("offsetSelf"));
        d.rotation = floats(t.getIntArray("rotation"));
        d.scale = t.contains("scale") ? t.getFloat("scale") : 1.0F;
        d.hideHelmet = t.getBoolean("hideHelmet");
        d.hidesHair = t.getBoolean("hidesHair");
        ListTag tl = t.getList("trackers", Tag.TAG_COMPOUND);
        for (int i = 0; i < tl.size(); i++)
            d.trackers.add(CosmeticTracker.fromNbt(tl.getCompound(i)));
        d.rarity = t.getString("rarity");
        d.event = t.getString("event");
        if (t.contains("animation", Tag.TAG_COMPOUND))
            d.animation = CosmeticAnimation.fromNbt(t.getCompound("animation"));
        if (t.contains("mount", Tag.TAG_COMPOUND))
            d.mount = CosmeticMount.fromNbt(t.getCompound("mount"));
        // Absent on a tag written before this field, which reads as CARRIED (the prior always-in-hand behaviour)
        // through byKey's empty-string fallback, so no migration is needed beyond the seed stamp.
        d.accessoryStyle = CosmeticAccessoryStyle.byKey(t.getString("accessoryStyle"));
        ListTag cmds = t.getList("grantCommands", Tag.TAG_STRING);
        for (int i = 0; i < cmds.size(); i++)
            d.grantCommands.add(cmds.getString(i));
        // Absent on a tag written before the field (or for a cosmetic that is not a Patreon one): reads as null.
        d.patreonTier = t.getString("patreonTier");
        return d.normalise();
    }

    // Floats travel as raw bits in an int array rather than as a list of float tags: an int array is one tag
    // whose equals() is an array compare, which is what keeps the state sync's CompoundTag comparison cheap and
    // exact. Round-tripping the bits is lossless, so nothing drifts on repeated serialisation.
    private static int[] bits(float[] in)
    {
        float[] v = in == null ? new float[3] : in;
        int[] out = new int[3];
        for (int i = 0; i < 3 && i < v.length; i++)
            out[i] = Float.floatToIntBits(v[i]);
        return out;
    }

    private static float[] floats(int[] in)
    {
        float[] out = new float[3];
        if (in != null)
            for (int i = 0; i < 3 && i < in.length; i++)
                out[i] = Float.intBitsToFloat(in[i]);
        return out;
    }
}
