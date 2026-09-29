package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;

/**
 * What is in one wardrobe slot: not just WHICH cosmetic, but which COPY of it.
 *
 * <h2>Why a catalogue id stopped being enough</h2>
 * {@link PlayerWardrobe#equipped} used to map a slot key to a bare catalogue id. Once quality lives on the
 * instance, that is ambiguous the moment somebody owns two copies of the same hat at different qualities: the
 * server would know they are wearing "bat_hat" and have no way to say which bat_hat, so a Magic copy and a Normal
 * copy would be indistinguishable to everything downstream.
 *
 * <p>It is also what makes the client cheap. Outfits are broadcast to everybody
 * ({@code PacketWardrobeSync}), so once quality and the effect id ride in this record, a client already knows,
 * for every player it can see, whether they are wearing something Magic and which effect it is. Nothing has to be
 * asked for, and nothing has to be sent per frame or per step.
 *
 * <h2>{@link #instanceId} is a POINTER, never authority</h2>
 * The ownership row is the authority for quality, for the effect and for the count. This record is a
 * denormalised copy of the parts a renderer and a label need, refreshed by the server when it changes. A client
 * that edited it would be lying to its own screen; the server re-reads the ledger on every equip.
 *
 * <h2>Reading an older record</h2>
 * Both readers tolerate the old shape, a bare string, and read it as {@code {id, no instance, NORMAL, "", "", 0}}.
 * That costs one {@code instanceof} and means an outfit saved before this milestone is a plain hat rather than a
 * failed load. The instance is then null until the wearer next equips, which is the honest answer: nothing on
 * disk ever recorded which copy it was.
 *
 * <p>Plain-data object (public fields, explicit save/load/encode/decode) matching the shape the rest of the suite
 * uses, rather than a Java record, so the codecs read the same way as {@link CosmeticOwnership}'s beside it.
 */
public final class EquippedCosmetic
{
    /** The {@link CosmeticDef#id} being worn. Blank means the slot is empty and this record is not stored. */
    public String catalogId = "";

    /**
     * Which {@link CosmeticOwnership#instanceId} is worn, or null when it is not known.
     *
     * <p>Null is a real and reachable state: an outfit saved before this milestone has no instance recorded, and
     * so does one whose row was revoked out from under it. Everything reading this must treat null as "fall back
     * to the definition", never as an error.
     */
    public UUID instanceId;

    /** Denormalised from the ownership row. See the class note on why this is not authority. */
    public CosmeticQuality quality = CosmeticQuality.NORMAL;

    /** The rolled effect id, blank unless {@link #quality} is MAGIC. Nothing rolls one yet. */
    public String effectId = "";

    /** Which of the cosmetic's counters the wearer chose to show. Blank shows no number. */
    public String displayedTrackerId = "";

    /**
     * The value of that counter as of the last refresh. DISPLAY ONLY.
     *
     * <p>Denormalised on purpose: the authoritative count is on the ownership row, and copying it here is what
     * lets every client draw the number without a lookup and without a packet per kill. The label therefore lags
     * the truth, which is a deliberate trade and is stated in {@link CosmeticCounter}.
     */
    public long displayedCount;

    public EquippedCosmetic()
    {
    }

    public EquippedCosmetic(String catalogId, UUID instanceId, CosmeticQuality quality, String effectId)
    {
        this.catalogId = catalogId == null ? "" : catalogId;
        this.instanceId = instanceId;
        this.quality = quality == null ? CosmeticQuality.NORMAL : quality;
        this.effectId = effectId == null ? "" : effectId;
    }

    public EquippedCosmetic copy()
    {
        EquippedCosmetic c = new EquippedCosmetic(catalogId, instanceId, quality, effectId);
        c.displayedTrackerId = displayedTrackerId;
        c.displayedCount = displayedCount;
        return c;
    }

    /** A record naming no cosmetic is an empty slot, and an empty slot is stored as an absence, never a record. */
    public boolean valid()
    {
        return catalogId != null && !catalogId.isBlank();
    }

    /** Whether two records say the same thing, so a setter can tell a real change from a no-op. */
    public boolean sameAs(EquippedCosmetic other)
    {
        return other != null
                && java.util.Objects.equals(catalogId, other.catalogId)
                && java.util.Objects.equals(instanceId, other.instanceId)
                && quality == other.quality
                && java.util.Objects.equals(effectId, other.effectId)
                && java.util.Objects.equals(displayedTrackerId, other.displayedTrackerId)
                && displayedCount == other.displayedCount;
    }

    /** Deterministic field order, so the shard sync's content hash only moves on a real edit. */
    public CompoundTag toNbt()
    {
        CompoundTag t = new CompoundTag();
        t.putString("id", catalogId == null ? "" : catalogId);
        if (instanceId != null)
            t.putUUID("instance", instanceId);
        t.putString("quality", (quality == null ? CosmeticQuality.NORMAL : quality).key);
        // Blank optional strings are OMITTED rather than written empty, so an ordinary Normal hat produces the
        // smallest possible tag and two converged servers cannot differ by a field one of them wrote as "".
        if (effectId != null && !effectId.isBlank())
            t.putString("effect", effectId);
        if (displayedTrackerId != null && !displayedTrackerId.isBlank())
            t.putString("tracker", displayedTrackerId);
        if (displayedCount != 0L)
            t.putLong("count", displayedCount);
        return t;
    }

    /**
     * Read one slot's value, tolerating the pre-milestone bare string. See the class note.
     *
     * @return null when the tag names no cosmetic, which the caller stores as an empty slot
     */
    public static EquippedCosmetic fromTag(Tag tag)
    {
        if (tag == null)
            return null;
        if (tag instanceof StringTag)
        {
            String id = tag.getAsString();
            return id == null || id.isBlank() ? null
                    : new EquippedCosmetic(id, null, CosmeticQuality.NORMAL, "");
        }
        if (!(tag instanceof CompoundTag t))
            return null;
        EquippedCosmetic e = new EquippedCosmetic();
        e.catalogId = t.getString("id");
        e.instanceId = t.hasUUID("instance") ? t.getUUID("instance") : null;
        e.quality = CosmeticQuality.byKey(t.getString("quality"));
        e.effectId = t.getString("effect");
        e.displayedTrackerId = t.getString("tracker");
        e.displayedCount = Math.max(0L, t.getLong("count"));
        return e.valid() ? e : null;
    }

    public void encode(FriendlyByteBuf buf)
    {
        buf.writeUtf(catalogId == null ? "" : catalogId);
        buf.writeBoolean(instanceId != null);
        if (instanceId != null)
            buf.writeUUID(instanceId);
        buf.writeUtf((quality == null ? CosmeticQuality.NORMAL : quality).key);
        buf.writeUtf(effectId == null ? "" : effectId);
        buf.writeUtf(displayedTrackerId == null ? "" : displayedTrackerId);
        buf.writeVarLong(Math.max(0L, displayedCount));
    }

    public static EquippedCosmetic decode(FriendlyByteBuf buf)
    {
        EquippedCosmetic e = new EquippedCosmetic();
        e.catalogId = buf.readUtf();
        e.instanceId = buf.readBoolean() ? buf.readUUID() : null;
        e.quality = CosmeticQuality.byKey(buf.readUtf());
        e.effectId = buf.readUtf();
        e.displayedTrackerId = buf.readUtf();
        e.displayedCount = buf.readVarLong();
        return e;
    }
}
