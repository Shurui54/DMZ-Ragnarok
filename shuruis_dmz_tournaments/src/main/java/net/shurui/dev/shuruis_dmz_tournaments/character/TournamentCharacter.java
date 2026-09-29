package net.shurui.dev.shuruis_dmz_tournaments.character;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandlerModifiable;

import com.dragonminez.common.init.MainEffects;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;
import com.dragonminez.common.stats.character.Stats;
import com.dragonminez.common.stats.extras.DynamicGrowthStat;
import com.dragonminez.common.util.TransformationsHelper;
import com.dragonminez.server.events.players.StatsEvents;

import net.shurui.dev.shuruis_dmz_tournaments.Config;
import net.shurui.dev.shuruis_dmz_tournaments.dmz.CuriosBridge;
import net.shurui.dev.shuruis_dmz_tournaments.dmz.DmzHooks;

/**
 * Dedicated tournament-only character, kept in a SIBLING store from any SU character slot so it can never be
 * selected outside a tournament nor show in the character GUI. Lives in the player's Forge persistent NBT
 * (playerdata/&lt;uuid&gt;.dat) under {@value #STORE}, holding:
 *
 * <ul>
 *   <li>{@code template} - the frozen fighter (built once, reloaded verbatim on every entry, so live mutations are
 *       never written back).</li>
 *   <li>{@code restore} - snapshot of the player's REAL character taken at entry, loaded back on exit.</li>
 *   <li>{@code active}/{@code defId} - persistent "in a tournament character" marker, for recovering a stranded
 *       player on login after a disconnect or restart.</li>
 *   <li>{@code loadout} - chosen ki/strike attack ids (persisted across tournaments).</li>
 * </ul>
 *
 * <p>The snapshot mirrors SU's {@code CharacterSlots}: DMZ stats, main inventory, Curios, xp, food, effects,
 * health. Banked Z-Soul progress lives in a separate per-uuid store, untouched by the swap, so it survives.</p>
 */
public final class TournamentCharacter {
    private TournamentCharacter() {}

    static final String STORE = "su_tournament_char";

    // players in the full-creation sandbox (in-memory only). The persistent active/creating markers are the real
    // recovery record; this set just drives the per-tick completion watch cheaply.
    private static final Set<UUID> IN_CREATION = ConcurrentHashMap.newKeySet();

    /**
     * Skills every fighter is handed, by DMZ skill id. This is the FLOOR, not the whole set: Shurui's 2026-08-30
     * call is that a fighter has every skill at its ceiling, so a match turns on build and play, not on which
     * skills someone bought. The full set is enumerated from DMZ's registry at grant time (see
     * {@link #grantFighterKit}); this list stays because the four functional unlocks make a character work at all
     * ({@code kicontrol} gates every ki action, {@code fly} leaving the ground, {@code kisense} and
     * {@code kimanipulation} sensing and ki-weapon play). Ceilings are read back from DMZ, not hardcoded, because
     * {@code calculateMaxLevel} is race and config dependent.
     *
     * <p>Ids are DMZ's own, lowercase, as {@code Skills} stores them. Verified against DMZ 2.1.3's
     * {@code skill.dragonminez.*} lang keys; do not guess a new one, look it up.
     */
    private static final String[] FIGHTER_SKILL_FLOOR = {
        "kicontrol", "fly", "kisense", "kimanipulation",
        "potentialunlock", "meditation", "sprint", "jump",
    };

    /**
     * Grant the fighter skill set and the flat stat-allocation pool, TP zeroed. Both are "what a blank fighter is
     * given" and both must survive DMZ's create handler re-seeding a character, hence the second call from
     * {@link #reassertConstraints}. TP is ZERO deliberately: DMZ's {@code IncreaseStatC2S} spends pending attribute
     * points first and only falls through to TP, so any leftover TP would buy stat past the allocation cap. Zero
     * makes the allocation a hard ceiling.
     */
    private static void grantFighterKit(StatsData sd) {
        sd.getResources().setTrainingPoints(0.0f);
        sd.getResources().setPendingAttributePoints(Config.TOURNAMENT_STAT_ALLOCATIONS.get());
        try {
            var skills = sd.getSkills();

            // EVERY skill DMZ knows about, at its ceiling. Enumerated from DMZ's registry, not a list here, because a
            // hardcoded list silently misses any skill DMZ or a datapack later adds. getSkillsConfig().getSkills() is
            // the same map DMZ's /skills command uses.
            java.util.Set<String> ids = new java.util.LinkedHashSet<>(java.util.Arrays.asList(FIGHTER_SKILL_FLOOR));
            String race = null;
            try {
                race = sd.getCharacter().getRace();
            } catch (Throwable ignored) {
                // no race yet on a half-built character: skip the race filter below.
            }
            try {
                var config = com.dragonminez.common.config.ConfigManager.getSkillsConfig();
                for (String id : config.getSkills().keySet()) {
                    // skip a race-restricted skill for a fighter of another race. isSkillAllowedForRace is true when a
                    // skill names no races, so an unrestricted skill is never dropped.
                    if (race != null && !race.isEmpty() && !config.isSkillAllowedForRace(id, race)) {
                        continue;
                    }
                    ids.add(id);
                }
            } catch (Throwable t) {
                // DMZ config shape shifted: fall back to the floor, still a working fighter.
                LOG.warn("[TournamentCharacter] could not enumerate DMZ skills, granting the floor set only: {}",
                        t.toString());
            }

            for (String id : ids) {
                // Level first so the skill EXISTS: setSkillLevel creates the entry (computing its max) for a missing
                // key, and getMaxSkillLevel on an absent key answers 0.
                skills.setSkillLevel(id, 1);
                int max = skills.getMaxSkillLevel(id);
                if (max > 1) {
                    skills.setSkillLevel(id, max);
                } else if (max <= 0) {
                    // no ceiling for this character means the skill does not apply; take the level-1 entry back off.
                    skills.removeSkill(id);
                }
            }
        } catch (Throwable t) {
            // a DMZ skill-API shift must not stop the build; the fighter is merely unskilled.
            LOG.error("[TournamentCharacter] granting fighter skills failed", t);
        }

        // Max mastery on every form the fighter can use. DMZ normally accrues mastery over time (per hit and a
        // passive trickle), so a form is weak and drains fast until it is ground up; the 2026-08-30 call is that a
        // tournament turns on build and play, not on grind, so a fighter is handed full mastery up front. Kaioken
        // is a stack form and was the visible miss: granted as a skill but left at zero mastery. gainMastery clamps
        // to each form's configured maxMastery and writes the correct map (stack vs regular) itself, so a deliberate
        // overshoot simply pins every form at its ceiling. A no-op on a blank (raceless) character.
        grantMaxMastery(sd);
    }

    /** Very large so {@code gainMastery} clamps every form to its own configured {@code maxMastery} ceiling. */
    private static final double MASTERY_GRANT = 1.0e9;

    /**
     * Give this character max mastery on every regular form its race can reach and every stack form (kaioken, ultra
     * instinct, ultra ego, ...). Uses DMZ's own {@link Character#gainMastery(String, String, double)}, which resolves
     * the form's configured max and the correct mastery map (regular vs stack) internally, so this never needs to
     * know either. Guarded per form so one bad entry cannot stop the rest, and skipped entirely when no race is set
     * (the blank sandbox before DMZ's creation screen picks one), because regular forms are looked up by race.
     */
    private static void grantMaxMastery(StatsData sd) {
        final Character ch;
        try {
            ch = sd.getCharacter();
        } catch (Throwable t) {
            return;
        }
        if (ch == null) {
            return;
        }
        try {
            String race = null;
            try {
                race = ch.getRace();
            } catch (Throwable ignored) {
            }
            // regular forms are race specific: skip when the race is not chosen yet.
            if (race != null && !race.isEmpty()) {
                var groups = com.dragonminez.common.config.ConfigManager.getAllFormsForRace(race);
                if (groups != null) {
                    grantMasteryForGroups(ch, groups);
                }
            }
            // stack forms are race agnostic (kaioken and friends).
            grantMasteryForGroups(ch, com.dragonminez.common.config.ConfigManager.getAllStackForms());
        } catch (Throwable t) {
            LOG.warn("[TournamentCharacter] granting max form mastery failed: {}", t.toString());
        }
    }

    private static void grantMasteryForGroups(Character ch,
            java.util.Map<String, com.dragonminez.common.config.FormConfig> groups) {
        if (groups == null) {
            return;
        }
        for (var entry : groups.entrySet()) {
            final String group = entry.getKey();
            com.dragonminez.common.config.FormConfig cfg = entry.getValue();
            if (group == null || cfg == null || cfg.getForms() == null) {
                continue;
            }
            for (String form : cfg.getForms().keySet()) {
                if (form == null) {
                    continue;
                }
                final String formId = form;
                quietly(() -> ch.gainMastery(group, formId, MASTERY_GRANT));
            }
        }
    }

    /**
     * Reset the four custom hair objects, so a character loaded after this writes onto a blank head. Deliberately a
     * copy of SU {@code CharacterSlots}' helper, not a shared one: this addon does not import shuruisutilities
     * (verified: zero imports), and adding that dependency for eight lines would couple it to the admin suite for
     * life. Each getter is guarded alone, so a DMZ shape shift costs the hair reset, not the swap around it.
     */
    private static void clearHair(StatsData sd) {
        try {
            var character = sd.getCharacter();
            quietly(() -> character.getHairBase().clear());
            quietly(() -> character.getHairSSJ().clear());
            quietly(() -> character.getHairSSJ2().clear());
            quietly(() -> character.getHairSSJ3().clear());
        } catch (Throwable t) {
            LOG.warn("[TournamentCharacter] Could not clear the previous character's hair: {}", t.toString());
        }
    }

    private static void quietly(Runnable step) {
        try {
            step.run();
        } catch (Throwable ignored) {
        }
    }

    /** Stat allocations this player has not spent yet. 0 means they are done allocating. */
    private static int unspentAllocations(ServerPlayer p) {
        return p.getCapability(StatsCapability.INSTANCE)
                .map(sd -> sd.getResources().getPendingAttributePoints())
                .orElse(0);
    }

    /**
     * The store, in the player's PERSISTED sub-tag. It used to sit at the ROOT of {@code getPersistentData()}, which
     * is why players rebuilt a tournament character before every tournament: Forge copies only the
     * {@code PlayerPersisted} sub-tag onto the respawn clone, so a root write is thrown away on death, and a
     * tournament is where people die. The template, loadout and markers were gone by the next one and {@code enter}
     * rebuilt from scratch. The dangerous half was {@code restore} and {@code active} going too: a player who died
     * mid-match had no record of the swap, so {@code exit} no-opped and left them wearing the fighter. A store
     * written at the root by an older build is moved across on first touch and the root copy removed, so it is a
     * one-time move, not two stores drifting.
     */
    private static CompoundTag store(ServerPlayer p, boolean create) {
        CompoundTag root = p.getPersistentData();
        CompoundTag persisted = root.getCompound(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG);
        if (!root.contains(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG)) {
            // getCompound returns a fresh detached tag for an absent key, so attach it or every write below goes
            // into an object nothing holds.
            root.put(net.minecraft.world.entity.player.Player.PERSISTED_NBT_TAG, persisted);
        }

        if (root.contains(STORE)) {
            if (!persisted.contains(STORE)) {
                persisted.put(STORE, root.getCompound(STORE));
            }
            root.remove(STORE);
        }

        if (!persisted.contains(STORE)) {
            if (!create) return null;
            persisted.put(STORE, new CompoundTag());
        }
        return persisted.getCompound(STORE);
    }

    public static boolean isActive(ServerPlayer p) {
        CompoundTag s = store(p, false);
        return s != null && s.getBoolean("active");
    }

    public static boolean hasTemplate(ServerPlayer p) {
        CompoundTag s = store(p, false);
        return s != null && s.contains("template");
    }

    // Whether the tournament character feature is on; the SU slots GUI hides its tournament row when false. Gated
    // by the addon's own config value now that the operator switchboard is gone.
    public static boolean featureEnabled() {
        return Config.TOURNAMENT_CHARS_ENABLED.get();
    }

    // The tournament this player is currently swapped into (for login recovery diagnostics), or "".
    public static String activeDefId(ServerPlayer p) {
        CompoundTag s = store(p, false);
        return s == null ? "" : s.getString("defId");
    }

    public static List<String> getLoadout(ServerPlayer p) {
        CompoundTag s = store(p, false);
        if (s != null && s.contains("loadout")) {
            ListTag list = s.getList("loadout", Tag.TAG_STRING);
            List<String> out = new ArrayList<>();
            for (int i = 0; i < list.size(); i++) {
                String id = list.getString(i);
                if (TournamentAttacks.isKnownAttack(id)) out.add(id);
            }
            if (!out.isEmpty()) return out;
        }
        return TournamentAttacks.defaultLoadout();
    }

    public static void setLoadout(ServerPlayer p, List<String> ids) {
        CompoundTag s = store(p, true);
        ListTag list = new ListTag();
        int count = 0;
        for (String id : ids) {
            if (count >= TournamentAttacks.EQUIP_SLOTS) break;
            if (TournamentAttacks.isKnownAttack(id)) {
                list.add(StringTag.valueOf(id));
                count++;
            }
        }
        s.put("loadout", list);
    }

    /**
     * Persist a loadout and make it take effect. Shared by the forced first-entry GUI and the "create in advance"
     * button. If the player is currently swapped in we cannot round-trip their real character safely, so just flag
     * the template stale and let the next {@link #enter} rebuild it; otherwise (re)build now.
     */
    public static void setLoadoutAndBuild(ServerPlayer p, List<String> ids) {
        setLoadout(p, ids);
        if (isActive(p)) {
            store(p, true).putBoolean("templateStale", true);
        } else {
            buildTemplateNow(p);
        }
    }

    /**
     * (Re)build the frozen template WITHOUT changing the character the player is playing. Only valid when NOT in a
     * tournament: snapshots the real character, mutates the live player into the fighter to capture the template,
     * then restores verbatim. Lets a tournament character be created from the slots GUI while the player walks
     * around as their real self.
     */
    public static void buildTemplateNow(ServerPlayer p) {
        if (isActive(p)) return;
        CompoundTag s = store(p, true);
        CompoundTag real = capture(p);
        try {
            buildTemplateInPlace(p);
            s.put("template", capture(p));
            s.putBoolean("templateStale", false);
        } catch (Throwable t) {
            LOG.error("[TournamentCharacter] buildTemplateNow failed for " + p.getGameProfile().getName(), t);
        } finally {
            // always put the real character back, even if the build threw, so the player is never stranded
            apply(p, real);
            reconcileHealth(p);
        }
    }

    /**
     * Open the attack picker on this player's client (mid-match first-entry fallback). Server-authoritative: the
     * client only edits a selection, the template is only ever built server-side from a validated save.
     */
    public static void openCreationGui(ServerPlayer p) {
        net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.openCreation(p);
    }

    /** True while the player is in the ahead-of-time full-creation sandbox (blank DMZ character being built). */
    public static boolean isCreating(ServerPlayer p) {
        CompoundTag s = store(p, false);
        return s != null && s.getBoolean("creating");
    }

    /**
     * Full creation, mirroring a normal SU slot: the player is swapped into a BLANK DMZ character so DMZ's creation
     * flow (race + appearance) fires client-side, handed the fixed TP allowance with growth frozen so allocation
     * goes through DMZ's stats screen. When they finish and pick four ki + four strike moves, the result is frozen
     * as the template and the real character restored. The "create in advance" path for the SU slots GUI; it can NOT
     * run from in-match entry because DMZ's creation screen is modal and drives the live player. Refused while
     * already swapped (would strand them); if already in the sandbox, just re-offers the move-picker prompt.
     */
    public static void beginCreation(ServerPlayer p) {
        if (isActive(p)) {
            if (isCreating(p)) {
                promptMoves(p); // already building one: re-offer the finish step instead of nesting a second swap
            } else {
                p.sendSystemMessage(Component.translatable(
                        "message.dmz_ragnarok.tournaments.char.create_in_match").withStyle(ChatFormatting.RED));
            }
            return;
        }
        CompoundTag s = store(p, true);
        try {
            s.put("restore", capture(p));
            s.putBoolean("active", true);
            s.putBoolean("creating", true);
            s.putBoolean("awaitingLoadout", false);
            // a fresh sandbox re-grants the kit once DMZ finishes its own creation screen
            s.putBoolean("kitGranted", false);
            s.putString("defId", "");
            resetToBlank(p); // clears race + hasCreatedCharacter so DMZ force-opens its own full creation screen
            reconcileHealth(p);
            DmzHooks.syncStats(p);
            DmzHooks.syncResources(p);
            IN_CREATION.add(p.getUUID());
            p.sendSystemMessage(Component.translatable(
                    "message.dmz_ragnarok.tournaments.char.creation_started").withStyle(ChatFormatting.YELLOW));
        } catch (Throwable t) {
            // never strand: put the real character straight back and drop the markers. Only clear them and remove
            // "restore" if the real DMZ character was actually restored; otherwise keep them so login recovery
            // retries, rather than deleting the only copy of the real character (bug 1009).
            LOG.error("[TournamentCharacter] beginCreation failed for " + p.getGameProfile().getName(), t);
            boolean restored = s.contains("restore") && apply(p, s.getCompound("restore"));
            if (restored) {
                s.putBoolean("active", false);
                s.putBoolean("creating", false);
                s.remove("restore");
                IN_CREATION.remove(p.getUUID());
            }
        }
    }

    /**
     * Per-tick watch over sandbox players. Once DMZ reports the blank character created (race + appearance chosen),
     * re-assert the TP allowance + growth freeze (DMZ's create handler may re-seed resources) and offer the move
     * picker, whose save finalizes the template.
     */
    public static void tickCreations(MinecraftServer server) {
        if (server == null || IN_CREATION.isEmpty()) return;
        for (UUID id : new ArrayList<>(IN_CREATION)) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) { IN_CREATION.remove(id); continue; } // offline: login recovery swaps them back
            CompoundTag s = store(p, false);
            if (s == null || !s.getBoolean("creating")) { IN_CREATION.remove(id); continue; }
            if (s.getBoolean("awaitingLoadout")) continue;       // already past creation, waiting on the move pick
            if (DmzHooks.hasCreatedCharacter(p)) {
                if (!s.getBoolean("kitGranted")) {
                    reassertConstraints(p);
                    s.putBoolean("kitGranted", true);
                    // Tell them ONCE to spend the allocation pool. This used to fire from the wait loop below every
                    // few seconds for as long as any point was unspent, which read as chat spam during creation.
                    // Sent here, on the single kit-granted transition, it appears exactly once; promptMoves below is
                    // likewise sent once, when allocations reach zero.
                    if (unspentAllocations(p) > 0) {
                        promptAllocations(p);
                    }
                }
                // STATS FIRST: no move picker until every allocation is spent, making the order a rule not a
                // suggestion. reassertConstraints is NOT re-run here: it zeroes the base stats and re-grants the
                // pool, so running it every tick would wipe each allocation as it was made and never reach zero.
                // We simply wait without re-messaging (the reminder above was already sent once).
                if (unspentAllocations(p) > 0) {
                    continue;
                }
                s.putBoolean("awaitingLoadout", true);
                promptMoves(p);
            }
        }
    }

    // chat prompt (not an auto modal) so the player can still allocate TP through DMZ's stats screen first. Sent
    // ONCE, when the kit is granted (see tickCreations), not on a repeating cadence: repeating it every few seconds
    // was the creation chat spam.
    private static void promptAllocations(ServerPlayer p) {
        p.sendSystemMessage(Component.translatable(
                "message.dmz_ragnarok.tournaments.char.spend_allocations", unspentAllocations(p))
                .withStyle(ChatFormatting.YELLOW));
    }

    private static void promptMoves(ServerPlayer p) {
        Component msg = Component.translatable("message.dmz_ragnarok.tournaments.char.pick_moves")
                .withStyle(st -> st.withColor(ChatFormatting.GOLD)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/rg tourney loadout")));
        p.sendSystemMessage(msg);
    }

    // wipe the live player to a fresh uncreated DMZ character (mirrors SU CharacterSlots' blank path), then seed the
    // fixed TP pool with growth frozen. Clearing hasCreatedCharacter makes DMZ prompt creation.
    private static void resetToBlank(ServerPlayer p) {
        p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> {
            try {
                sd.resetPlayerProgress(p, null, false, false);
            } catch (Throwable t) {
                LOG.error("[TournamentCharacter] resetPlayerProgress failed; zeroing manually", t);
            }
            Stats st = sd.getStats();
            st.setStrength(0);
            st.setStrikePower(0);
            st.setResistance(0);
            st.setVitality(0);
            st.setKiPower(0);
            st.setEnergy(0);
            grantFighterKit(sd);
            try {
                for (DynamicGrowthStat gs : DynamicGrowthStat.values()) {
                    sd.getDynamicGrowth().setGrowthEnabled(gs, false);
                }
            } catch (Throwable ignored) {
            }
            try {
                sd.getStatus().setHasCreatedCharacter(false);
                sd.getCharacter().setRace("");
                sd.getCharacter().setHasPreviousFormRecord(false);
                sd.getCharacter().setHasPreviousStackFormRecord(false);
            } catch (Throwable ignored) {
            }
        });
        p.getInventory().clearContent();
        clearCurios(p);
        p.removeAllEffects();
        p.totalExperience = 0;
        p.experienceLevel = 0;
        p.experienceProgress = 0f;
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);
    }

    // re-apply constraints after DMZ's create handler: zero the base stats (so the pool is spent from scratch
    // through DMZ's stats screen), re-grant the pool, re-freeze dynamic growth.
    private static void reassertConstraints(ServerPlayer p) {
        p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> {
            Stats st = sd.getStats();
            st.setStrength(0);
            st.setStrikePower(0);
            st.setResistance(0);
            st.setVitality(0);
            st.setKiPower(0);
            st.setEnergy(0);
            grantFighterKit(sd);
            try {
                for (DynamicGrowthStat gs : DynamicGrowthStat.values()) {
                    sd.getDynamicGrowth().setGrowthEnabled(gs, false);
                }
            } catch (Throwable ignored) {
            }
        });
        DmzHooks.syncStats(p);
        DmzHooks.syncResources(p);
    }

    /**
     * Finish the sandbox: bake the chosen moves onto the new character, strip it to a bare fighter (keeping race,
     * appearance, allocated stats), freeze as the template, restore the real character. Falls back to
     * {@link #setLoadoutAndBuild} if the player is not actually in a sandbox.
     */
    public static void finalizeCreation(ServerPlayer p, List<String> loadout) {
        CompoundTag s = store(p, false);
        if (s == null || !s.getBoolean("creating")) {
            setLoadoutAndBuild(p, loadout);
            return;
        }
        // Both gates enforced HERE, the server-side chokepoint. The tick loop withholds the picker until allocations
        // are spent, but the picker is reachable by command, so a client-only order would be advice not a rule.
        // Refusing leaves the player in the sandbox intact; they finish and click save again. NOT enforced in exit():
        // that also runs on disconnect, login recovery and match end, where refusing would strand the player.
        int unspent = unspentAllocations(p);
        if (unspent > 0) {
            p.sendSystemMessage(Component.translatable(
                    "message.dmz_ragnarok.tournaments.char.spend_allocations", unspent)
                    .withStyle(ChatFormatting.RED));
            return;
        }
        if (loadout == null || loadout.stream().noneMatch(id -> id != null && !id.isBlank())) {
            p.sendSystemMessage(Component.translatable("message.dmz_ragnarok.tournaments.char.pick_attacks")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        try {
            setLoadout(p, loadout);
            p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> {
                TournamentAttacks.applyLoadout(sd, getLoadout(p));
                try {
                    sd.getCharacter().clearActiveForm(p);
                    sd.getCharacter().setHasPreviousFormRecord(false);
                } catch (Throwable ignored) {
                }
            });
            // bare fighter: no items, curios, effects or xp ride into the arena (stats/race/appearance are kept)
            p.getInventory().clearContent();
            clearCurios(p);
            p.removeAllEffects();
            p.totalExperience = 0;
            p.experienceLevel = 0;
            p.experienceProgress = 0f;
            p.getFoodData().setFoodLevel(20);
            p.getFoodData().setSaturation(5.0f);
            s.put("template", capture(p));
            s.putBoolean("templateStale", false);
        } catch (Throwable t) {
            LOG.error("[TournamentCharacter] finalizeCreation failed for " + p.getGameProfile().getName(), t);
        } finally {
            // leave the sandbox: exit() restores the real character and clears the creation markers + IN_CREATION
            s.putBoolean("creating", false);
            s.putBoolean("awaitingLoadout", false);
            exit(p);
            p.sendSystemMessage(Component.translatable(
                    "message.dmz_ragnarok.tournaments.char.created").withStyle(ChatFormatting.GREEN));
        }
    }

    /**
     * Delete the tournament character so the player rebuilds one on next entry. Refused while swapped into it (never
     * strands them). NEVER touches the {@code restore} snapshot of their real character. True if a template was
     * removed.
     */
    public static boolean deleteTemplate(ServerPlayer p) {
        if (isActive(p)) {
            p.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                    "message.dmz_ragnarok.tournaments.char.delete_active")
                    .withStyle(net.minecraft.ChatFormatting.RED));
            return false;
        }
        CompoundTag s = store(p, false);
        if (s == null || !s.contains("template")) return false;
        s.remove("template");
        s.remove("templateStale");
        s.remove("loadout");
        p.sendSystemMessage(net.minecraft.network.chat.Component.translatable(
                "message.dmz_ragnarok.tournaments.char.deleted")
                .withStyle(net.minecraft.ChatFormatting.YELLOW));
        return true;
    }

    /**
     * Swap the player into their tournament character. Idempotent: a second call while active is a no-op (never
     * overwrites the saved real character). Snapshots the real character into {@code restore}, builds the template
     * on first use, loads it onto the player and sets the marker.
     */
    public static void enter(ServerPlayer p, String defId) {
        if (isActive(p)) return;
        CompoundTag s = store(p, true);
        // a player with no template is prompted to build one after the swap; the match never waits on that GUI
        // because a valid default template is built below first
        boolean firstEver = !s.contains("template");
        try {
            s.put("restore", capture(p));
            s.putBoolean("active", true);
            s.putString("defId", defId == null ? "" : defId);
            // build if no template yet, or if a loadout edit in a previous tournament flagged it stale (could not
            // round-trip the live player then, so deferred to here)
            if (firstEver || s.getBoolean("templateStale")) {
                buildTemplateInPlace(p);
                s.put("template", capture(p));
                s.putBoolean("templateStale", false);
            } else {
                apply(p, s.getCompound("template"));
            }
            reconcileHealth(p);
            markFighter(p, true);
            if (firstEver) {
                openCreationGui(p);
            }
        } catch (Throwable t) {
            // A failed swap must not strand the player: put the real character back and drop the marker. Only clear
            // "active" and remove "restore" if the real DMZ character was actually restored; otherwise keep them so
            // login recovery retries, rather than deleting the only copy of the real character (bug 1009).
            LOG.error("[TournamentCharacter] enter failed for " + p.getGameProfile().getName(), t);
            boolean restored = s.contains("restore") && apply(p, s.getCompound("restore"));
            if (restored) {
                s.putBoolean("active", false);
                s.remove("restore");
                markFighter(p, false);
            }
        }
    }

    /**
     * Swap the player back to their real character and drop the marker. No-op if not in a tournament character.
     * Template and loadout are kept for next time.
     */
    public static void exit(ServerPlayer p) {
        CompoundTag s = store(p, false);
        if (s == null || !s.getBoolean("active")) return;
        boolean restored = false;
        try {
            restored = apply(p, s.getCompound("restore"));
            reconcileHealth(p);
        } catch (Throwable t) {
            LOG.error("[TournamentCharacter] exit failed for " + p.getGameProfile().getName(), t);
        }
        if (!restored) {
            // The real DMZ character was NOT put back (a missing "dmz" snapshot, an absent capability, or a load that
            // threw). Clearing "active" and removing "restore" here would destroy the only copy of the player's real
            // character and leave them wearing the reset fighter permanently, while their inventory is handed back
            // from the same snapshot (bug 1009). Keep the snapshot AND the active marker so the next login's recovery
            // in ForgeEventHandler.onLogin retries the swap-back instead of stranding them.
            LOG.error("[TournamentCharacter] exit could not restore the real DMZ character for "
                    + p.getGameProfile().getName()
                    + "; keeping the restore snapshot and active marker for recovery on next login");
            return;
        }
        s.putBoolean("active", false);
        // a swap-back also ends any in-progress creation sandbox (e.g. a disconnect recovered on login), so the
        // player is never left half-created
        s.putBoolean("creating", false);
        s.putBoolean("awaitingLoadout", false);
        s.remove("kitGranted");
        s.remove("restore");
        IN_CREATION.remove(p.getUUID());
        markFighter(p, false);
    }

    /**
     * Add or remove this player from the in-memory fighter set (consulted by the flat-multiplier mixin) and push it
     * to every client. Membership means "holds a tournament character now"; the mixin further gates on being
     * transformed.
     */
    public static void markFighter(ServerPlayer p, boolean fighter) {
        if (fighter) {
            TournamentFighters.addServer(p.getUUID());
        } else {
            TournamentFighters.removeServer(p.getUUID());
        }
        net.shurui.dev.shuruis_dmz_tournaments.network.TournamentNet.broadcastFighters(p.getServer());
    }

    // Mutate the live player (holding the real character) into the frozen fighter. Called only when no template
    // exists, AFTER the real character was captured into "restore".
    private static void buildTemplateInPlace(ServerPlayer p) {
        p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> {
            // fixed pool, no earned growth: reset base stats, hand over flat allocations and fighter skills, freeze
            // dynamic growth so nothing accrues from energy/stamina spend
            Stats st = sd.getStats();
            st.setStrength(0);
            st.setStrikePower(0);
            st.setResistance(0);
            st.setVitality(0);
            st.setKiPower(0);
            st.setEnergy(0);
            grantFighterKit(sd);
            try {
                for (DynamicGrowthStat gs : DynamicGrowthStat.values()) {
                    sd.getDynamicGrowth().setGrowthEnabled(gs, false);
                }
            } catch (Throwable ignored) {
            }
            // chosen ki/strike loadout
            TournamentAttacks.applyLoadout(sd, getLoadout(p));
            // start at base form
            try {
                sd.getCharacter().clearActiveForm(p);
                sd.getCharacter().setHasPreviousFormRecord(false);
            } catch (Throwable ignored) {
            }
        });
        // bare fighter: no items or curios ride into the arena
        p.getInventory().clearContent();
        clearCurios(p);
        p.removeAllEffects();
        p.totalExperience = 0;
        p.experienceLevel = 0;
        p.experienceProgress = 0f;
        p.getFoodData().setFoodLevel(20);
        p.getFoodData().setSaturation(5.0f);
    }

    /**
     * Force a transform, bypassing {@code FormModeHandler}'s mastery/energy/stamina/item gates. Uses the first form
     * the race can reach; a race with none goes Ultimate.
     *
     * <p>The stat buff is NOT baked into base stats and does NOT use the form's configured multiplier. DMZ reads a
     * form's multiplier at stat-read time, so setting the active form alone would stack the form's own multiplier
     * (a Super Saiyan getting the flat buff AND its configured multiplier, every race landing on a different total).
     * Instead base stats are left alone and
     * {@link net.shurui.dev.shuruis_dmz_tournaments.mixin.StatsDataTournamentFormMixin} makes the form component read
     * as a FLAT {@link Config#FORCED_FORM_STAT_MULT} for every stat and race while this player is a registered
     * fighter: exactly that multiplier over the fighter's pre-transform stats. Only affects registered fighters, so
     * shipped form configs and normal play are untouched.</p>
     */
    public static void forceTransform(ServerPlayer p) {
        p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> {
            try {
                String group = TransformationsHelper.getGroupWithFirstAvailableForm(sd);
                String form = TransformationsHelper.getFirstAvailableForm(sd);
                Character ch = sd.getCharacter();
                ch.recordPreviousForm();
                if (form == null || form.isBlank() || group == null || group.isBlank()) {
                    // race has no reachable form: Ultimate
                    ch.setActiveForm("ultimate", "ultimate");
                } else {
                    ch.setActiveForm(group, form);
                }
                int effectTicks = Math.max(20, Config.FORCED_TRANSFORM_IFRAME_TICKS.get() + 20);
                try {
                    p.addEffect(new MobEffectInstance(MainEffects.TRANSFORMED.get(), effectTicks, 0, false, false, false));
                } catch (Throwable ignored) {
                }
                p.refreshDimensions();
                StatsEvents.applyHealthBonus(p);
                DmzHooks.syncStats(p);
                DmzHooks.syncResources(p);
            } catch (Throwable t) {
                LOG.error("[TournamentCharacter] forceTransform failed for " + p.getGameProfile().getName(), t);
            }
        });
    }

    private static CompoundTag capture(ServerPlayer p) {
        CompoundTag d = new CompoundTag();
        p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> d.put("dmz", sd.save()));
        d.put("inv", p.getInventory().save(new ListTag()));
        d.put("curios", saveCurios(p));
        d.putInt("xpTotal", p.totalExperience);
        d.putInt("xpLevel", p.experienceLevel);
        d.putFloat("xpProgress", p.experienceProgress);
        d.putFloat("health", p.getHealth());
        CompoundTag food = new CompoundTag();
        p.getFoodData().addAdditionalSaveData(food);
        d.put("food", food);
        d.put("effects", saveEffects(p));
        return d;
    }

    /**
     * Write a snapshot back onto the live player. Returns whether the DMZ character half was actually restored:
     * false when the capability is absent, the snapshot carries no {@code dmz} tag, or {@code load} throws. The
     * inventory/curios/xp/effects half is applied regardless. A caller that is about to delete the snapshot (any
     * swap-back path) MUST check this and keep the snapshot on false, or the player's real DMZ character is lost
     * while their inventory is restored from the same snapshot (bug 1009).
     */
    private static boolean apply(ServerPlayer p, CompoundTag d) {
        boolean[] dmzRestored = { false };
        p.getCapability(StatsCapability.INSTANCE).ifPresent(sd -> {
            try {
                // Wipe hair BEFORE the load or the two characters' hair merges. Character.hairBase and its three
                // super-form siblings are final fields holding live CustomHair objects DMZ never replaces:
                // Character.load calls load() ON them, and CustomHair.load does not clear first, skipping faces the
                // tag omits and overwriting only as many strands as the tag carries. So a swap kept the other
                // character's styled strands. Same defect CharacterSlots documents for form masteries.
                clearHair(sd);
                if (d.contains("dmz")) {
                    sd.load(d.getCompound("dmz"));
                    dmzRestored[0] = true;
                }
            } catch (Throwable t) {
                LOG.error("[TournamentCharacter] DMZ load failed", t);
            }
        });

        p.getInventory().clearContent();
        if (d.contains("inv")) p.getInventory().load(d.getList("inv", Tag.TAG_COMPOUND));

        clearCurios(p);
        loadCurios(p, d.getList("curios", Tag.TAG_COMPOUND));

        p.totalExperience = d.getInt("xpTotal");
        p.experienceLevel = d.getInt("xpLevel");
        p.experienceProgress = Math.max(0.0f, Math.min(1.0f, d.getFloat("xpProgress")));

        if (d.contains("food")) p.getFoodData().readAdditionalSaveData(d.getCompound("food"));

        p.removeAllEffects();
        loadEffects(p, d.getList("effects", Tag.TAG_COMPOUND));

        if (d.contains("health")) {
            float hp = d.getFloat("health");
            if (hp > 0) p.setHealth(Math.min(hp, p.getMaxHealth()));
        }

        DmzHooks.syncStats(p);
        DmzHooks.syncResources(p);
        p.inventoryMenu.broadcastChanges();
        p.containerMenu.broadcastChanges();
        return dmzRestored[0];
    }

    // reconcile MAX_HEALTH with the loaded character's DMZ vitality so the attribute does not carry the previous
    // character's modifier, then clamp current health into range
    private static void reconcileHealth(ServerPlayer p) {
        try {
            StatsEvents.applyHealthBonus(p);
        } catch (Throwable ignored) {
        }
        if (p.getHealth() > p.getMaxHealth()) p.setHealth(p.getMaxHealth());
    }

    private static ListTag saveEffects(ServerPlayer p) {
        ListTag list = new ListTag();
        for (MobEffectInstance e : p.getActiveEffects()) list.add(e.save(new CompoundTag()));
        return list;
    }

    private static void loadEffects(ServerPlayer p, ListTag list) {
        for (int i = 0; i < list.size(); i++) {
            MobEffectInstance e = MobEffectInstance.load(list.getCompound(i));
            if (e != null) p.addEffect(e);
        }
    }

    private static IItemHandlerModifiable curios(ServerPlayer p) {
        return CuriosBridge.getEquipped(p);
    }

    private static ListTag saveCurios(ServerPlayer p) {
        ListTag list = new ListTag();
        IItemHandlerModifiable c = curios(p);
        if (c != null) {
            for (int i = 0; i < c.getSlots(); i++) {
                ItemStack s = c.getStackInSlot(i);
                if (!s.isEmpty()) {
                    CompoundTag t = s.save(new CompoundTag());
                    t.putInt("Slot", i);
                    list.add(t);
                }
            }
        }
        return list;
    }

    private static void clearCurios(ServerPlayer p) {
        IItemHandlerModifiable c = curios(p);
        if (c != null) {
            for (int i = 0; i < c.getSlots(); i++) c.setStackInSlot(i, ItemStack.EMPTY);
        }
    }

    private static void loadCurios(ServerPlayer p, ListTag list) {
        IItemHandlerModifiable c = curios(p);
        if (c == null) return;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag t = list.getCompound(i);
            int slot = t.getInt("Slot");
            if (slot >= 0 && slot < c.getSlots()) c.setStackInSlot(slot, ItemStack.of(t));
        }
    }

    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
}
