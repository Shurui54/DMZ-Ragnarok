package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.data.v2.DataManager;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Everything an admin has authored for the wardrobe: the cosmetics, the MAGIC effects and the effect pools.
 *
 * <p>ONE file for every slot and every record type rather than one per kind, because a definition carries its
 * own {@link CosmeticDef#slot} and because a pool, an effect and the cosmetic that names them are edited in the
 * same sitting and must merge as one unit across shards.
 *
 * <p>Persisted to {@code cosmetics.json} in the SU directory, the same way crates, warps and the task pool are,
 * so it is editable by hand when that is quicker than the editor and survives a jar swap.
 *
 * <h2>Why a JSON file and not SavedData</h2>
 * This is an admin-edited catalogue, exactly like {@code TaskPool} and {@code CrateManager}, and it is read on
 * both the tick thread and (through {@link #saveState}) the shard sync, so the established shape in this suite
 * for that job is a static store behind a JSON file. Note that the known orphaning trap,
 * {@code DataManager.getTypePath} naming a folder after a Java simple class name, applies only to the
 * {@code loadAll(Class)} / {@code saveAll} form. {@link DataManager#load(Class, File)} takes an explicit file and
 * never touches it, which is the form used here and in {@code TaskPool}. The file name below is nonetheless
 * load-bearing: renaming {@code cosmetics.json} orphans every definition on a live server.
 *
 * <h2>The merge, and why it is per id</h2>
 * {@link #saveState} and {@link #mergeState} carry the catalogue between shards through {@code ShardStateSync}.
 * The merge is per record id with a tombstone for a deletion, copied from {@code TaskPool.mergeState}, so two
 * admins adding DIFFERENT records on two servers keep both and a deletion carries rather than being undone by
 * the next merge. Absence alone never deletes; only an explicit newer tombstone does.
 *
 * <h2>STAMP KEYS ARE NAMESPACED, AND THAT IS A PERSISTED FORMAT DECISION</h2>
 * {@link CatalogData#stamps} is one flat map serving all three record types, so its keys carry a type prefix:
 * {@link #NS_COSMETIC}, {@link #NS_EFFECT}, {@link #NS_POOL}. Without it a cosmetic called {@code hollow} and a
 * pool called {@code hollow} would share one tombstone, and deleting either would delete both on every sibling
 * shard. {@code crate:}, {@code shop:} and {@code addon:} are RESERVED for the milestones that add those
 * records; take those exact prefixes when the time comes rather than inventing new ones.
 *
 * <p>A key with NO recognised prefix is read as a cosmetic, which is what an older file written before this
 * change contains. That migration is unambiguous and safe precisely because {@link CosmeticDef#sanitizeId}
 * strips colons, so no id of any kind can ever contain one and no legacy key can be mistaken for a namespaced
 * one. The fold happens on load and on merge, so an older sibling shard converges without a special case.
 */
public final class CosmeticCatalog
{
    /** Stamp namespace for a {@link CosmeticDef}. Also the one an unprefixed legacy key folds into. */
    public static final String NS_COSMETIC = "cos:";

    /** Stamp namespace for a {@link CosmeticEffect}. */
    public static final String NS_EFFECT = "fx:";

    /** Stamp namespace for a {@link CosmeticEffectPool}. */
    public static final String NS_POOL = "pool:";

    /** Stamp namespace for a {@link CosmeticCrate}. */
    public static final String NS_CRATE = "crate:";

    /** Stamp namespace for a {@link CosmeticShopListing}. */
    public static final String NS_SHOP = "shop:";

    /**
     * Every prefix this format reserves, including the one no record type uses YET.
     *
     * <p>{@code addon:} is unbuilt and is listed here on purpose: it is what stops a future add-on called
     * {@code hollow} from being read as a cosmetic by THIS build, which would give it a tombstone it must never
     * have.
     */
    private static final List<String> RESERVED_NAMESPACES =
            List.of(NS_COSMETIC, NS_EFFECT, NS_POOL, NS_CRATE, NS_SHOP, "addon:");

    /** Persisted shape. Maps so an id collision is impossible and a lookup by id is free. */
    public static class CatalogData
    {
        public Map<String, CosmeticDef> cosmetics = new LinkedHashMap<>();

        /** The MAGIC effects, keyed by {@link CosmeticEffect#id}. */
        public Map<String, CosmeticEffect> effects = new LinkedHashMap<>();

        /** The effect pools, keyed by {@link CosmeticEffectPool#id}. */
        public Map<String, CosmeticEffectPool> pools = new LinkedHashMap<>();

        /** The cosmetic crates, keyed by {@link CosmeticCrate#crateName}. */
        public Map<String, CosmeticCrate> crates = new LinkedHashMap<>();

        /** The shop listings, keyed by {@link CosmeticShopListing#id}. */
        public Map<String, CosmeticShopListing> shopListings = new LinkedHashMap<>();

        /**
         * Last-change stamp per NAMESPACED id, kept for the cross-server merge. A stamp is retained even for an
         * id that is no longer in its map: that is a TOMBSTONE, which is how a deletion made on one server
         * carries to the others instead of the record simply coming back on the next merge.
         */
        public Map<String, Long> stamps = new LinkedHashMap<>();
    }

    private static final Map<String, CosmeticDef> DEFS = new LinkedHashMap<>();

    private static final Map<String, CosmeticEffect> EFFECTS = new LinkedHashMap<>();

    private static final Map<String, CosmeticEffectPool> POOLS = new LinkedHashMap<>();

    private static final Map<String, CosmeticCrate> CRATES = new LinkedHashMap<>();

    private static final Map<String, CosmeticShopListing> SHOP_LISTINGS = new LinkedHashMap<>();

    /**
     * Last-change stamp per namespaced id. A key present here but ABSENT from its map is a tombstone: the record
     * was deleted, and the stamp records when, so the deletion wins over a stale copy on another server rather
     * than being undone by it.
     */
    private static final Map<String, Long> STAMPS = new LinkedHashMap<>();

    /**
     * Bumped on every mutation and NEVER reset, so the shard state sync can skip rebuilding this store's NBT
     * while it has not moved and can never miss a change. Mirrors the {@code shardDirtyVersion} the registered
     * SavedData stores keep, which is what {@code ShardStateSync.register}'s change-signal overload wants.
     */
    private static long dirtyVersion;

    private CosmeticCatalog()
    {
    }

    private static File saveFile()
    {
        return new File(ShuruisUtilities.getSUDirectory(), "cosmetics.json");
    }

    public static long dirtyVersion()
    {
        return dirtyVersion;
    }

    /** The file the live catalogue was last loaded from, or null before the first load. See {@link #ensureLoaded}. */
    private static File loadedFrom;

    /**
     * Load the catalogue unless it is already loaded from this server's file. For the public Patreon wardrobe
     * ({@code PatreonWardrobe}), which needs the definitions (and their Patreon tier gates) on every server, keyless
     * included, and must not depend on the private Cosmetics module having loaded them at server start.
     */
    public static void ensureLoaded()
    {
        File f = saveFile();
        if (loadedFrom == null || !loadedFrom.equals(f))
            load();
    }

    public static void load()
    {
        loadedFrom = saveFile();
        DEFS.clear();
        EFFECTS.clear();
        POOLS.clear();
        CRATES.clear();
        SHOP_LISTINGS.clear();
        STAMPS.clear();
        CatalogData data = DataManager.load(CatalogData.class, saveFile());
        if (data != null && data.cosmetics != null)
        {
            for (Map.Entry<String, CosmeticDef> e : data.cosmetics.entrySet())
            {
                CosmeticDef def = e.getValue();
                if (def == null)
                    continue;
                // The MAP KEY is authoritative for the id. A hand-edited file can easily end up with a def whose
                // own id field disagrees with the key it is filed under, and every ownership row references the
                // id, so letting the two drift would strand rows pointing at a cosmetic that cannot be found.
                def.id = e.getKey();
                DEFS.put(def.id, def.normalise());
            }
        }
        if (data != null && data.effects != null)
        {
            for (Map.Entry<String, CosmeticEffect> e : data.effects.entrySet())
            {
                CosmeticEffect fx = e.getValue();
                if (fx == null)
                    continue;
                fx.id = e.getKey(); // key authoritative, same reason as above: copies reference the id
                EFFECTS.put(fx.id, fx.normalise());
            }
        }
        if (data != null && data.pools != null)
        {
            for (Map.Entry<String, CosmeticEffectPool> e : data.pools.entrySet())
            {
                CosmeticEffectPool pool = e.getValue();
                if (pool == null)
                    continue;
                pool.id = e.getKey();
                POOLS.put(pool.id, pool.normalise());
            }
        }
        if (data != null && data.crates != null)
        {
            for (Map.Entry<String, CosmeticCrate> e : data.crates.entrySet())
            {
                CosmeticCrate crate = e.getValue();
                if (crate == null)
                    continue;
                crate.crateName = e.getKey(); // key authoritative, as with defs
                CRATES.put(crate.crateName, crate.normalise());
            }
        }
        if (data != null && data.shopListings != null)
        {
            for (Map.Entry<String, CosmeticShopListing> e : data.shopListings.entrySet())
            {
                CosmeticShopListing listing = e.getValue();
                if (listing == null)
                    continue;
                listing.id = e.getKey();
                SHOP_LISTINGS.put(listing.id, listing.normalise());
            }
        }
        if (data != null && data.stamps != null)
            for (Map.Entry<String, Long> e : data.stamps.entrySet())
                if (e.getKey() != null && e.getValue() != null)
                    STAMPS.put(namespaced(e.getKey()), e.getValue());
        // A live record the file has no stamp for (an older file, or a hand-added entry) seeds at 0, so any
        // stamped incoming change wins for it exactly once, which is the safe direction on a one-time upgrade.
        for (String id : DEFS.keySet())
            STAMPS.putIfAbsent(NS_COSMETIC + id, 0L);
        for (String id : EFFECTS.keySet())
            STAMPS.putIfAbsent(NS_EFFECT + id, 0L);
        for (String id : POOLS.keySet())
            STAMPS.putIfAbsent(NS_POOL + id, 0L);
        for (String id : CRATES.keySet())
            STAMPS.putIfAbsent(NS_CRATE + id, 0L);
        for (String id : SHOP_LISTINGS.keySet())
            STAMPS.putIfAbsent(NS_SHOP + id, 0L);
        boolean seeded = seedDefaults();
        boolean migrated = migrateHalloweenRarityToEvent();
        boolean slotsFolded = migrateTriggeredSlotsToPaired();
        // Revert the old head shrink BEFORE applying the placement defaults, not after: a server still holding a hat
        // at the previous 0.5 to 0.8 shrink is off the identity fingerprint, so the placement pass would skip it. The
        // revert brings it back to 1 (identity), and the placement pass then stamps the current default onto it, so a
        // hat whose default is no longer 1 (the spooky pumpkin hat at 0.85) reaches that value from every prior state.
        migrated |= migrateHalloweenHeadScaleRevert();
        // Reset the stale back-piece facing turn BEFORE the placement defaults, for the same reason the head revert
        // runs first: a piece still holding the previous [0,180,0] is off the identity fingerprint, so the placement
        // pass would skip it. The revert brings its rotation back to zero (identity), matching the uniform upright
        // BACK render, and the placement pass then has no back rotation to re-apply.
        migrated |= migrateHalloweenBackFacingRevert();
        migrated |= migrateHalloweenPlacementDefaults();
        migrated |= migrateHalloweenAccessoryStyles();
        migrated |= migrateAnimationGeoRigs();
        dirtyVersion++;
        if (seeded || migrated || slotsFolded)
            save();
        LoggingHandler.sulog.info(
                "[Cosmetics] Loaded {} cosmetic definition(s), {} effect(s), {} pool(s), {} crate(s), {} listing(s).",
                DEFS.size(), EFFECTS.size(), POOLS.size(), CRATES.size(), SHOP_LISTINGS.size());
    }

    /**
     * Put the shipped effects and pools in on a catalogue that has never had any, so the feature arrives with
     * content rather than an empty editor.
     *
     * <p>GUARDED ON THE STAMPS, NOT ON THE MAPS, and the difference is the whole point. An admin who deletes all
     * eighteen effects leaves eighteen TOMBSTONES behind, so this sees stamps in the {@code fx:} namespace and
     * seeds nothing: their deletion sticks across a restart instead of the catalogue growing back overnight.
     * Effects and pools are guarded separately, so emptying one does not freeze the other.
     *
     * <p>Seeded at stamp ZERO on purpose. Two shards that both seed hold identical content at identical stamps,
     * so neither considers the other newer and the pair is quiet from the first merge. A wall-clock stamp would
     * make them trade one pointless overwrite each first.
     */
    private static boolean seedDefaults()
    {
        boolean seeded = false;
        // The bundled Halloween cosmetics, seeded PER ID rather than per namespace, because an admin authors
        // cosmetics of their own in the cos: namespace and a namespace-wide guard would stop shipped content from
        // ever arriving once they had. A stamp on an id (live OR tombstone) means that id has been dealt with, so
        // an admin's edit is never overwritten and a deleted seed does not grow back. Seeded at stamp ZERO so two
        // shards that both seed converge silently, matching the effect and pool seeds below.
        int seededCosmetics = 0;
        for (CosmeticDef def : CosmeticHalloweenDefaults.cosmetics())
        {
            if (def == null || def.id == null || def.id.isBlank())
                continue;
            String key = NS_COSMETIC + def.id;
            if (STAMPS.containsKey(key))
                continue;
            DEFS.put(def.id, def.normalise());
            STAMPS.put(key, 0L);
            seededCosmetics++;
        }
        if (seededCosmetics > 0)
        {
            seeded = true;
            LoggingHandler.sulog.info("[Cosmetics] Seeded {} bundled Halloween cosmetic(s).", seededCosmetics);
        }
        // The bundled triggered animations, seeded PER ID on the same rules as the cosmetics above: a deleted or
        // edited one is never regrown or overwritten, and stamp ZERO keeps two seeding shards silent.
        int seededAnimations = 0;
        for (CosmeticDef def : CosmeticAnimationDefaults.animations())
        {
            if (def == null || def.id == null || def.id.isBlank())
                continue;
            String key = NS_COSMETIC + def.id;
            if (STAMPS.containsKey(key))
                continue;
            DEFS.put(def.id, def.normalise());
            STAMPS.put(key, 0L);
            seededAnimations++;
        }
        if (seededAnimations > 0)
        {
            seeded = true;
            LoggingHandler.sulog.info("[Cosmetics] Seeded {} bundled Halloween animation(s).", seededAnimations);
        }
        if (!hasStampIn(NS_EFFECT))
        {
            for (CosmeticEffect fx : CosmeticEffectDefaults.effects())
            {
                EFFECTS.put(fx.id, fx);
                STAMPS.put(NS_EFFECT + fx.id, 0L);
            }
            seeded = true;
            LoggingHandler.sulog.info("[Cosmetics] Seeded {} shipped effect(s) into a catalogue that had none.",
                    EFFECTS.size());
        }
        if (!hasStampIn(NS_POOL))
        {
            for (CosmeticEffectPool pool : CosmeticEffectDefaults.pools())
            {
                POOLS.put(pool.id, pool);
                STAMPS.put(NS_POOL + pool.id, 0L);
            }
            seeded = true;
            LoggingHandler.sulog.info("[Cosmetics] Seeded {} shipped effect pool(s).", POOLS.size());
        }
        // The bundled Halloween crate, seeded PER crate name on the crate: stamp, the same discipline the cosmetics
        // above use: an admin who deletes it leaves a tombstone and it does not grow back, and an admin's edits to a
        // seeded crate are never overwritten. Seeded at stamp ZERO so two shards that both seed converge silently.
        // Its entries reference the Halloween cosmetics and pool_hollow seeded just above, so this runs last.
        int seededCrates = 0;
        for (CosmeticCrate crate : CosmeticCrateDefaults.crates())
        {
            if (crate == null || crate.crateName == null || crate.crateName.isBlank())
                continue;
            String key = NS_CRATE + crate.crateName;
            if (STAMPS.containsKey(key))
                continue;
            CRATES.put(crate.crateName, crate.normalise());
            STAMPS.put(key, 0L);
            seededCrates++;
        }
        if (seededCrates > 0)
        {
            seeded = true;
            LoggingHandler.sulog.info("[Cosmetics] Seeded {} bundled cosmetic crate(s).", seededCrates);
        }
        return seeded;
    }

    /**
     * Fold the retired collection-in-rarity stand-in into {@link CosmeticDef#event}, once, on any def that still
     * carries it.
     *
     * <p>The early Halloween seed had no {@code event} field, so it grouped its entries by writing
     * {@link CosmeticHalloweenDefaults#LEGACY_RARITY} ("Halloween 2026") into {@code rarity}. A world seeded that
     * way already holds a stamp per id, so the seeder never revisits those entries: this pass is what moves them
     * onto the real field. Any def whose rarity is EXACTLY that string and whose event is still blank gets
     * {@link CosmeticHalloweenDefaults#EVENT} in {@code event} and a cleared {@code rarity}, and is RE-STAMPED so
     * the change carries to sibling shards the same way an admin edit would.
     *
     * <p>Idempotent by construction: it clears the rarity it matched on, so a second pass finds nothing, and it
     * only ever touches a def whose event is blank, so it can never overwrite an admin's own event choice.
     */
    private static boolean migrateHalloweenRarityToEvent()
    {
        int changed = 0;
        for (CosmeticDef def : DEFS.values())
        {
            if (def == null)
                continue;
            String rarity = def.rarity == null ? "" : def.rarity.trim();
            String event = def.event == null ? "" : def.event.trim();
            if (event.isBlank() && rarity.equals(CosmeticHalloweenDefaults.LEGACY_RARITY))
            {
                def.event = CosmeticHalloweenDefaults.EVENT;
                def.rarity = "";
                def.normalise();
                STAMPS.put(NS_COSMETIC + def.id, System.currentTimeMillis());
                changed++;
            }
        }
        if (changed > 0)
            LoggingHandler.sulog.info(
                    "[Cosmetics] Migrated {} cosmetic(s) from the '{}' rarity stand-in to the '{}' event.",
                    changed, CosmeticHalloweenDefaults.LEGACY_RARITY, CosmeticHalloweenDefaults.EVENT);
        return changed > 0;
    }

    /**
     * Fold any definition still homed on a retired single-direction triggered slot onto a paired one, once, so it
     * has an enabled home tab in the editor and the wardrobe. A def on {@code join} or {@code leave} becomes
     * {@code join_leave}, one on {@code tp_depart} or {@code tp_arrive} becomes {@code teleport}. Re-stamped so the
     * fold carries to sibling shards exactly as an admin edit would.
     *
     * <p>Idempotent: a def already on a paired slot, or on any non-triggered slot, is left untouched, so a second
     * pass finds nothing. Equipping is not affected either way (a triggered animation goes into any paired slot
     * regardless of its home), so this is purely about which tab it shows under.
     */
    private static boolean migrateTriggeredSlotsToPaired()
    {
        int changed = 0;
        for (CosmeticDef def : DEFS.values())
        {
            if (def == null || def.slot == null)
                continue;
            CosmeticSlot target = null;
            if (def.slot == CosmeticSlot.JOIN || def.slot == CosmeticSlot.LEAVE)
                target = CosmeticSlot.JOIN_LEAVE;
            else if (def.slot == CosmeticSlot.TP_DEPART || def.slot == CosmeticSlot.TP_ARRIVE)
                target = CosmeticSlot.TELEPORT;
            if (target == null)
                continue;
            def.slot = target;
            def.normalise();
            STAMPS.put(NS_COSMETIC + def.id, System.currentTimeMillis());
            changed++;
        }
        if (changed > 0)
            LoggingHandler.sulog.info(
                    "[Cosmetics] Folded {} animation definition(s) from the retired join/leave/tp slots into "
                            + "join_leave/teleport.",
                    changed);
        return changed > 0;
    }

    /**
     * Stamp the measured HEAD placement defaults (a scale correction, and hair-hiding for a full head) onto a
     * seeded Halloween head that a server seeded BEFORE those defaults existed, once.
     *
     * <p>A fresh seed already carries them ({@link CosmeticHalloweenDefaults#simple}), but a server seeded earlier
     * holds a stamp per id so the seeder never revisits it: this pass is what brings its heads from the old
     * "authored two to three times head size, hair showing" state to the shipped default. It touches a def only
     * when its placement is still at IDENTITY (offset and rotation all zero, scale 1), which is the fingerprint of
     * a record no admin has tuned, so it can never overwrite an admin's own resize. It re-STAMPS what it changes so
     * the correction carries to sibling shards the way an admin edit would.
     *
     * <p>Idempotent by construction: applying a shrink moves the scale off 1, so the identity fingerprint no longer
     * matches; applying only the hair flag (the pumpkin head, whose scale stays 1) leaves the values equal to the
     * target, so the "differs" test is false on the next pass. Either way a second run finds nothing and re-stamps
     * nothing, so it never spams the shard sync.
     */
    private static boolean migrateHalloweenPlacementDefaults()
    {
        int changed = 0;
        for (Map.Entry<String, CosmeticHalloweenDefaults.HeadDefault> e
                : CosmeticHalloweenDefaults.headDefaults().entrySet())
        {
            CosmeticDef def = DEFS.get(e.getKey());
            CosmeticHalloweenDefaults.HeadDefault hd = e.getValue();
            if (def == null || hd == null)
                continue;
            if (!placementAtIdentity(def))
                continue;
            boolean rotates = hd.rotation != null && hd.rotation.length == 3
                    && (hd.rotation[0] != 0.0F || hd.rotation[1] != 0.0F || hd.rotation[2] != 0.0F);
            if (def.scale == hd.scale && def.hidesHair == hd.hidesHair && !rotates)
                continue;
            def.scale = hd.scale;
            def.hidesHair = hd.hidesHair;
            // Seeded facing turn, if this id carries one. Applied only to an at-identity record for the same
            // reason as the scale: a non-zero seed moves the def off identity, so a second pass skips it and an
            // admin's own rotation (already off identity) is never overwritten.
            if (rotates)
                def.rotation = new float[] { hd.rotation[0], hd.rotation[1], hd.rotation[2] };
            def.normalise();
            STAMPS.put(NS_COSMETIC + def.id, System.currentTimeMillis());
            changed++;
        }
        if (changed > 0)
            LoggingHandler.sulog.info(
                    "[Cosmetics] Applied measured HEAD placement default(s) to {} seeded Halloween head(s).",
                    changed);
        return changed > 0;
    }

    /**
     * Stamp the seeded accessory style (hand for the weapon props, float for the balloons) onto a seeded Halloween
     * accessory that a server seeded BEFORE the style field existed, once.
     *
     * <p>A fresh seed already carries it ({@link CosmeticHalloweenDefaults#simple}), but a server seeded earlier
     * holds a stamp per id so the seeder never revisits it, and the field read off its record defaults to
     * {@link CosmeticAccessoryStyle#CARRIED} (the prior always-in-hand behaviour). This pass moves the eight held
     * props to {@link CosmeticAccessoryStyle#HAND} and the three balloons to {@link CosmeticAccessoryStyle#FLOAT}.
     * It touches a def only when it is on the ACCESSORY slot and its style is still the CARRIED default, which is the
     * fingerprint of a record no admin has retuned, so it never overwrites an admin's own choice. It re-STAMPS what
     * it changes so the correction carries to sibling shards the way an admin edit would.
     *
     * <p>Idempotent by construction: setting HAND or FLOAT moves the style off the CARRIED default, so a second pass
     * skips it. The candy basket's seeded style IS CARRIED, so it equals the default and is never touched here.
     */
    private static boolean migrateHalloweenAccessoryStyles()
    {
        int changed = 0;
        for (Map.Entry<String, CosmeticAccessoryStyle> e
                : CosmeticHalloweenDefaults.accessoryStyles().entrySet())
        {
            CosmeticDef def = DEFS.get(e.getKey());
            CosmeticAccessoryStyle target = e.getValue();
            if (def == null || target == null || def.slot != CosmeticSlot.ACCESSORY)
                continue;
            // The unmigrated fingerprint: still at the CARRIED default. An admin who set a style is off it and is
            // left alone; a target that IS CARRIED (the basket) equals the default and is a no-op here.
            if (def.accessoryStyle != CosmeticAccessoryStyle.CARRIED || target == def.accessoryStyle)
                continue;
            def.accessoryStyle = target;
            def.normalise();
            STAMPS.put(NS_COSMETIC + def.id, System.currentTimeMillis());
            changed++;
        }
        if (changed > 0)
            LoggingHandler.sulog.info(
                    "[Cosmetics] Applied the seeded accessory style to {} seeded Halloween accessory(ies).", changed);
        return changed > 0;
    }

    /**
     * Stamp the geo RIG fields onto a seeded Halloween ANIMATION that a server seeded BEFORE those fields existed,
     * once, so the five shipped animations draw their real GeckoLib rig rather than only their particle fallback.
     *
     * <p>This is the fix for "the animation models are not spawning, still just particle effects": the five
     * animations were seeded when {@link CosmeticAnimation} had no {@code style = geo}, {@code geoRig} or the in/out
     * tick fields. Such a server holds a stamp per id, so {@link #seedDefaults()} never revisits them and they stay
     * plain particle records with a blank {@code geoRig}; {@link CosmeticAnimation#hasGeo()} is then false and the
     * client always draws particles. A fresh seed already carries the geo fields ({@link CosmeticAnimationDefaults}),
     * so this pass only lifts the older records up to the same shape.
     *
     * <p>It touches a live animation only when it is still at the UNMIGRATED fingerprint: not already geo, a blank
     * {@code geoRig}, and a {@code style} still equal to the shipped fallback (the particle style the record was
     * originally seeded with). An admin who re-authored the style or already named a rig is off that fingerprint and
     * is left alone, the same discipline the placement and accessory migrations use. It re-STAMPS what it changes so
     * the upgrade carries to sibling shards the way an admin edit would.
     *
     * <p>Idempotent by construction: setting {@code style = geo} and a {@code geoRig} moves the record off the
     * fingerprint, so a second pass finds nothing and re-stamps nothing.
     */
    private static boolean migrateAnimationGeoRigs()
    {
        int changed = 0;
        for (CosmeticDef seed : CosmeticAnimationDefaults.animations())
        {
            if (seed == null || seed.id == null || seed.animation == null)
                continue;
            CosmeticAnimation want = seed.animation;
            if (!want.hasGeo())
                continue;
            CosmeticDef live = DEFS.get(seed.id);
            if (live == null || live.animation == null)
                continue;
            CosmeticAnimation a = live.animation;
            // The unmigrated fingerprint: not yet geo, no rig named, and the style is still the shipped particle
            // fallback the record was first seeded with. Anything else is an admin's own edit and is left untouched.
            boolean untouched = !CosmeticAnimation.STYLE_GEO.equals(a.style)
                    && (a.geoRig == null || a.geoRig.isBlank())
                    && a.style != null && a.style.equals(want.geoFallbackStyle);
            if (!untouched)
                continue;
            a.style = CosmeticAnimation.STYLE_GEO;
            a.geoRig = want.geoRig;
            a.geoScale = want.geoScale;
            a.geoFallbackStyle = want.geoFallbackStyle;
            a.geoInTicks = want.geoInTicks;
            a.geoOutTicks = want.geoOutTicks;
            live.normalise();
            STAMPS.put(NS_COSMETIC + live.id, System.currentTimeMillis());
            changed++;
        }
        if (changed > 0)
            LoggingHandler.sulog.info(
                    "[Cosmetics] Upgraded {} seeded Halloween animation(s) to their geo rig (were particle-only).",
                    changed);
        return changed > 0;
    }

    /**
     * Reset the previous build's BACK facing turn, once.
     *
     * <p>An earlier BACK render placed a piece by honouring the pack's armour-stand-head transform, which sat two
     * pieces (the witch cat cape and the witch cauldron) facing the wrong way, so they were seeded a {@code [0,180,0]}
     * rotation to turn them round. The current BACK render runs every piece through one uniform upright recipe that
     * already faces the decorated side outward, so that per-item turn is stale and would now spin those two to face
     * inward. A fresh seed no longer carries it ({@link CosmeticHalloweenDefaults}); this brings a server that ran the
     * earlier build back to the uniform baseline.
     *
     * <p>It touches a def ONLY when its rotation still exactly equals the value that build seeded
     * ({@link CosmeticHalloweenDefaults#previousBackYawRotations}) and its offset is still zero and its scale still 1:
     * the fingerprint of a record the earlier build turned and no admin has retuned. An admin who set their own
     * rotation is off that fingerprint and is left alone. It re-STAMPS what it changes so the reset carries to sibling
     * shards the way an admin edit would.
     *
     * <p>Idempotent: once a piece is back at a zero rotation it no longer matches the seeded turn, so a second pass
     * finds nothing and re-stamps nothing.
     */
    private static boolean migrateHalloweenBackFacingRevert()
    {
        int changed = 0;
        for (Map.Entry<String, float[]> e : CosmeticHalloweenDefaults.previousBackYawRotations().entrySet())
        {
            CosmeticDef def = DEFS.get(e.getKey());
            float[] prev = e.getValue();
            if (def == null || prev == null || prev.length != 3)
                continue;
            // Exact rotation match against the former seed, plus untouched offset and scale: the fingerprint of a
            // record the earlier build turned and no admin has since retuned.
            if (def.scale != 1.0F || !offsetZero(def) || !rotationEquals(def, prev))
                continue;
            def.rotation = new float[] { 0.0F, 0.0F, 0.0F };
            def.normalise();
            STAMPS.put(NS_COSMETIC + def.id, System.currentTimeMillis());
            changed++;
        }
        if (changed > 0)
            LoggingHandler.sulog.info(
                    "[Cosmetics] Reset the previous build's back-piece facing turn on {} seeded Halloween piece(s).",
                    changed);
        return changed > 0;
    }

    /** Whether a def's placement is untouched: offset and rotation all zero and scale 1. The unmigrated fingerprint. */
    private static boolean placementAtIdentity(CosmeticDef def)
    {
        return def.scale == 1.0F && offsetAndRotationZero(def);
    }

    /** Whether a def's offset is all zero, ignoring rotation and scale. */
    private static boolean offsetZero(CosmeticDef def)
    {
        float[] o = def.offset;
        for (int i = 0; i < 3; i++)
            if (o != null && i < o.length && o[i] != 0.0F)
                return false;
        return true;
    }

    /** Whether a def's rotation exactly equals the given [x,y,z] degrees. */
    private static boolean rotationEquals(CosmeticDef def, float[] r)
    {
        float[] dr = def.rotation;
        for (int i = 0; i < 3; i++)
        {
            float a = dr != null && i < dr.length ? dr[i] : 0.0F;
            if (a != r[i])
                return false;
        }
        return true;
    }

    /** Whether a def's offset and rotation are all zero, ignoring scale. */
    private static boolean offsetAndRotationZero(CosmeticDef def)
    {
        float[] o = def.offset;
        float[] r = def.rotation;
        for (int i = 0; i < 3; i++)
        {
            if (o != null && i < o.length && o[i] != 0.0F)
                return false;
            if (r != null && i < r.length && r[i] != 0.0F)
                return false;
        }
        return true;
    }

    /**
     * Revert the previous build's HEAD shrink, once.
     *
     * <p>An earlier build seeded six Halloween hats at a 0.5 to 0.8 scale to bring an armour-stand-scale author
     * down to head size; the owner found the result too small and said the hats were fine before, so the shipped
     * default is back at scale 1 ({@link CosmeticHalloweenDefaults}). A fresh seed already carries 1, but a server
     * that ran the earlier build holds those hats at the shrunk scale, and this brings them back to 1.
     *
     * <p>It touches a head ONLY when its scale still exactly equals the value that build seeded
     * ({@link CosmeticHalloweenDefaults#previousHeadShrinkScales}) and its offset and rotation are still zero.
     * That exact scale is the fingerprint of a record the earlier migration placed and no admin has retuned: an
     * admin who resized the hat set some other scale (or added an offset or rotation) and is left untouched. It
     * re-STAMPS what it changes so the revert carries to sibling shards the way an admin edit would.
     *
     * <p>Idempotent: once a hat is back at 1 its scale no longer equals any shrink value, so a second pass finds
     * nothing and re-stamps nothing.
     */
    private static boolean migrateHalloweenHeadScaleRevert()
    {
        int changed = 0;
        for (Map.Entry<String, Float> e : CosmeticHalloweenDefaults.previousHeadShrinkScales().entrySet())
        {
            CosmeticDef def = DEFS.get(e.getKey());
            Float prev = e.getValue();
            if (def == null || prev == null)
                continue;
            // Exact float match against the former default, plus untouched offset/rotation: the fingerprint of a
            // record the earlier build shrank and no admin has since retuned.
            if (def.scale != prev.floatValue() || !offsetAndRotationZero(def))
                continue;
            def.scale = 1.0F;
            def.normalise();
            STAMPS.put(NS_COSMETIC + def.id, System.currentTimeMillis());
            changed++;
        }
        if (changed > 0)
            LoggingHandler.sulog.info(
                    "[Cosmetics] Reverted the previous build's HEAD shrink on {} seeded Halloween hat(s).", changed);
        return changed > 0;
    }

    /**
     * Every distinct, non-blank {@link CosmeticDef#event} in the catalogue, sorted, for an editor dropdown or a
     * filter. Case-insensitive order; a duplicate that differs only in case is kept once, first spelling wins.
     */
    public static List<String> distinctEvents()
    {
        java.util.TreeMap<String, String> seen = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (CosmeticDef d : DEFS.values())
        {
            if (d == null || d.event == null)
                continue;
            String ev = d.event.trim();
            if (!ev.isBlank())
                seen.putIfAbsent(ev, ev);
        }
        return new ArrayList<>(seen.values());
    }

    private static boolean hasStampIn(String namespace)
    {
        for (String key : STAMPS.keySet())
            if (key != null && key.startsWith(namespace))
                return true;
        return false;
    }

    /** A stamp key as this build writes it. An unprefixed legacy key folds into {@link #NS_COSMETIC}. */
    private static String namespaced(String key)
    {
        if (key == null)
            return NS_COSMETIC;
        for (String ns : RESERVED_NAMESPACES)
            if (key.startsWith(ns))
                return key;
        return NS_COSMETIC + key;
    }

    public static void save()
    {
        CatalogData data = new CatalogData();
        data.cosmetics.putAll(DEFS);
        data.effects.putAll(EFFECTS);
        data.pools.putAll(POOLS);
        data.crates.putAll(CRATES);
        data.shopListings.putAll(SHOP_LISTINGS);
        data.stamps.putAll(STAMPS);
        DataManager.save(data, saveFile());
    }

    /** The live definition for this id, or null. Never mutate the returned instance outside an editor save. */
    public static CosmeticDef get(String id)
    {
        return id == null ? null : DEFS.get(id);
    }

    /** A COPY of the definition for this id, or null. What every reader outside this class should use. */
    public static CosmeticDef copyOf(String id)
    {
        CosmeticDef d = get(id);
        return d == null ? null : d.copy();
    }

    /** Every definition, in insertion order, as live instances (the editor's own read path). */
    public static Collection<CosmeticDef> all()
    {
        return new ArrayList<>(DEFS.values());
    }

    /** Every definition a player could actually wear right now: enabled, on a live slot. */
    public static List<CosmeticDef> wearable()
    {
        List<CosmeticDef> out = new ArrayList<>();
        for (CosmeticDef d : DEFS.values())
            if (d.wearable())
                out.add(d);
        return out;
    }

    /** Ids in use, so the editor can refuse a duplicate before it silently overwrites one. */
    public static Set<String> ids()
    {
        return new HashMap<>(DEFS).keySet();
    }

    public static int size()
    {
        return DEFS.size();
    }

    /** Store (and stamp) a definition, then persist. The only write path. */
    public static void put(CosmeticDef def)
    {
        if (def == null || def.id == null || def.id.isBlank())
            return;
        DEFS.put(def.id, def.normalise());
        STAMPS.put(NS_COSMETIC + def.id, System.currentTimeMillis());
        dirtyVersion++;
        save();
    }

    /**
     * Delete a definition, leaving a tombstone.
     *
     * <p>Ownership rows referencing it are deliberately NOT touched: a definition deleted by accident and
     * recreated with the same id should find everybody still owning it, and a player's purchase must not be
     * destroyed by an admin tidying the list. A row pointing at a missing definition simply does not render and
     * does not appear in the wardrobe. See {@link CosmeticLedgerData}.
     */
    public static boolean remove(String id)
    {
        if (id == null || DEFS.remove(id) == null)
            return false;
        // Leave a tombstone (a stamp with no live definition) so the deletion carries to sibling servers. Without
        // it, the next merge from a server that still holds the cosmetic would silently recreate it.
        STAMPS.put(NS_COSMETIC + id, System.currentTimeMillis());
        dirtyVersion++;
        save();
        return true;
    }

    // ------------------------------------------------------------------------------------------------------
    // Effects. Same contract as the definitions above, one namespace along.
    // ------------------------------------------------------------------------------------------------------

    /** The live effect for this id, or null. Never mutate the returned instance outside an editor save. */
    public static CosmeticEffect effect(String id)
    {
        return id == null ? null : EFFECTS.get(id);
    }

    /** A COPY of the effect for this id, or null. What every reader outside this class should use. */
    public static CosmeticEffect copyOfEffect(String id)
    {
        CosmeticEffect fx = effect(id);
        return fx == null ? null : fx.copy();
    }

    public static Collection<CosmeticEffect> allEffects()
    {
        return new ArrayList<>(EFFECTS.values());
    }

    public static Set<String> effectIds()
    {
        return new HashMap<>(EFFECTS).keySet();
    }

    public static int effectCount()
    {
        return EFFECTS.size();
    }

    public static void putEffect(CosmeticEffect fx)
    {
        if (fx == null || fx.id == null || fx.id.isBlank())
            return;
        EFFECTS.put(fx.id, fx.normalise());
        STAMPS.put(NS_EFFECT + fx.id, System.currentTimeMillis());
        dirtyVersion++;
        save();
    }

    /**
     * Delete an effect, leaving a tombstone.
     *
     * <p>Copies that rolled it keep their {@link CosmeticOwnership#effectId} and pools keep their entry, exactly
     * as a deleted cosmetic keeps its ownership rows. An effect deleted by accident and recreated with the same
     * id therefore comes back working for everybody who had it, and nobody's Magic copy is destroyed by an admin
     * tidying a list. A copy pointing at a missing effect presents as nothing.
     */
    public static boolean removeEffect(String id)
    {
        if (id == null || EFFECTS.remove(id) == null)
            return false;
        STAMPS.put(NS_EFFECT + id, System.currentTimeMillis());
        dirtyVersion++;
        save();
        return true;
    }

    // ------------------------------------------------------------------------------------------------------
    // Pools.
    // ------------------------------------------------------------------------------------------------------

    public static CosmeticEffectPool pool(String id)
    {
        return id == null ? null : POOLS.get(id);
    }

    public static CosmeticEffectPool copyOfPool(String id)
    {
        CosmeticEffectPool p = pool(id);
        return p == null ? null : p.copy();
    }

    public static Collection<CosmeticEffectPool> allPools()
    {
        return new ArrayList<>(POOLS.values());
    }

    public static Set<String> poolIds()
    {
        return new HashMap<>(POOLS).keySet();
    }

    public static int poolCount()
    {
        return POOLS.size();
    }

    public static void putPool(CosmeticEffectPool pool)
    {
        if (pool == null || pool.id == null || pool.id.isBlank())
            return;
        POOLS.put(pool.id, pool.normalise());
        STAMPS.put(NS_POOL + pool.id, System.currentTimeMillis());
        dirtyVersion++;
        save();
    }

    /**
     * Delete a pool, leaving a tombstone. A cosmetic naming it in {@link CosmeticDef#defaultEffectPoolId} is
     * deliberately left alone, for the same reason a deleted cosmetic keeps its ownership rows.
     */
    public static boolean removePool(String id)
    {
        if (id == null || POOLS.remove(id) == null)
            return false;
        STAMPS.put(NS_POOL + id, System.currentTimeMillis());
        dirtyVersion++;
        save();
        return true;
    }

    /**
     * Every pool entry naming an effect that is not in the catalogue, as pool id to the missing effect ids.
     *
     * <p>For the editor to WARN with, never to repair with. A dangling reference is usually a shard that has not
     * merged yet or an effect an admin is about to recreate, and silently rewriting an admin's pool because a
     * record was briefly missing would be far worse than showing them a list.
     */
    public static Map<String, List<String>> danglingPoolRefs()
    {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (CosmeticEffectPool pool : POOLS.values())
        {
            List<String> missing = new ArrayList<>();
            for (String effectId : pool.effectIds())
                if (!EFFECTS.containsKey(effectId))
                    missing.add(effectId);
            if (!missing.isEmpty())
                out.put(pool.id, missing);
        }
        return out;
    }

    // ------------------------------------------------------------------------------------------------------
    // Crates. Same contract as the records above, one namespace along. Keyed by crate name.
    // ------------------------------------------------------------------------------------------------------

    /** The live crate record for this crate name, or null. Never mutate the returned instance outside an editor. */
    public static CosmeticCrate crate(String crateName)
    {
        return crateName == null ? null : CRATES.get(crateName);
    }

    /** A COPY of the crate record for this crate name, or null. What every reader outside this class should use. */
    public static CosmeticCrate copyOfCrate(String crateName)
    {
        CosmeticCrate c = crate(crateName);
        return c == null ? null : c.copy();
    }

    /**
     * The crate record bound to this crate name IF it is enabled and could actually roll, else null.
     *
     * <p>The one question the crate block asks: "is this a cosmetic crate right now". A disabled or empty record
     * makes the block behave as a plain crate again, which is the honest fallback.
     */
    public static CosmeticCrate rollableCrate(String crateName)
    {
        CosmeticCrate c = crate(crateName);
        return c != null && c.rollable() ? c : null;
    }

    public static Collection<CosmeticCrate> allCrates()
    {
        return new ArrayList<>(CRATES.values());
    }

    public static Set<String> crateNames()
    {
        return new HashMap<>(CRATES).keySet();
    }

    public static int crateCount()
    {
        return CRATES.size();
    }

    public static void putCrate(CosmeticCrate crate)
    {
        if (crate == null || crate.crateName == null || crate.crateName.isBlank())
            return;
        CRATES.put(crate.crateName, crate.normalise());
        STAMPS.put(NS_CRATE + crate.crateName, System.currentTimeMillis());
        dirtyVersion++;
        save();
    }

    /** Delete a crate record, leaving a tombstone. The bound world crate then opens as a plain crate again. */
    public static boolean removeCrate(String crateName)
    {
        if (crateName == null || CRATES.remove(crateName) == null)
            return false;
        STAMPS.put(NS_CRATE + crateName, System.currentTimeMillis());
        dirtyVersion++;
        save();
        return true;
    }

    // ------------------------------------------------------------------------------------------------------
    // Shop listings. Keyed by listing id, NOT by cosmetic id: two listings may sell one cosmetic.
    // ------------------------------------------------------------------------------------------------------

    public static CosmeticShopListing listing(String id)
    {
        return id == null ? null : SHOP_LISTINGS.get(id);
    }

    public static CosmeticShopListing copyOfListing(String id)
    {
        CosmeticShopListing l = listing(id);
        return l == null ? null : l.copy();
    }

    public static Collection<CosmeticShopListing> allListings()
    {
        return new ArrayList<>(SHOP_LISTINGS.values());
    }

    /** Every listing on sale right now, sorted by category then sortOrder then id: the shop's own read. */
    public static List<CosmeticShopListing> listingsOnSale(long now)
    {
        List<CosmeticShopListing> out = new ArrayList<>();
        for (CosmeticShopListing l : SHOP_LISTINGS.values())
            if (l != null && l.onSale(now))
                out.add(l);
        out.sort((a, b) ->
        {
            int byCat = String.CASE_INSENSITIVE_ORDER.compare(nz(a.category), nz(b.category));
            if (byCat != 0)
                return byCat;
            if (a.sortOrder != b.sortOrder)
                return Integer.compare(a.sortOrder, b.sortOrder);
            return a.id.compareToIgnoreCase(b.id);
        });
        return out;
    }

    public static Set<String> listingIds()
    {
        return new HashMap<>(SHOP_LISTINGS).keySet();
    }

    public static int listingCount()
    {
        return SHOP_LISTINGS.size();
    }

    public static void putListing(CosmeticShopListing listing)
    {
        if (listing == null || listing.id == null || listing.id.isBlank())
            return;
        SHOP_LISTINGS.put(listing.id, listing.normalise());
        STAMPS.put(NS_SHOP + listing.id, System.currentTimeMillis());
        dirtyVersion++;
        save();
    }

    /** Delete a listing, leaving a tombstone. The cosmetic and every ownership row are untouched. */
    public static boolean removeListing(String id)
    {
        if (id == null || SHOP_LISTINGS.remove(id) == null)
            return false;
        STAMPS.put(NS_SHOP + id, System.currentTimeMillis());
        dirtyVersion++;
        save();
        return true;
    }

    private static String nz(String s)
    {
        return s == null ? "" : s;
    }

    // ------------------------------------------------------------------------------------------------------
    // The shard state sync.
    // ------------------------------------------------------------------------------------------------------

    /**
     * Snapshot the whole catalogue (every record plus the per-id stamps, including tombstones) as one NBT tag
     * for {@code ShardStateSync}.
     *
     * <p>Sorted by id within each list so two servers holding the same catalogue produce byte-identical NBT
     * regardless of the order the entries were inserted; without that the content hash would differ between
     * converged servers and they would republish the same state forever.
     */
    public static CompoundTag saveState()
    {
        return snapshot(DEFS, EFFECTS, POOLS, CRATES, SHOP_LISTINGS, STAMPS);
    }

    /**
     * Merge a sibling server's catalogue into this one, per id, keeping the later stamp.
     *
     * <p>Two admins adding DIFFERENT records on two servers keep both, because an entry is only touched when its
     * own namespaced id carries a newer stamp than the copy here. One edited on both within one interval keeps
     * the later edit, the same last-write-wins trade every other admin sync in the suite makes and acceptable
     * for the same reason: this is deliberate admin work. A DELETION carries as a tombstone (a newer stamp with
     * no record), so absence alone never deletes and a merge can never lose a record by omission.
     */
    public static void mergeState(CompoundTag tag)
    {
        if (mergeInto(tag, DEFS, EFFECTS, POOLS, CRATES, SHOP_LISTINGS, STAMPS))
        {
            dirtyVersion++;
            save();
        }
    }

    /**
     * The pure form of {@link #saveState}: no statics, no IO.
     *
     * <p>Split out so the merge rules can be exercised against maps a test owns, which is the only way to prove
     * idempotence and commutativity without a running server behind {@code getSUDirectory()}.
     */
    public static CompoundTag snapshot(Map<String, CosmeticDef> defs, Map<String, CosmeticEffect> effects,
            Map<String, CosmeticEffectPool> pools, Map<String, CosmeticCrate> crates,
            Map<String, CosmeticShopListing> shopListings, Map<String, Long> stamps)
    {
        CompoundTag root = new CompoundTag();
        ListTag cosmeticList = new ListTag();
        for (String id : sortedKeys(defs))
            cosmeticList.add(defs.get(id).toNbt());
        root.put("cosmetics", cosmeticList);
        ListTag effectList = new ListTag();
        for (String id : sortedKeys(effects))
            effectList.add(effects.get(id).toNbt());
        root.put("effects", effectList);
        ListTag poolList = new ListTag();
        for (String id : sortedKeys(pools))
            poolList.add(pools.get(id).toNbt());
        root.put("pools", poolList);
        ListTag crateList = new ListTag();
        for (String id : sortedKeys(crates))
            crateList.add(crates.get(id).toNbt());
        root.put("crates", crateList);
        ListTag shopList = new ListTag();
        for (String id : sortedKeys(shopListings))
            shopList.add(shopListings.get(id).toNbt());
        root.put("shopListings", shopList);
        CompoundTag stampTag = new CompoundTag();
        for (Map.Entry<String, Long> e : stamps.entrySet())
            if (e.getKey() != null && e.getValue() != null)
                stampTag.putLong(e.getKey(), e.getValue());
        root.put("stamps", stampTag);
        return root;
    }

    /**
     * The pure form of {@link #mergeState}: applies an incoming snapshot to the maps handed in and answers
     * whether anything actually moved. No statics, no IO.
     *
     * <p>THE ONE THING TO KNOW: a stamp whose namespace this build does not recognise is IGNORED, not stored. A
     * build that kept one would republish it in its own snapshot with no record behind it, which reads to a
     * build that DOES know that record type as a tombstone, and would delete a crate or a shop listing that
     * nobody deleted. Carrying a stamp we cannot carry the record for is worse than dropping it.
     *
     * <p>THE SECOND THING: an EXACT stamp tie is broken on content, by {@link #takeAtEqualStamp}. Two admins
     * editing the same record in the same millisecond on two shards used to leave the pair holding different
     * content at the same stamp for ever, each considering the other stale, with nothing anywhere reporting it.
     * Breaking the tie deterministically makes the merge a true join: the result of applying a set of snapshots
     * does not depend on the order they arrive in, which is what the harness proves over random batches.
     */
    public static boolean mergeInto(CompoundTag tag, Map<String, CosmeticDef> defs,
            Map<String, CosmeticEffect> effects, Map<String, CosmeticEffectPool> pools,
            Map<String, CosmeticCrate> crates, Map<String, CosmeticShopListing> shopListings,
            Map<String, Long> stamps)
    {
        if (tag == null)
            throw new IllegalArgumentException("null cosmetic catalogue state");

        Map<String, CosmeticDef> incomingDefs = new HashMap<>();
        ListTag cosmeticList = tag.getList("cosmetics", Tag.TAG_COMPOUND);
        for (int i = 0; i < cosmeticList.size(); i++)
        {
            CosmeticDef def = CosmeticDef.fromNbt(cosmeticList.getCompound(i));
            if (def.id != null && !def.id.isBlank())
                incomingDefs.put(def.id, def);
        }
        Map<String, CosmeticEffect> incomingEffects = new HashMap<>();
        ListTag effectList = tag.getList("effects", Tag.TAG_COMPOUND);
        for (int i = 0; i < effectList.size(); i++)
        {
            CosmeticEffect fx = CosmeticEffect.fromNbt(effectList.getCompound(i));
            if (fx.id != null && !fx.id.isBlank())
                incomingEffects.put(fx.id, fx);
        }
        Map<String, CosmeticEffectPool> incomingPools = new HashMap<>();
        ListTag poolList = tag.getList("pools", Tag.TAG_COMPOUND);
        for (int i = 0; i < poolList.size(); i++)
        {
            CosmeticEffectPool pool = CosmeticEffectPool.fromNbt(poolList.getCompound(i));
            if (pool.id != null && !pool.id.isBlank())
                incomingPools.put(pool.id, pool);
        }
        Map<String, CosmeticCrate> incomingCrates = new HashMap<>();
        ListTag crateList = tag.getList("crates", Tag.TAG_COMPOUND);
        for (int i = 0; i < crateList.size(); i++)
        {
            CosmeticCrate crate = CosmeticCrate.fromNbt(crateList.getCompound(i));
            if (crate.crateName != null && !crate.crateName.isBlank())
                incomingCrates.put(crate.crateName, crate);
        }
        Map<String, CosmeticShopListing> incomingListings = new HashMap<>();
        ListTag shopList = tag.getList("shopListings", Tag.TAG_COMPOUND);
        for (int i = 0; i < shopList.size(); i++)
        {
            CosmeticShopListing listing = CosmeticShopListing.fromNbt(shopList.getCompound(i));
            if (listing.id != null && !listing.id.isBlank())
                incomingListings.put(listing.id, listing);
        }

        boolean changed = false;
        CompoundTag stampTag = tag.getCompound("stamps");
        for (String rawKey : stampTag.getAllKeys())
        {
            // An older sibling shard sends unprefixed keys, which are cosmetics. Folded here rather than in a
            // migration pass so a mixed-version pair converges with no operator action.
            String key = namespaced(rawKey);
            long incomingStamp = stampTag.getLong(rawKey);
            long localStamp = stamps.getOrDefault(key, 0L);
            if (incomingStamp < localStamp)
                continue;
            boolean tie = incomingStamp == localStamp;
            if (key.startsWith(NS_COSMETIC))
            {
                String id = key.substring(NS_COSMETIC.length());
                CosmeticDef def = incomingDefs.get(id);
                CosmeticDef local = defs.get(id);
                if (tie && !takeAtEqualStamp(def == null ? null : def.toNbt(),
                        local == null ? null : local.toNbt()))
                    continue;
                if (def != null)
                {
                    def.id = id; // key is authoritative, as on load
                    defs.put(id, def.normalise());
                }
                else
                {
                    defs.remove(id); // tombstone: a newer stamp with no record is a deletion
                }
            }
            else if (key.startsWith(NS_EFFECT))
            {
                String id = key.substring(NS_EFFECT.length());
                CosmeticEffect fx = incomingEffects.get(id);
                CosmeticEffect local = effects.get(id);
                if (tie && !takeAtEqualStamp(fx == null ? null : fx.toNbt(),
                        local == null ? null : local.toNbt()))
                    continue;
                if (fx != null)
                {
                    fx.id = id;
                    effects.put(id, fx.normalise());
                }
                else
                {
                    effects.remove(id);
                }
            }
            else if (key.startsWith(NS_POOL))
            {
                String id = key.substring(NS_POOL.length());
                CosmeticEffectPool pool = incomingPools.get(id);
                CosmeticEffectPool local = pools.get(id);
                if (tie && !takeAtEqualStamp(pool == null ? null : pool.toNbt(),
                        local == null ? null : local.toNbt()))
                    continue;
                if (pool != null)
                {
                    pool.id = id;
                    pools.put(id, pool.normalise());
                }
                else
                {
                    pools.remove(id);
                }
            }
            else if (key.startsWith(NS_CRATE))
            {
                String id = key.substring(NS_CRATE.length());
                CosmeticCrate crate = incomingCrates.get(id);
                CosmeticCrate local = crates.get(id);
                if (tie && !takeAtEqualStamp(crate == null ? null : crate.toNbt(),
                        local == null ? null : local.toNbt()))
                    continue;
                if (crate != null)
                {
                    crate.crateName = id;
                    crates.put(id, crate.normalise());
                }
                else
                {
                    crates.remove(id);
                }
            }
            else if (key.startsWith(NS_SHOP))
            {
                String id = key.substring(NS_SHOP.length());
                CosmeticShopListing listing = incomingListings.get(id);
                CosmeticShopListing local = shopListings.get(id);
                if (tie && !takeAtEqualStamp(listing == null ? null : listing.toNbt(),
                        local == null ? null : local.toNbt()))
                    continue;
                if (listing != null)
                {
                    listing.id = id;
                    shopListings.put(id, listing.normalise());
                }
                else
                {
                    shopListings.remove(id);
                }
            }
            else
            {
                // A reserved namespace (addon:) from a newer build. See the method note: dropped, deliberately.
                continue;
            }
            stamps.put(key, incomingStamp);
            changed = true;
        }
        return changed;
    }

    /**
     * The tie-break for two records carrying the SAME stamp: the greater serialised form wins.
     *
     * <p>Any total order would do, since the only requirement is that both shards reach the same answer without
     * talking to each other. The serialised form is used because it is the thing the sync already hashes, and
     * because it makes the rule explainable: identical content is not a tie at all (it compares equal, so
     * nothing moves and the merge stays idempotent), and a TOMBSTONE compares as nothing, so a record that
     * still exists beats a deletion stamped in the same millisecond. A deletion an admin actually made a moment
     * later carries its own later stamp and is unaffected.
     *
     * <p>This runs only on an exact millisecond collision, which means never in practice and exactly once in
     * the case that used to leave two shards permanently disagreeing with nothing reporting it.
     */
    private static boolean takeAtEqualStamp(CompoundTag incoming, CompoundTag local)
    {
        String i = incoming == null ? "" : incoming.toString();
        String l = local == null ? "" : local.toString();
        return i.compareTo(l) > 0;
    }

    private static List<String> sortedKeys(Map<String, ?> map)
    {
        List<String> out = new ArrayList<>(map.keySet());
        java.util.Collections.sort(out);
        return out;
    }
}
