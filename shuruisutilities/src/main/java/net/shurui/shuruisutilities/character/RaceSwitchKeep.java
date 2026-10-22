package net.shurui.shuruisutilities.character;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.skills.Skills;
import com.dragonminez.common.util.TransformationsHelper;
import com.dragonminez.server.events.players.StatsEvents;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.compat.dmz.ShadowDragonFormCompat;
import net.shurui.shuruisutilities.util.PlayerUtil;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

/**
 * Becoming a shadow dragon (the base race or any {@code shadow_dragon_Nstar} sub-race) keeps the character.
 *
 * <h2>The path a player really takes</h2>
 * DragonMineZ only lets a player pick a race while their character is NOT created ({@code Status.isHasCreatedCharacter}
 * false): the stats key then opens the race carousel instead of the stats screen, and the pick is committed by
 * {@code CreateCharacterC2S}, which runs {@code initializeWithRaceAndClass} only for an uncreated character. For a
 * player who already has a character the one way back to the carousel is Dende's reset ({@code NPCActionC2S} Dende
 * action 2), which is {@code resetPlayerProgress}: stats, TP, skills, techniques, quests and the created flag all go.
 * That is the "Reset your character" the race unlock told people to do, and it is what wiped them.
 *
 * <h2>What this does</h2>
 * {@link #stashBeforeDendeReset} keeps a copy of the DMZ character in persisted NBT ({@link #STASH_KEY}) right before
 * Dende resets it. When that player next commits a character, {@link #afterCreate} looks at the race: a shadow dragon
 * race gets the stashed character back, with only the race, the look picked in the carousel and the race-bound form
 * skills changed; any other race drops the stash and the reset stands as DragonMineZ intends.
 *
 * <p>What still changes, and why:
 * <ul>
 *   <li>The race, class-free look and tail come from the carousel pick, because that is the point of the switch.</li>
 *   <li>The form-group skills of the old and the new race ({@code superforms}, {@code godforms}, ...) go to 0. Those
 *       skill names are shared across races and a form is unlocked by its group's skill LEVEL, so a saiyan's
 *       {@code superforms} 9 would hand a shadow dragon every superform rung for free. Omega Shenron is re-applied
 *       straight after for a player who holds that entitlement. Between two shadow dragon races (base and sub-races
 *       share the same one-rung ladder) the skills are kept.</li>
 *   <li>The active form and stack form are cleared (the old race's forms do not exist on the new one).</li>
 *   <li>An upgraded android stops being one (that upgrade is the human racial).</li>
 * </ul>
 * Everything else is the stashed character: level, stats, TP, pending points, alignment, release, other skills,
 * techniques, quests, masteries, cooldowns and bonus stats. Prestige, the Z-Soul bank, stat cap overrides and the super
 * wish count are SU data that Dende's reset never touches, so they are simply still there.
 */
public final class RaceSwitchKeep
{
    private RaceSwitchKeep() {}

    /** Player persisted NBT: the DMZ character as it was just before Dende reset it. */
    public static final String STASH_KEY = "su_race_keep_stash";

    /** Called at Dende's reset, before DragonMineZ wipes the character. Only a created character is worth keeping. */
    public static void stashBeforeDendeReset(ServerPlayer p, StatsData sd)
    {
        try
        {
            if (p == null || sd == null || !sd.getStatus().isHasCreatedCharacter())
                return;
            CompoundTag stash = new CompoundTag();
            stash.put("dmz", sd.save());
            stash.putString("race", String.valueOf(sd.getCharacter().getRaceName()));
            stash.putInt("slot", CharacterSlots.activeIndex(p));
            stash.putLong("at", System.currentTimeMillis());
            PlayerUtil.getPersistedTag(p, true).put(STASH_KEY, stash);
            LoggingHandler.sulog.info("[Characters] {} reset at Dende ({}, level {}): kept a copy in case they pick a"
                    + " shadow dragon race.", p.getGameProfile().getName(), stash.getString("race"), sd.getLevel());
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.warn("[Characters] could not keep a copy before Dende's reset: {}", t.toString());
        }
    }

    /** Called once a character has been committed. Restores the stashed character for a shadow dragon race. */
    public static void afterCreate(ServerPlayer p, StatsData sd)
    {
        try
        {
            if (p == null || sd == null || !sd.getStatus().isHasCreatedCharacter())
                return;
            CompoundTag persisted = PlayerUtil.getPersistedTag(p, true);
            if (!persisted.contains(STASH_KEY, Tag.TAG_COMPOUND))
                return;
            CompoundTag stash = persisted.getCompound(STASH_KEY);
            persisted.remove(STASH_KEY);
            String newRace = String.valueOf(sd.getCharacter().getRaceName()).toLowerCase(Locale.ROOT);
            if (!ShadowDragonFormCompat.isShadowDragonRace(newRace))
                return; // any other race: the reset stands
            if (stash.getInt("slot") != CharacterSlots.activeIndex(p) || !stash.contains("dmz", Tag.TAG_COMPOUND))
                return; // the copy belongs to another character slot
            String oldRace = stash.getString("race").toLowerCase(Locale.ROOT);
            restore(p, sd, stash.getCompound("dmz"), oldRace, newRace);
        }
        catch (Throwable t)
        {
            LoggingHandler.sulog.error("[Characters] keeping the character through the shadow dragon switch failed for "
                    + (p == null ? "?" : p.getGameProfile().getName()), t);
        }
    }

    private static final String[] MASTERY_KEYS = { "FormMasteries", "StackFormMasteries", "FormsUsedBefore",
            "StackFormsUsedBefore" };

    private static void restore(ServerPlayer p, StatsData sd, CompoundTag old, String oldRace, String newRace)
            throws Exception
    {
        CompoundTag fresh = sd.save();
        CompoundTag merged = old.copy();
        // The look and race are the carousel pick; the masteries are the old character's.
        CompoundTag character = fresh.getCompound("Character").copy();
        CompoundTag oldCharacter = old.getCompound("Character");
        for (String k : MASTERY_KEYS)
            if (oldCharacter.contains(k))
                character.put(k, oldCharacter.get(k).copy());
        merged.put("Character", character);
        sd.load(merged);
        sd.getStatus().setHasCreatedCharacter(true);
        if (!oldRace.equals(newRace))
            sd.getStatus().setAndroidUpgraded(false);
        try { sd.getCharacter().clearActiveForm(p); } catch (Throwable ignored) {}
        try { sd.getCharacter().clearActiveStackForm(p); } catch (Throwable ignored) {}

        boolean bothShadow = ShadowDragonFormCompat.isShadowDragonRace(oldRace);
        Set<String> zeroed = new HashSet<>();
        if (!bothShadow)
        {
            zeroed.addAll(formSkills(oldRace));
            zeroed.addAll(formSkills(newRace));
            Skills skills = sd.getSkills();
            for (String s : zeroed)
                if (skills.hasSkill(s))
                    skills.setSkillLevel(s, 0);
        }
        // Omega Shenron only for a player who holds the entitlement (applyFormSkill itself does not check it; it only
        // raises, and is a no-op keyless).
        if (net.shurui.shuruisutilities.corrupted.RaceUnlocks.has(p.getUUID(),
                net.shurui.shuruisutilities.corrupted.RaceUnlocks.OMEGA_SHENRON_RACE))
            ShadowDragonFormCompat.applyFormSkill(p, ShadowDragonFormCompat.FORM_OMEGA_SHENRON);
        try
        {
            sd.getCharacter().setSelectedFormGroup(TransformationsHelper.getGroupWithFirstAvailableForm(sd));
            sd.getCharacter().setSelectedForm(TransformationsHelper.getFirstAvailableForm(sd));
            sd.getCharacter().setSelectedStackFormGroup(TransformationsHelper.getGroupWithFirstAvailableStackForm(sd));
            sd.getCharacter().setSelectedStackForm(TransformationsHelper.getFirstAvailableStackForm(sd));
        }
        catch (Throwable ignored)
        {
        }
        StatCapOverrides.reassert(p);
        p.refreshDimensions();
        try
        {
            StatsEvents.applyHealthBonus(p);
        }
        catch (Throwable ignored)
        {
        }
        p.setHealth(p.getMaxHealth());
        sd.getResources().setCurrentEnergy(sd.getMaxEnergy());
        sd.getResources().setCurrentStamina(sd.getMaxStamina());
        NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(p), p);
        LoggingHandler.sulog.info("[Characters] {} became {} (was {}): character kept at level {}; form skills reset: {}.",
                p.getGameProfile().getName(), newRace, oldRace, sd.getLevel(), zeroed.isEmpty() ? "none" : zeroed);
        net.shurui.shuruisutilities.util.output.ChatOutputHandler.chatNotification(p,
                "You walk the shadow dragon's path and keep everything you had. Only your race and its forms changed.");
    }

    /** The skill names that gate this race's form groups (superforms, godforms, ...). */
    private static Set<String> formSkills(String race)
    {
        Set<String> out = new HashSet<>();
        try
        {
            Map<String, FormConfig> groups = ConfigManager.getAllFormsForRace(race);
            if (groups == null)
                return out;
            for (FormConfig cfg : groups.values())
            {
                if (cfg == null || cfg.getFormType() == null)
                    continue;
                String skill = TransformationsHelper.getSkillNameForType(cfg.getFormType());
                if (skill != null && !skill.isBlank())
                    out.add(skill.toLowerCase(Locale.ROOT));
            }
        }
        catch (Throwable ignored)
        {
        }
        return out;
    }
}
