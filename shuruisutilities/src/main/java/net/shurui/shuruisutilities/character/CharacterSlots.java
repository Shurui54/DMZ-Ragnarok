package net.shurui.shuruisutilities.character;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import net.shurui.shuruisutilities.wish.SuperPowerWishCommand;
import net.shurui.shuruisutilities.util.PlayerUtil;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.ResourceSyncS2C;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Stats;
import com.dragonminez.server.events.players.StatsEvents;

import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;

/**
 * Multiple character slots per player, stored in persisted NBT so they survive relog. Each slot snapshots the
 * DMZ character, main inventory (+armour/offhand), Curios, xp, food and active effects. Ender chest is shared
 * across slots on purpose.
 *
 * <p>Active slot = the live player; only inactive slots are stored. Switching serialises the live player into
 * the active slot, then loads the target. Each step guarded and the target is only applied after the current
 * one is safely captured, so a failure never silently wipes a character.</p>
 */
public final class CharacterSlots
{
    private CharacterSlots() {}

    private static final String TAG = "su_characters";
    // value-permissions (perm GUI "Values" tab)
    public static final String SLOT_LIMIT_PROP = "su.character.slots";
    public static final String SWAP_COOLDOWN_PROP = "su.character.swap_cooldown"; // seconds, 0 = none, default 600
    public static final String COMBAT_LOCK_PROP = "su.character.combat_lock"; // swap block after combat, 0 = off, default 120
    public static final int HARD_CAP = 20; // safety cap regardless of the perm value

    // the live su_characters compound (created on first use), backed by the player's saved data
    private static CompoundTag root(ServerPlayer p)
    {
        CompoundTag persisted = PlayerUtil.getPersistedTag(p, true);
        if (!persisted.contains(TAG))
            persisted.put(TAG, new CompoundTag());
        return persisted.getCompound(TAG);
    }

    private static ListTag slots(ServerPlayer p)
    {
        return root(p).getList("slots", Tag.TAG_COMPOUND);
    }

    // first-use init: slot 0 is the player's current character
    public static void ensureInit(ServerPlayer p)
    {
        CompoundTag r = root(p);
        if (!r.contains("slots"))
        {
            ListTag slots = new ListTag();
            slots.add(newSlotEntry("Main", capture(p)));
            r.put("slots", slots);
            r.putInt("active", 0);
        }
    }

    private static CompoundTag newSlotEntry(String name, CompoundTag data)
    {
        CompoundTag s = new CompoundTag();
        s.putString("name", name);
        s.put("data", data);
        return s;
    }

    public static int activeIndex(ServerPlayer p)
    {
        ensureInit(p);
        return root(p).getInt("active");
    }

    public static int slotCount(ServerPlayer p)
    {
        ensureInit(p);
        return slots(p).size();
    }

    // active slot's prestige. Stored per slot (sibling of name/data), so it's NOT in the swappable snapshot and
    // stays with each character across switches.
    public static int getActivePrestige(ServerPlayer p)
    {
        ensureInit(p);
        ListTag s = slots(p);
        int active = root(p).getInt("active");
        if (active < 0 || active >= s.size())
            return 0;
        return s.getCompound(active).getInt("prestige");
    }

    // highest prestige across ALL slots (0 if none). Used to seed existing players in the reward ledger
    // ("only going forward" migration).
    public static int highestPrestige(ServerPlayer p)
    {
        ensureInit(p);
        ListTag s = slots(p);
        int max = 0;
        for (int i = 0; i < s.size(); i++)
            max = Math.max(max, s.getCompound(i).getInt("prestige"));
        return max;
    }

    /**
     * {@link #highestPrestige} as a pure read: no first-use init and no write of any kind, 0 when the player has no
     * slot list yet. For the character slot floor (PrestigeLedger.slotsRaw), which runs on every slot count read.
     */
    public static int peekHighestPrestige(ServerPlayer p)
    {
        try
        {
            CompoundTag persisted = p.getPersistentData();
            if (!persisted.contains(TAG, Tag.TAG_COMPOUND))
                return 0;
            ListTag s = persisted.getCompound(TAG).getList("slots", Tag.TAG_COMPOUND);
            int max = 0;
            for (int i = 0; i < s.size(); i++)
                max = Math.max(max, s.getCompound(i).getInt("prestige"));
            return max;
        }
        catch (Throwable t)
        {
            return 0;
        }
    }

    public static void setActivePrestige(ServerPlayer p, int level)
    {
        ensureInit(p);
        CompoundTag r = root(p);
        ListTag s = slots(p);
        int active = r.getInt("active");
        if (active < 0 || active >= s.size())
            return;
        s.getCompound(active).putInt("prestige", Math.max(0, level));
        r.put("slots", s);
    }

    /**
     * A parked character the prestige floor raised, owed the reset that comes with a prestige. Sibling of
     * name/data/prestige, cleared by {@link #switchTo} the moment the character is loaded and reset.
     *
     * <p>A flag rather than editing the slot's stored {@code data}: the reset is DMZ's own
     * {@code resetPlayerProgress} plus a dozen setters on a live {@link StatsData}, and only the active character is
     * live. Rewriting the serialised copy by hand would mean reimplementing that reset against DMZ's NBT format,
     * where a key we got wrong or that DMZ renames later fails silently on characters nobody looks at for months.
     */
    private static final String FLOOR_RESET = "floorReset";

    /**
     * Raise EVERY slot that is below {@code floor} up to it, and report how many were raised.
     *
     * <p>Every slot, not just the active one: "everyone is at least prestige 1" is a statement about the person, and
     * a player who logs in on their second character would otherwise be missed and then be missed again forever,
     * because the floor is only ever checked against whichever character happens to be loaded.
     *
     * <p>Raising is only half of a prestige; the other half is the reset. The caller does that for the ACTIVE
     * character, which is the one that is live. Every other raised slot is flagged {@link #FLOOR_RESET} here and
     * reset by {@link #switchTo} when the player next loads it, so a character parked in slot 2 cannot be used to
     * carry a maxed pre-reset character past the reset.
     *
     * @param markForReset whether raised characters owe a reset. False for a floor that was set before the reset
     *                     behaviour existed, which was only ever a promise to raise a number: see
     *                     {@code PrestigeManager.applyMinimum}.
     */
    public static int raiseAllPrestigeTo(ServerPlayer p, int floor, boolean markForReset)
    {
        if (floor <= 0)
            return 0;
        ensureInit(p);
        CompoundTag r = root(p);
        ListTag s = slots(p);
        int active = r.getInt("active");
        int raised = 0;
        for (int i = 0; i < s.size(); i++)
        {
            CompoundTag slot = s.getCompound(i);
            if (slot.getInt("prestige") < floor)
            {
                slot.putInt("prestige", floor);
                if (markForReset && i != active)
                    slot.putBoolean(FLOOR_RESET, true);
                raised++;
            }
        }
        if (raised > 0)
            r.put("slots", s);
        return raised;
    }

    public static List<String> names(ServerPlayer p)
    {
        ensureInit(p);
        List<String> out = new ArrayList<>();
        ListTag s = slots(p);
        for (int i = 0; i < s.size(); i++)
            out.add(s.getCompound(i).getString("name"));
        return out;
    }

    // sub-compound on each slot entry (sibling of name/data/prestige) holding stat -> value
    private static final String OVERRIDES = "statCapOverrides";

    // Active slot's stat-cap override map ({"STR": 999999, ...}), or empty. Stats /dmzstats set|add pushed above
    // the normal cap; re-asserted after DMZ re-clamps on login/clone/dim-change/swap. Per slot so they travel
    // with the character. Return value is read-only; mutate via setActiveOverride/removeActiveOverride to persist.
    public static CompoundTag activeOverrides(ServerPlayer p)
    {
        ensureInit(p);
        ListTag s = slots(p);
        int active = root(p).getInt("active");
        if (active < 0 || active >= s.size())
            return new CompoundTag();
        CompoundTag slot = s.getCompound(active);
        return slot.contains(OVERRIDES) ? slot.getCompound(OVERRIDES) : new CompoundTag();
    }

    public static void setActiveOverride(ServerPlayer p, String stat, int value)
    {
        ensureInit(p);
        CompoundTag r = root(p);
        ListTag s = slots(p);
        int active = r.getInt("active");
        if (active < 0 || active >= s.size())
            return;
        CompoundTag slot = s.getCompound(active);
        CompoundTag ov = slot.contains(OVERRIDES) ? slot.getCompound(OVERRIDES) : new CompoundTag();
        ov.putInt(stat, value);
        slot.put(OVERRIDES, ov);
        r.put("slots", s);
    }

    public static void removeActiveOverride(ServerPlayer p, String stat)
    {
        ensureInit(p);
        CompoundTag r = root(p);
        ListTag s = slots(p);
        int active = r.getInt("active");
        if (active < 0 || active >= s.size())
            return;
        CompoundTag slot = s.getCompound(active);
        if (!slot.contains(OVERRIDES))
            return;
        CompoundTag ov = slot.getCompound(OVERRIDES);
        if (ov.contains(stat))
        {
            ov.remove(stat);
            slot.put(OVERRIDES, ov);
            r.put("slots", s);
        }
    }

    // switch to slot index; null on success, else an error message
    public static String switchTo(ServerPlayer p, int index)
    {
        ensureInit(p);
        CompoundTag r = root(p);
        ListTag s = slots(p);
        int active = r.getInt("active");
        if (index < 0 || index >= s.size())
            return "No such character slot.";
        if (index == active)
            return "That character is already active.";

        // recent combat blocks the swap (no mid-fight bailouts), configurable per group
        long combatLeft = CharacterCombatTracker.lockRemainingMillis(p, propSeconds(p, COMBAT_LOCK_PROP, 120));
        if (combatLeft > 0)
            return "You were in combat recently. You can swap characters in " + fmtSeconds(combatLeft) + ".";
        // swap cooldown, persisted with the character data so relogging doesn't reset it
        int cooldown = propSeconds(p, SWAP_COOLDOWN_PROP, 600);
        long sinceSwap = System.currentTimeMillis() - r.getLong("lastSwitch");
        if (cooldown > 0 && sinceSwap < cooldown * 1000L)
            return "Character swap on cooldown. Try again in " + fmtSeconds(cooldown * 1000L - sinceSwap) + ".";

        try
        {
            // Drop any dragon balls first: they must not be stored on the outgoing (now inactive) character. Parking
            // a character is the same "this character is going away" moment as a logout, and the logout rule
            // (DragonBallInventoryHandler.onLogout) entombs a quitter's balls so they cannot be hoarded offline.
            // Without this, a player could stash balls on a second character, switch back to the first, and log out
            // safely, keeping the balls in the inactive slot's saved inventory. Eject them here from the LIVE player,
            // BEFORE the snapshot, so the stored character never carries a ball.
            ejectDragonBallsBeforePark(p);
            // capture the live player into the active slot FIRST (never lose the current character)
            s.getCompound(active).put("data", capture(p));
            apply(p, s.getCompound(index).getCompound("data"));
            r.putInt("active", index);
            r.put("slots", s);
            r.putLong("lastSwitch", System.currentTimeMillis());
            // The prestige floor raised this character while it was parked, so it still owes the reset that comes
            // with the prestige. Now that it is the live character, DMZ's own reset can run on it. The flag is
            // cleared only once the reset has actually run: if it could not (no DMZ character loaded), the character
            // stays owed and is caught on the next switch rather than quietly keeping its pre-reset stats.
            if (s.getCompound(index).getBoolean(FLOOR_RESET)
                    && net.shurui.shuruisutilities.prestige.PrestigeManager.wipeActiveCharacter(p))
            {
                s.getCompound(index).remove(FLOOR_RESET);
                r.put("slots", s);
                net.shurui.shuruisutilities.util.output.ChatOutputHandler.chatNotification(p,
                        "This character was prestiged by the server-wide reset, so it starts again from scratch.");
            }
            // apply() -> sd.load() re-clamps every stat to the normal cap; re-assert the now-active character's
            // above-cap overrides so they stick across the swap (active index already updated above)
            StatCapOverrides.reassert(p);
            // fresh start on every swap: full HP, hunger, DMZ ki/stamina
            fullHeal(p);
            // prestige is per-slot, so refresh the SU TP-gain multiplier for DMZ's TP Multiplier tooltip
            net.shurui.shuruisutilities.prestige.PrestigeManager.sendTpMult(p);
            // Record the switch with item counts, so a later "I lost my stuff on the other character" is auditable
            // against exactly what was saved on the outgoing character and loaded on the incoming one.
            int savedStacks = capturedStackCount(s.getCompound(active).getCompound("data"));
            int loadedStacks = liveStackCount(p);
            LoggingHandler.sulog.info("[Characters] {} switched from slot {} to slot {} ('{}'): saved {} item stack(s)"
                    + " on the outgoing character, loaded {} on the incoming one.",
                    p.getGameProfile().getName(), active, index, s.getCompound(index).getString("name"),
                    savedStacks, loadedStacks);
            // A switch is only durable in the local save until the next hop or logout writes the vault. Persist it to
            // the vault NOW so an unclean disconnect or a stale cross-shard write cannot revert it (and, with a later
            // switch on top, permanently lose a character's items). A no-op off the network. See ShardSync.persistNow.
            net.shurui.shuruisutilities.shard.ShardSync.persistNow(p);
            return null;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[Characters] switch failed for " + p.getGameProfile().getName(), t);
            return "Character switch failed. See server log. Your data was not changed.";
        }
    }

    /**
     * Eject the live player's dragon balls before their character is parked, mirroring the logout rule so a
     * character swap cannot be used to hoard balls offline.
     *
     * <p>Reuses {@link net.shurui.shuruisutilities.dragonballbag.DragonBallTotem}'s own extract + entomb path, the
     * exact code {@code DragonBallInventoryHandler.onLogout} runs, so behaviour stays consistent: it pulls every ball
     * out of the main inventory AND the equipped dragon ball bag and builds a totem (falling back to a ground drop,
     * then the player's inventory) at the player's current position. Guarded so a failure never abandons the swap;
     * a ball left behind is a small leak, an aborted swap loses a character.
     */
    private static void ejectDragonBallsBeforePark(ServerPlayer p)
    {
        try
        {
            net.shurui.shuruisutilities.dragonballbag.DragonBallTotem.entomb(
                    p, net.shurui.shuruisutilities.dragonballbag.DragonBallTotem.extract(p));
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Characters] Could not eject dragon balls before parking a character: {}",
                    t.toString());
        }
    }

    // per-player value-permission read as seconds, def when unset/invalid
    private static int propSeconds(ServerPlayer p, String prop, int def)
    {
        try
        {
            Integer v = net.shurui.shuruisutilities.api.APIRegistry.perms.getUserPermissionPropertyInt(
                    net.shurui.shuruisutilities.api.UserIdent.get(p), prop);
            return v == null ? def : Math.max(0, v);
        }
        catch (Throwable t)
        {
            return def;
        }
    }

    private static String fmtSeconds(long millis)
    {
        if (millis < 0)
            millis = 0;
        return net.shurui.shuruisutilities.util.StringUtil.formatDuration(millis / 1000);
    }

    // full restore on swap: vanilla HP + hunger, DMZ ki + stamina, resynced
    private static void fullHeal(ServerPlayer p)
    {
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);
        p.setRemainingFireTicks(0);
        try
        {
            // reconcile MAX_HEALTH with the loaded character's DMZ vitality BEFORE healing, else the attribute
            // still carries the previous character's modifier (DMZ only reapplies on the next PlayerTickEvent,
            // and that path never heals upward). Mirrors DMZ's own respawn sequence.
            StatsEvents.applyHealthBonus(p);
            p.setHealth(p.getMaxHealth());
            StatsData data = p.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
            if (data != null)
            {
                data.getResources().setCurrentEnergy(data.getMaxEnergy());
                data.getResources().setCurrentStamina(data.getMaxStamina());
                NetworkHandler.sendToPlayer(new StatsSyncS2C(p), p);
                NetworkHandler.sendToPlayer(new ResourceSyncS2C(p), p);
            }
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.debug("[Characters] DMZ heal on swap skipped: {}", t.toString());
            p.setHealth(p.getMaxHealth());
        }
    }

    // create a blank character slot and switch to it; null on success, else an error message
    public static String create(ServerPlayer p, String name, int maxSlots)
    {
        ensureInit(p);
        CompoundTag r = root(p);
        ListTag s = slots(p);
        if (s.size() >= Math.min(maxSlots, HARD_CAP))
            return "You've reached your character slot limit (" + Math.min(maxSlots, HARD_CAP) + ").";
        try
        {
            // The current character is about to be parked, so drop its dragon balls the same way a logout or a
            // switch does: an inactive slot must never hold balls (see ejectDragonBallsBeforePark and switchTo).
            ejectDragonBallsBeforePark(p);
            // save the current character, then start a blank one (fresh DMZ character re-prompts creation)
            s.getCompound(r.getInt("active")).put("data", capture(p));
            String slotName = (name == null || name.isBlank()) ? ("Character " + (s.size() + 1)) : name.trim();
            s.add(newSlotEntry(slotName, new CompoundTag())); // empty data => apply() gives a blank character
            int newIndex = s.size() - 1;
            apply(p, new CompoundTag());
            r.putInt("active", newIndex);
            r.put("slots", s);
            return null;
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[Characters] create failed for " + p.getGameProfile().getName(), t);
            return "Creating a character failed. See server log. Your data was not changed.";
        }
    }

    public static String rename(ServerPlayer p, int index, String name)
    {
        ensureInit(p);
        ListTag s = slots(p);
        if (index < 0 || index >= s.size())
            return "No such character slot.";
        if (name == null || name.isBlank())
            return "Name cannot be empty.";
        s.getCompound(index).putString("name", name.trim());
        root(p).put("slots", s);
        return null;
    }

    public static String delete(ServerPlayer p, int index)
    {
        ensureInit(p);
        CompoundTag r = root(p);
        ListTag s = slots(p);
        if (index < 0 || index >= s.size())
            return "No such character slot.";
        if (s.size() <= 1)
            return "You can't delete your only character.";
        if (index == r.getInt("active"))
            return "Switch to another character before deleting this one.";
        s.remove(index);
        int active = r.getInt("active");
        if (active > index)
            r.putInt("active", active - 1); // indices shift down
        r.put("slots", s);
        return null;
    }

    /**
     * Snapshot the ACTIVE character, for anything outside the slot system that needs one.
     *
     * <p>Exposed for the cross-server vault ({@code shard}), so a character handed to another server is exactly
     * the thing a character slot holds. Two definitions of "what a character is" would drift, and the shape that
     * drifted would be the one that only runs when somebody changes servers, which is the worst place to find out.
     */
    public static CompoundTag captureActive(ServerPlayer p)
    {
        return capture(p);
    }

    // Non-empty item stacks in a captured slot snapshot. capture() writes one compound per non-empty stack (main,
    // armour and offhand) into "inv" via Inventory.save, so the list size IS the stack count. For logging only.
    private static int capturedStackCount(CompoundTag data)
    {
        if (data == null || !data.contains("inv"))
            return 0;
        return data.getList("inv", Tag.TAG_COMPOUND).size();
    }

    // Non-empty item stacks the live player currently holds (main, armour, offhand). For logging only.
    private static int liveStackCount(ServerPlayer p)
    {
        int n = 0;
        for (int i = 0; i < p.getInventory().getContainerSize(); i++)
            if (!p.getInventory().getItem(i).isEmpty())
                n++;
        return n;
    }

    /**
     * Clear whichever mastery records the snapshot being loaded does NOT carry.
     *
     * <p>Only the absent ones: where the key IS present DMZ has already loaded it, and FormMasteries.load clears
     * its map before reading, so re-clearing would be pointless and re-clearing AFTER a good load would be
     * destructive. This exists purely to cover DMZ's {@code if (contains(...))} guard, which turns a missing key
     * into "keep whatever the last character had" rather than "this character has none".
     *
     * <p>Guarded per record so one missing DMZ getter cannot stop the others.
     */
    private static void clearAbsentMasteries(StatsData sd, CompoundTag dmz)
    {
        CompoundTag character = dmz.getCompound("Character");
        if (!character.contains("FormMasteries"))
            runQuietly(() -> sd.getCharacter().getFormMasteries().clear());
        if (!character.contains("StackFormMasteries"))
            runQuietly(() -> sd.getCharacter().getStackFormMasteries().clear());
        if (!character.contains("FormsUsedBefore"))
            runQuietly(() -> sd.getCharacter().getFormsUsedBefore().clear());
        if (!character.contains("StackFormsUsedBefore"))
            runQuietly(() -> sd.getCharacter().getStackFormsUsedBefore().clear());
    }

    /** Wipe every mastery record. Used when starting a blank character. */
    /**
     * Wipe the four custom hair objects before a slot is loaded onto the player.
     *
     * <p>This is the mastery bug again, in a different sub-object, and it is worth writing down because the shape
     * will keep recurring. {@code Character.hairBase} and its three super-form siblings are FINAL fields holding
     * live {@code CustomHair} objects that DMZ never replaces. {@code Character.load} does not assign them, it calls
     * {@code hairBase.load(tag.getCompound("HairBase"))} on the object already there, and {@code CustomHair.load}
     * does not clear itself first: it walks the five faces, SKIPS any face the tag does not mention, and for a face
     * it does mention only overwrites as many strands as that tag carries.
     *
     * <p>So loading character B over character A kept every strand B did not explicitly name. A face B never styled
     * kept A's, and a face where A had eight strands and B has three kept A's last five. That is the hair merging:
     * not a render fault, a load that only ever adds.
     *
     * <p>Clearing here rather than reaching into the saved tag, because the tags are DMZ's and the absent-key case
     * is exactly the one that has to be handled. {@code clear()} is public on {@code CustomHair} and resets every
     * face to its base strands. Each getter is guarded on its own: a DMZ shape shift should cost the hair reset, not
     * the whole character swap.
     */
    private static void clearHair(StatsData sd)
    {
        try
        {
            var character = sd.getCharacter();
            runQuietly(() -> character.getHairBase().clear());
            runQuietly(() -> character.getHairSSJ().clear());
            runQuietly(() -> character.getHairSSJ2().clear());
            runQuietly(() -> character.getHairSSJ3().clear());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Characters] Could not clear the previous character's hair: {}", t.toString());
        }
    }

    /**
     * Wipe every transformation-progress trace a fresh character would not have: form and stack-form mastery
     * maps plus the "forms used before" sets. DMZ's own resetPlayerProgress clears the ACTIVE form and the
     * interacted masters but never touches these maps, so a prestige or a slot reuse that relied on
     * resetPlayerProgress alone would leave Oozaru and the Kaioken stack forms sitting at their old mastery.
     * Shared with PrestigeManager.wipeActiveCharacter so the two definitions of "fresh" cannot drift.
     */
    public static void clearMasteries(StatsData sd)
    {
        runQuietly(() -> sd.getCharacter().getFormMasteries().clear());
        runQuietly(() -> sd.getCharacter().getStackFormMasteries().clear());
        runQuietly(() -> sd.getCharacter().getFormsUsedBefore().clear());
        runQuietly(() -> sd.getCharacter().getStackFormsUsedBefore().clear());
    }

    /** Run a step whose failure must not abandon the rest of a swap. */
    private static void runQuietly(Runnable step)
    {
        try
        {
            step.run();
        }
        catch (Throwable ignored)
        {
        }
    }

    /** Apply a snapshot from {@link #captureActive}. Same contract as a slot swap. */
    public static void applyActive(ServerPlayer p, CompoundTag data)
    {
        apply(p, data);
    }

    // snapshot everything a character owns (ender chest excluded, it stays shared)
    private static CompoundTag capture(ServerPlayer p)
    {
        CompoundTag d = new CompoundTag();
        // The DMZ character is the most valuable thing in a snapshot, so it must never be silently OMITTED. The old
        // ifPresent(...) dropped the "dmz" key whenever the capability was absent, and apply() reads a MISSING "dmz"
        // key as "blank, fresh character": it takes the branch that wipes the character, down to
        // getResources().setTrainingPoints(0f). So a silently omitted capture does not lose a little, it serialises a
        // TP=0 character over a good one in the cross-server vault, costing real progression. A partial snapshot is
        // worse than none, so throw instead, mirroring ShardPayload.capture ("A HALF CAPTURED PLAYER MUST NEVER BE
        // SAVED"): the whole capture aborts, the caller declines to save, and the previous vault blob stays intact.
        // Capture the DMZ character if the capability is there. Deliberately a no-op when it is NOT: this runs
        // from ensureInit on login (via highestPrestige) as well as from the cross-shard capture, and at that
        // moment an established player's capability can legitimately read as not-yet-loaded. A previous version
        // threw here to avoid ever writing a blank character, and it refused VALID captures on login instead:
        // logins errored and "[shard] Could not capture <player>" meant their data was not saved. If this needs
        // hardening again, gate it on the CALLER (only the vault path can afford to refuse) and not on a
        // load-state check evaluated at a moment when loading is still in progress.
        p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> d.put("dmz", sd.save()));
        d.put("inv", p.getInventory().save(new ListTag()));
        d.put("curios", saveCurios(p));
        CompoundTag zsoul = ZSoulBridge.export(p); // banked Z-Soul progress (per character, if Raid Bosses is present)
        if (zsoul != null)
            d.put("zsoul", zsoul);
        d.putInt("xpTotal", p.totalExperience);
        d.putInt("xpLevel", p.experienceLevel);
        d.putFloat("xpProgress", p.experienceProgress);
        d.putFloat("health", p.getHealth());
        CompoundTag food = new CompoundTag();
        p.getFoodData().addAdditionalSaveData(food);
        d.put("food", food);
        d.put("effects", saveEffects(p));
        // Super Shenron power wish uses: per character, so each slot has its own three. Written only when non-zero, so
        // a snapshot of a character that never took the wish stays byte-identical.
        int superWishes = p.getPersistentData().getInt(SuperPowerWishCommand.USES_KEY);
        if (superWishes > 0)
            d.putInt("superWishes", superWishes);
        return d;
    }

    // apply a snapshot to the live player; empty snapshot = a blank, fresh character
    private static void apply(ServerPlayer p, CompoundTag d)
    {
        // DMZ character: load the saved data, or reset to a fresh (uncreated) character when blank
        p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> {
            try
            {
                // HAIR DOES NOT CROSS SLOTS EITHER, and for the same reason masteries did not. Cleared BEFORE the
                // load so the incoming character writes onto a blank head rather than onto the last one's.
                clearHair(sd);
                if (d.contains("dmz"))
                {
                    sd.load(d.getCompound("dmz"));
                    // MASTERY DOES NOT CROSS SLOTS. DMZ's Character.load only restores form masteries when the
                    // saved tag actually carries the key:
                    //     if (tag.contains("FormMasteries")) formMasteries.load(...)
                    // and FormMasteries is a FINAL field on a live Character that is never replaced. So a snapshot
                    // without that key does not load empty masteries, it skips the load entirely and leaves the
                    // PREVIOUS character's masteries sitting in the object. Any slot saved before DMZ wrote that
                    // key hits this, which is exactly what "mastery is kept across characters" looks like.
                    // Clearing on the absent-key path makes the swap say what it means: this character has none.
                    clearAbsentMasteries(sd, d.getCompound("dmz"));
                }
                else
                {
                    // blank slot -> a fresh character. resetPlayerProgress is DMZ's full wipe (skills, masters,
                    // techniques, quests, dynamic growth...) but touches many subsystems and can throw mid-swap;
                    // if it does, NOTHING resets and the old MAXED character stays, which reads as "no assignable
                    // stats" (DMZ shows no stat +buttons, can't upgrade). So always follow it with a guaranteed
                    // zeroing via plain setters (can't throw). Clearing the race makes DMZ re-prompt creation.
                    // Each step guarded so one failure doesn't block the rest.
                    try { sd.resetPlayerProgress(p, null, false, false); }
                    catch (Throwable t) { LoggingHandler.sulog.error("[Characters] resetPlayerProgress failed; zeroing manually", t); }

                    Stats st = sd.getStats();
                    st.setStrength(0);
                    st.setStrikePower(0);
                    st.setResistance(0);
                    st.setVitality(0);
                    st.setKiPower(0);
                    st.setEnergy(0);
                    sd.getResources().setTrainingPoints(0f);
                    sd.getResources().setPendingAttributePoints(0);
                    try { sd.getBonusStats().clearAllStats(); } catch (Throwable ignored) {}
                    try
                    {
                        sd.getCharacter().setRace("");
                        sd.getCharacter().setHasPreviousFormRecord(false);
                        sd.getCharacter().setHasPreviousStackFormRecord(false);
                    }
                    catch (Throwable ignored) {}
                    // Mastery is part of "a fresh character" and was missing from this guaranteed-zeroing block.
                    // It matters most exactly when it was missing: the block exists because resetPlayerProgress
                    // can throw mid-swap, and if it does, nothing else clears masteries, so the new character
                    // starts with the old one's form mastery already earned.
                    clearMasteries(sd);
                }
            }
            catch (Throwable t)
            {
                LoggingHandler.sulog.error("[Characters] DMZ load/reset failed", t);
            }
        });

        p.getInventory().clearContent();
        if (d.contains("inv"))
            p.getInventory().load(d.getList("inv", Tag.TAG_COMPOUND));

        clearCurios(p);
        loadCurios(p, d.getList("curios", Tag.TAG_COMPOUND));

        // banked Z-Soul progress (per character); absent tag clears it so a new character starts at 0
        ZSoulBridge.load(p, d.contains("zsoul") ? d.getCompound("zsoul") : new CompoundTag());

        p.totalExperience = d.getInt("xpTotal");
        p.experienceLevel = d.getInt("xpLevel");
        p.experienceProgress = d.getFloat("xpProgress");
        p.experienceProgress = Math.max(0.0f, Math.min(1.0f, p.experienceProgress));

        if (d.contains("food"))
            p.getFoodData().readAdditionalSaveData(d.getCompound("food"));

        p.removeAllEffects();
        loadEffects(p, d.getList("effects", Tag.TAG_COMPOUND));

        // The incoming character's own power wish count (absent = none used), never the previous slot's.
        if (d.contains("superWishes"))
            p.getPersistentData().putInt(SuperPowerWishCommand.USES_KEY, d.getInt("superWishes"));
        else
            p.getPersistentData().remove(SuperPowerWishCommand.USES_KEY);

        // HEALTH. The clamp is to the new character's max, which DMZ drives from vitality, so MAX_HEALTH has to
        // be reconciled BEFORE the clamp or the clamp is against the wrong number.
        //
        // This is the same reconciliation fullHeal() does and for the same stated reason: DMZ only reapplies the
        // health bonus on the next PlayerTickEvent, and that path never heals upward. Without it, a player
        // arriving from another server was clamped to whatever stale MAX_HEALTH the destination's attribute
        // happened to carry (the vanilla 20 on a first visit), and DMZ then raised the max on the following
        // tick. The result was a character sitting at a sliver of a huge bar, which reads as near death, which
        // is what was CONSUMING ZENKAI on every server hop. Losing the health value was the visible half; the
        // zenkai charge was the expensive half.
        if (d.contains("health"))
        {
            try
            {
                StatsEvents.applyHealthBonus(p);
            }
            catch (Throwable t)
            {
                // Better to clamp against a stale max than to abort the whole restore over it.
                LoggingHandler.sulog.debug("[Characters] Could not reconcile max health before restoring it: {}",
                        t.toString());
            }
            float hp = d.getFloat("health");
            if (hp > 0)
                p.setHealth(Math.min(hp, p.getMaxHealth()));
        }

        // Blank the CLIENT's hair before the DMZ sync below re-loads the character onto it. clearHair() above only
        // fixed the server copy; the StatsSyncS2C that follows runs DMZ's additive Character.load on the client's
        // still-present previous character, which is where the hair visibly combines (see PacketClearClientHair).
        // Sent FIRST so the client processes the clear before that load: both ride the one connection and enqueue to
        // the client main thread in arrival order.
        try
        {
            net.shurui.shuruisutilities.commons.network.NetworkUtils.INSTANCE.send(
                    net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> p),
                    new PacketClearClientHair());
        }
        catch (Throwable ignored)
        {
        }

        // resync everything to the client
        try
        {
            if (NetworkHandler.INSTANCE != null)
            {
                NetworkHandler.sendToPlayer(new StatsSyncS2C(p), p);
                NetworkHandler.sendToPlayer(new ResourceSyncS2C(p), p);
            }
        }
        catch (Throwable ignored)
        {
        }
        p.inventoryMenu.broadcastChanges();
        p.containerMenu.broadcastChanges();
    }

    private static ListTag saveEffects(ServerPlayer p)
    {
        ListTag list = new ListTag();
        for (MobEffectInstance e : p.getActiveEffects())
            list.add(e.save(new CompoundTag()));
        return list;
    }

    private static void loadEffects(ServerPlayer p, ListTag list)
    {
        for (int i = 0; i < list.size(); i++)
        {
            MobEffectInstance e = MobEffectInstance.load(list.getCompound(i));
            if (e != null)
                p.addEffect(e);
        }
    }

    private static IItemHandlerModifiable curios(ServerPlayer p)
    {
        return CuriosApi.getCuriosInventory(p).map(ICuriosItemHandler::getEquippedCurios).orElse(null);
    }

    private static ListTag saveCurios(ServerPlayer p)
    {
        ListTag list = new ListTag();
        IItemHandlerModifiable c = curios(p);
        if (c != null)
            for (int i = 0; i < c.getSlots(); i++)
            {
                ItemStack s = c.getStackInSlot(i);
                if (!s.isEmpty())
                {
                    CompoundTag t = s.save(new CompoundTag());
                    t.putInt("Slot", i);
                    list.add(t);
                }
            }
        return list;
    }

    private static void clearCurios(ServerPlayer p)
    {
        IItemHandlerModifiable c = curios(p);
        if (c != null)
            for (int i = 0; i < c.getSlots(); i++)
                c.setStackInSlot(i, ItemStack.EMPTY);
    }

    private static void loadCurios(ServerPlayer p, ListTag list)
    {
        IItemHandlerModifiable c = curios(p);
        if (c == null)
            return;
        for (int i = 0; i < list.size(); i++)
        {
            CompoundTag t = list.getCompound(i);
            int slot = t.getInt("Slot");
            if (slot >= 0 && slot < c.getSlots())
                // Player property: normalize a pre-merge item id in a stored character-inventory stack before decoding.
                c.setStackInSlot(slot, ItemStack.of(
                        net.shurui.shuruisutilities.ragnarok.LegacyIds.normalizeItemTag(t)));
        }
    }
}
