package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The bundled Halloween 2026 cosmetics, as SEED catalogue entries.
 *
 * <p>SEED CONTENT, exactly like {@link CosmeticEffectDefaults}: {@link CosmeticCatalog#load()} puts one
 * {@link CosmeticDef} per id here into a catalogue that has never held that id, once, and from that moment
 * each is an ordinary admin-owned record, editable and deletable in game and synced across shards. Nothing
 * here is consulted again afterwards, and changing a line in this file does NOT change a live server's copy.
 *
 * <h2>Guarded PER ID on the stamps, so a deleted seed stays deleted</h2>
 * The seed loop in {@link CosmeticCatalog#seedDefaults()} skips any id that already carries a stamp,
 * whether that stamp sits on a live record or on a tombstone. So an admin who deletes 'Bat Hat' leaves a
 * tombstone behind and it does not grow back on the next restart, and an admin's edits to a seeded entry are
 * never overwritten. Seeded at stamp ZERO so two shards that both seed converge silently.
 *
 * <h2>The fields, and why</h2>
 * Every entry is BOUND (tradeable false, the owner's rule) and grouped under the {@link CosmeticDef#event} value
 * {@link #EVENT} ("Halloween"), with {@code rarity} left blank. {@code modelId} and
 * {@code modelPreviewId} both name the item registered in {@link CosmeticContentItems}. Wearables are eligible
 * for Normal, Super and Magic and default a Magic roll to the shipped Halloween pool {@code pool_hollow};
 * pets and mounts are Normal only. Mounts sit on {@link CosmeticSlot#MOUNT}, which is declared disabled until
 * the summon entity exists (milestone M6), so a mount entry is parked rather than offered, the same way a
 * body cosmetic would be.
 */
public final class CosmeticHalloweenDefaults
{
    /**
     * The event name every entry here is grouped under, written into {@link CosmeticDef#event}.
     *
     * <p>This is the real home for the collection, replacing the earlier stand-in that stuffed
     * {@link #LEGACY_RARITY} into {@link CosmeticDef#rarity}. A live server that seeded the old way is folded across
     * once by {@link CosmeticCatalog}'s migration.
     */
    public static final String EVENT = "Halloween";

    /**
     * The exact {@link CosmeticDef#rarity} value an earlier build wrote as a stand-in for the collection. Kept only
     * so {@link CosmeticCatalog}'s one-time migration can recognise and clear it. Not written by anything now.
     */
    public static final String LEGACY_RARITY = "Halloween 2026";

    /** The shipped Halloween Magic pool a wearable defaults to. See {@link CosmeticEffectDefaults}. */
    public static final String MAGIC_POOL = "pool_hollow";

    private CosmeticHalloweenDefaults()
    {
    }

    /**
     * A seeded placement default for a HEAD piece: a uniform scale correction, and whether the piece encloses the
     * head enough to hide the DragonMineZ hair.
     *
     * <p>ONLY HEAD pieces carry a scale correction here. BACK pieces are sized by the render layer, which measures
     * each model and fits it to torso size, so their seeded scale stays 1; ACCESSORY pieces draw in the hand
     * context at their authored size. The heads are the slot the layer trusts the authored transform for, so the
     * heads are the slot a data correction belongs to.
     */
    public static final class HeadDefault
    {
        /** Uniform {@link CosmeticDef#scale}. Below 1 shrinks a hat authored oversized for a player head. */
        public final float scale;

        /** {@link CosmeticDef#hidesHair}: true for a piece that fully encloses the head (the pumpkin head). */
        public final boolean hidesHair;

        /**
         * Seeded {@link CosmeticDef#rotation}, in degrees [x,y,z], or null for none. This is the FACING default:
         * the render layer applies it as an admin fine-tune in bone space, so a piece the source pack authored
         * back to front or sideways can be turned the right way as DATA, not as a special case in the render
         * code. Seeded and migrated exactly like {@link #scale}: a non-null rotation moves the record off the
         * identity fingerprint, which is what keeps the one-time migration idempotent and stops it clobbering an
         * admin's own turn (any hand edit already sits off identity, so the migration skips it).
         */
        public final float[] rotation;

        HeadDefault(float scale, boolean hidesHair)
        {
            this(scale, hidesHair, null);
        }

        HeadDefault(float scale, boolean hidesHair, float[] rotation)
        {
            this.scale = scale;
            this.hidesHair = hidesHair;
            this.rotation = rotation;
        }
    }

    /**
     * The per-id HEAD placement corrections, measured from each model's element bounds after its {@code display.head}
     * transform and the head recipe, so a hat lands about head size rather than the two to three block wide it was
     * authored at. An id NOT listed here keeps scale 1 and shows hair, which is right for the hats that were already
     * a sensible size and for every back and accessory piece.
     *
     * <p>ONE source for both paths: {@link #simple} stamps these onto a FRESH seed, and
     * {@link CosmeticCatalog}'s one-time migration stamps them onto an EXISTING seed whose placement is still at
     * identity, so a server that seeded before this correction converges to the same values a fresh one gets.
     */
    private static final Map<String, HeadDefault> HEAD_DEFAULTS = buildHeadDefaults();

    private static Map<String, HeadDefault> buildHeadDefaults()
    {
        Map<String, HeadDefault> m = new LinkedHashMap<>();
        // Hats draw at their authored size (scale 1). An earlier build shrank these six (0.5 to 0.8) to bring an
        // armour-stand-scale author down to head size, but the owner reported the result too small and that the
        // hats were fine before that correction, so the seeded scale is back to 1 and the previous shrink is
        // reverted by CosmeticCatalog's one-time migration. The data mechanism stays: an admin can still resize
        // any hat from the editor, and the value survives because the revert only touches a still-shrunk record.
        m.put("hw_bat_hat", new HeadDefault(1.0F, false));
        m.put("hw_phantom_hat", new HeadDefault(1.0F, false));
        m.put("hw_witch_cat_hat", new HeadDefault(1.0F, false));
        m.put("hw_candy_pumpkin_beret", new HeadDefault(1.0F, false));
        // The spooky pumpkin hat is the exception the owner flagged on 1.1.581-test as "kinda big". Its authored
        // model measures the largest of the shipped hats (about 2.12 blocks across, tied with the phantom hat),
        // and the owner is happy with the rest at scale 1, so it alone takes a modest 0.85 to bring it down to
        // about 1.80 blocks, in line with the witch hat (1.56) and the phantom hat (2.14).
        m.put("hw_spooky_pumpkin_hat", new HeadDefault(0.85F, false));
        m.put("hw_witch_hat", new HeadDefault(1.0F, false));
        // The one shipped piece that fully encloses the head: already about head size, so scale stays 1, but it
        // hides the hair by default so the hair does not poke through the pumpkin shell.
        m.put("hw_pumpkin_head", new HeadDefault(1.0F, true));

        // No BACK piece carries a seeded facing yaw any more. The earlier BACK render honoured each pack's
        // armour-stand-head transform, which sat two pieces (the cat cape and the witch cauldron) facing the wrong
        // way, so both were seeded a [0,180,0] turn. The current BACK render runs every piece through one uniform
        // upright recipe that already faces the decorated side outward (see WardrobeCosmeticLayer.drawBack), so that
        // per-item turn is stale and would now spin those two to face inward. A server that ran the earlier build is
        // brought back to this uniform baseline by CosmeticCatalog.migrateHalloweenBackFacingRevert, whose fingerprint
        // is PREVIOUS_BACK_YAW_ROTATIONS below.

        // FACING defaults (the third HeadDefault arg, a [x,y,z] degree rotation) go here, for any slot, when a
        // source model reads back to front or sideways on the body. This table is keyed by id alone, so a BACK or
        // ACCESSORY id is legal even though the class is named for heads; simple() and the migration both key off
        // the id. None of the BACK pieces are seeded yet: the source packs authored these for armour-stand-head and
        // display-entity mounts, so the correct on-body yaw cannot be read from a model's element coordinates alone
        // (nothing in the JSON says which face is the decorated front), and a wrong guess turns a correct piece
        // backwards. The one lead the JSON gives is hw_phantom_wings, whose display.head carries a 45 degree yaw the
        // BACK recipe otherwise discards. The exact per-item BACK values want a launch-test render to confirm before
        // they are committed here; until then an admin can set def.rotation live in the editor, which the layer
        // already applies. Example once confirmed: m.put("hw_phantom_wings", new HeadDefault(1.0F, false, new float[]{0, 45, 0}));

        // HAND accessory ORIENTATION into ki-weapon space. WardrobeCosmeticLayer.renderHandSkin anchors a HAND prop
        // on DMZ's own ki-weapon box (measured from kiweapon_blade.geo.json) and centres and fits it there
        // generically; the only per-item value is which way the prop points out of the fist, and that lives here as
        // DATA (seeded on a fresh catalogue, stamped onto an at-identity record by CosmeticCatalog's migration, and
        // editable live), never as a special case in the render code.
        //
        // The half turn about X is derived, not guessed: an item model is authored +Y up by the format's convention
        // (the gui shows it upright), and the arm-bone frame the skin draws in has +Y up too (GeckoLib applies no
        // model Y flip for a living player, confirmed in GeoEntityRenderer.applyRotations), whereas DMZ's blade geo
        // points DOWN the arm (its cubes run from y +17 at the hand to y -1 at the tip). So flipping the prop 180
        // about X turns its up-pointing blade down the arm the way DMZ's weapon points. The SWORD (hw_pumpkin_sword)
        // is the reference: its blade is the long +Y run of elements up to y=30, so [180,0,0] lands the blade down
        // the arm and the grip in the fist. The other seven HAND props share the +Y-up item-model convention and so
        // take the same baseline; only the sword's box was cross-checked against DMZ's geo, so the rest are a
        // best-effort start that an admin can fine-tune live (a wand or broom may want a small extra yaw or roll).
        float[] handDownTheArm = new float[] { 180.0F, 0.0F, 0.0F };
        m.put("hw_pumpkin_sword", new HeadDefault(1.0F, false, handDownTheArm));
        m.put("hw_phantom_scythe", new HeadDefault(1.0F, false, handDownTheArm));
        m.put("hw_spirit_wand", new HeadDefault(1.0F, false, handDownTheArm));
        m.put("hw_bat_candy_staff", new HeadDefault(1.0F, false, handDownTheArm));
        m.put("hw_pumpkin_candy", new HeadDefault(1.0F, false, handDownTheArm));
        m.put("hw_witch_broom", new HeadDefault(1.0F, false, handDownTheArm));
        m.put("hw_spooky_witch_broom", new HeadDefault(1.0F, false, handDownTheArm));
        m.put("hw_spooky_pumpkin_staff", new HeadDefault(1.0F, false, handDownTheArm));
        return m;
    }

    /**
     * The exact HEAD {@link CosmeticDef#scale} a prior build seeded as a shrink correction (0.5 to 0.8), kept
     * ONLY so {@link CosmeticCatalog}'s one-time revert can recognise a still-shrunk, admin-untouched head and
     * bring it back to 1. It plays the same role for the shrink that {@link #LEGACY_RARITY} plays for the old
     * rarity stand-in: a fingerprint of a value this project wrote, not something written any more.
     *
     * <p>A head whose scale exactly equals its value here (and whose offset and rotation are still zero) was
     * placed by that build and no admin has retuned it. Any OTHER scale is an admin's own choice, so the revert
     * leaves it alone. The exact-float match is the whole discriminator: an admin who wanted, say, 0.75 could in
     * principle collide, but re-typing the precise former default and no other tuning is vanishingly unlikely and
     * the cost is only a hat back at full size, which the admin can re-tune in one edit.
     */
    private static final Map<String, Float> PREVIOUS_HEAD_SHRINK_SCALES = buildPreviousShrinkScales();

    private static Map<String, Float> buildPreviousShrinkScales()
    {
        Map<String, Float> m = new LinkedHashMap<>();
        m.put("hw_bat_hat", 0.75F);
        m.put("hw_phantom_hat", 0.5F);
        m.put("hw_witch_cat_hat", 0.7F);
        m.put("hw_candy_pumpkin_beret", 0.8F);
        m.put("hw_spooky_pumpkin_hat", 0.5F);
        m.put("hw_witch_hat", 0.65F);
        return m;
    }

    /** The previous build's HEAD shrink scales, for {@link CosmeticCatalog}'s one-time revert. */
    public static Map<String, Float> previousHeadShrinkScales()
    {
        return java.util.Collections.unmodifiableMap(PREVIOUS_HEAD_SHRINK_SCALES);
    }

    /**
     * The exact BACK {@link CosmeticDef#rotation} a prior build seeded as a facing turn ({@code [0,180,0]} on the
     * cat cape and the witch cauldron), kept ONLY so {@link CosmeticCatalog}'s one-time revert can recognise a
     * still-turned, admin-untouched back piece and bring it back to a zero rotation. It plays the same role for the
     * back yaw that {@link #PREVIOUS_HEAD_SHRINK_SCALES} plays for the head shrink: a fingerprint of a value this
     * project wrote for the old render, not something written any more.
     *
     * <p>A back piece whose rotation exactly equals its value here (and whose offset is still zero and scale still 1)
     * was placed by that build and no admin has retuned it. Any other rotation is an admin's own choice, so the
     * revert leaves it alone.
     */
    private static final Map<String, float[]> PREVIOUS_BACK_YAW_ROTATIONS = buildPreviousBackYaw();

    private static Map<String, float[]> buildPreviousBackYaw()
    {
        Map<String, float[]> m = new LinkedHashMap<>();
        m.put("hw_witch_cat", new float[] { 0.0F, 180.0F, 0.0F });
        m.put("hw_witch_cauldron", new float[] { 0.0F, 180.0F, 0.0F });
        return m;
    }

    /** The previous build's BACK facing yaws, for {@link CosmeticCatalog}'s one-time revert. */
    public static Map<String, float[]> previousBackYawRotations()
    {
        return java.util.Collections.unmodifiableMap(PREVIOUS_BACK_YAW_ROTATIONS);
    }

    /**
     * The seeded accessory style per id: which of the twelve accessories is a held prop, a floating balloon or a
     * carried oddment. Eight weapon-shaped props are {@link CosmeticAccessoryStyle#HAND} (shown only with a ki weapon
     * drawn), the three balloons are {@link CosmeticAccessoryStyle#FLOAT}, and the candy basket is
     * {@link CosmeticAccessoryStyle#CARRIED} (a trick-or-treat prop carried in the hand at all times, not a weapon
     * skin gated on combat and not a balloon).
     *
     * <p>ONE source for both paths: {@link #simple} stamps these onto a FRESH seed, and
     * {@link CosmeticCatalog}'s one-time migration stamps the non-default ones onto an EXISTING accessory whose style
     * is still at the CARRIED default, so a server seeded before this field converges to the same values a fresh one
     * gets. The basket's CARRIED equals the default, so it needs neither a seed change nor a migration.
     */
    private static final Map<String, CosmeticAccessoryStyle> ACCESSORY_STYLES = buildAccessoryStyles();

    private static Map<String, CosmeticAccessoryStyle> buildAccessoryStyles()
    {
        Map<String, CosmeticAccessoryStyle> m = new LinkedHashMap<>();
        // The eight held weapon props: a skin over the ki weapon, drawn only while it is out.
        m.put("hw_phantom_scythe", CosmeticAccessoryStyle.HAND);
        m.put("hw_pumpkin_sword", CosmeticAccessoryStyle.HAND);
        m.put("hw_spirit_wand", CosmeticAccessoryStyle.HAND);
        m.put("hw_bat_candy_staff", CosmeticAccessoryStyle.HAND);
        m.put("hw_pumpkin_candy", CosmeticAccessoryStyle.HAND);
        m.put("hw_witch_broom", CosmeticAccessoryStyle.HAND);
        m.put("hw_spooky_witch_broom", CosmeticAccessoryStyle.HAND);
        m.put("hw_spooky_pumpkin_staff", CosmeticAccessoryStyle.HAND);
        // The three balloons: float above on a string.
        m.put("hw_candy_pumpkin_balloon", CosmeticAccessoryStyle.FLOAT);
        m.put("hw_spooky_pumpkin_balloon", CosmeticAccessoryStyle.FLOAT);
        m.put("hw_ghost_balloon", CosmeticAccessoryStyle.FLOAT);
        // The basket: carried in the hand always. Equal to the default; listed for clarity and so a fresh seed is
        // explicit about it.
        m.put("hw_candy_pumpkin_basket", CosmeticAccessoryStyle.CARRIED);
        return m;
    }

    /** The seeded accessory style for this id, or null when the id is not a shipped accessory. */
    public static CosmeticAccessoryStyle accessoryStyle(String id)
    {
        return id == null ? null : ACCESSORY_STYLES.get(id);
    }

    /** Every seeded accessory style, for {@link CosmeticCatalog}'s one-time migration. */
    public static Map<String, CosmeticAccessoryStyle> accessoryStyles()
    {
        return java.util.Collections.unmodifiableMap(ACCESSORY_STYLES);
    }

    /** The seeded HEAD placement default for this id, or null when the id has none (keeps scale 1, shows hair). */
    public static HeadDefault headDefault(String id)
    {
        return id == null ? null : HEAD_DEFAULTS.get(id);
    }

    /** Every seeded HEAD placement default, for {@link CosmeticCatalog}'s one-time migration. */
    public static Map<String, HeadDefault> headDefaults()
    {
        return java.util.Collections.unmodifiableMap(HEAD_DEFAULTS);
    }

    /** Fresh instances every call. The catalogue takes ownership of what it is handed. */
    public static List<CosmeticDef> cosmetics()
    {
        List<CosmeticDef> out = new ArrayList<>();
        out.add(wearable("hw_coffin_hat", CosmeticSlot.HEAD, "Coffin Hat"));
        out.add(wearable("hw_phantom_hat", CosmeticSlot.HEAD, "Phantom Hat"));
        out.add(wearable("hw_pumpkin_hat", CosmeticSlot.HEAD, "Pumpkin Hat"));
        out.add(wearable("hw_coffin_backwear", CosmeticSlot.BACK, "Coffin Backwear"));
        out.add(wearable("hw_phantom_wings", CosmeticSlot.BACK, "Phantom Wings"));
        out.add(wearable("hw_pumpkin_bag", CosmeticSlot.BACK, "Pumpkin Bag"));
        out.add(wearable("hw_phantom_scythe", CosmeticSlot.ACCESSORY, "Phantom Scythe"));
        out.add(wearable("hw_pumpkin_sword", CosmeticSlot.ACCESSORY, "Pumpkin Sword"));
        out.add(wearable("hw_spirit_wand", CosmeticSlot.ACCESSORY, "Spirit Wand"));
        out.add(wearable("hw_bat_hat", CosmeticSlot.HEAD, "Bat Hat"));
        out.add(wearable("hw_witch_cat_hat", CosmeticSlot.HEAD, "Witch Cat Hat"));
        out.add(wearable("hw_pumpkin_head", CosmeticSlot.HEAD, "Pumpkin Head"));
        out.add(wearable("hw_bat_coat", CosmeticSlot.BACK, "Bat Coat"));
        out.add(wearable("hw_pumpkin_wear", CosmeticSlot.BACK, "Pumpkin Wear"));
        out.add(wearable("hw_witch_cat", CosmeticSlot.BACK, "Witch Cat Cape"));
        out.add(wearable("hw_bat_candy_staff", CosmeticSlot.ACCESSORY, "Bat Candy Staff"));
        out.add(wearable("hw_pumpkin_candy", CosmeticSlot.ACCESSORY, "Pumpkin Candy"));
        out.add(wearable("hw_witch_broom", CosmeticSlot.ACCESSORY, "Witch Broom"));
        out.add(wearable("hw_candy_pumpkin_beret", CosmeticSlot.HEAD, "Candy Pumpkin Beret"));
        out.add(wearable("hw_spooky_pumpkin_hat", CosmeticSlot.HEAD, "Spooky Pumpkin Hat"));
        out.add(wearable("hw_witch_hat", CosmeticSlot.HEAD, "Witch Hat"));
        out.add(wearable("hw_candy_pumpkin_backpack", CosmeticSlot.BACK, "Candy Pumpkin Backpack"));
        out.add(wearable("hw_spooky_pumpkin_wings", CosmeticSlot.BACK, "Spooky Pumpkin Wings"));
        out.add(wearable("hw_witch_cauldron", CosmeticSlot.BACK, "Witch Cauldron"));
        out.add(wearable("hw_candy_pumpkin_basket", CosmeticSlot.ACCESSORY, "Candy Pumpkin Basket"));
        out.add(wearable("hw_candy_pumpkin_balloon", CosmeticSlot.ACCESSORY, "Candy Pumpkin Balloon"));
        out.add(wearable("hw_spooky_pumpkin_balloon", CosmeticSlot.ACCESSORY, "Spooky Pumpkin Balloon"));
        out.add(wearable("hw_ghost_balloon", CosmeticSlot.ACCESSORY, "Ghost Balloon"));
        out.add(wearable("hw_spooky_pumpkin_staff", CosmeticSlot.ACCESSORY, "Spooky Pumpkin Staff"));
        out.add(wearable("hw_spooky_witch_broom", CosmeticSlot.ACCESSORY, "Spooky Witch Broom"));
        out.add(simple("hw_pet_devil", CosmeticSlot.PET, "Devil Chibi (Pet)"));
        out.add(simple("hw_pet_frank", CosmeticSlot.PET, "Frankenstein Chibi (Pet)"));
        out.add(simple("hw_pet_mummy", CosmeticSlot.PET, "Mummy Chibi (Pet)"));
        out.add(simple("hw_pet_scarecrow", CosmeticSlot.PET, "Scarecrow Chibi (Pet)"));
        out.add(mount("hw_mount_broomstick", "Witch Broomstick (Mount)"));
        out.add(mount("hw_mount_deadhorse", "Dead Horse (Mount)"));
        out.add(mount("hw_mount_ghostship", "Ghost Ship (Mount)"));
        out.add(mount("hw_mount_skeleptor", "Skeleptor (Mount)"));
        out.add(mount("hw_mount_watcher", "The Watcher (Mount)"));
        out.add(mount("hw_mount_pumpkin_hound", "Pumpkin Hound (Mount)"));
        out.add(mount("hw_mount_pumpkin_spider", "Pumpkin Spider (Mount)"));
        return out;
    }

    /**
     * A mount entry: a {@link #simple} art-only Normal entry, plus the editable movement sub-record seeded from the
     * {@code CosmeticMountType} code table (flying flag and speed) so a fresh catalogue shows the mount's real feel in
     * the editor. An already-seeded catalogue keeps working through the entity's fallback to that same table.
     */
    private static CosmeticDef mount(String id, String displayName)
    {
        CosmeticDef d = simple(id, CosmeticSlot.MOUNT, displayName);
        CosmeticMount mv = net.shurui.shuruisutilities.cosmetics.wardrobe.mount.CosmeticMountType.defaultMovement(id);
        if (mv != null)
            d.mount = mv;
        return d;
    }

    /** A wearable: Normal, Super and Magic eligible, Magic defaulting to the Halloween pool. */
    private static CosmeticDef wearable(String id, CosmeticSlot slot, String displayName)
    {
        CosmeticDef d = simple(id, slot, displayName);
        d.allowedQualities = java.util.EnumSet.of(
                CosmeticQuality.NORMAL, CosmeticQuality.SUPER, CosmeticQuality.MAGIC);
        d.defaultEffectPoolId = MAGIC_POOL;
        return d;
    }

    /** A Normal-only entry (pets and mounts), art only. */
    private static CosmeticDef simple(String id, CosmeticSlot slot, String displayName)
    {
        CosmeticDef d = new CosmeticDef(id, slot);
        d.displayName = displayName;
        d.modelId = "dmz_ragnarok:" + id;
        d.modelPreviewId = "dmz_ragnarok:" + id;
        d.tradeable = false;
        // The collection lives in event now, not rarity. Rarity is left blank (a sensible real value) so the tile
        // colour is the default rather than the collection name.
        d.event = EVENT;
        d.rarity = "";
        // Stamp the measured HEAD placement default (a scale correction, and hair-hiding for a full head) on a
        // fresh seed. The same table drives CosmeticCatalog's migration for a server that seeded before this.
        HeadDefault hd = headDefault(id);
        if (hd != null)
        {
            d.scale = hd.scale;
            d.hidesHair = hd.hidesHair;
            if (hd.rotation != null && hd.rotation.length == 3)
                d.rotation = new float[] { hd.rotation[0], hd.rotation[1], hd.rotation[2] };
        }
        // The seeded accessory style (hand / float / carried) on a fresh seed. The same table drives
        // CosmeticCatalog's migration for a server that seeded before this field. Inert on any non-accessory id,
        // which the map simply does not list.
        CosmeticAccessoryStyle as = accessoryStyle(id);
        if (as != null)
            d.accessoryStyle = as;
        return d.normalise();
    }
}
