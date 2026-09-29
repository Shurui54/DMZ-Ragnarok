package net.shurui.dev.sdu.compat;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.SkillsConfig;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.network.S2C.StatsSyncS2C;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.DmzNpc;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Server-side buy path for DMZ stack skills (kaioken, ultimate). DMZ's client menu never sends a PURCHASE
 * packet for a stack skill's first level, so {@code /rg npc buyskill} grants/charges/syncs exactly like DMZ's
 * {@code UpdateSkillC2S} PURCHASE case. Direct DMZ refs (mandatory dep), guarded so a rename/absence gives a
 * safe failure.
 *
 * <p>DMZ PURCHASE reference ({@code UpdateSkillC2S#handle}, non-form skill): race gate
 * {@code isSkillAllowedForRace(name, race)} (empty allowedRaces = all); cost
 * {@code Math.max(0, costs[currentLevel])}; afford {@code trainingPoints >= cost} then
 * {@code removeTrainingPoints(cost)}; grant {@code setSkillLevel(name, currentLevel+1)}; sync
 * {@code StatsSyncS2C}. DMZ only does 0 -&gt; 1; we generalise to the next level so the command climbs the
 * whole stack, reusing DMZ's per-level {@code costs[currentLevel]} so scaling matches.
 */
public final class DmzSkills {

    /**
     * DMZ's ultimate skill. Its FIRST level is Old Kai's ritual reward, never a purchase, so no buy path in
     * this suite may hand it over.
     */
    public static final String RITUAL_ONLY_SKILL = "ultimate";

    private DmzSkills() {
    }

    /**
     * True when a buy would be the ultimate UNLOCK itself, which stock DMZ grants only through Old Kai's
     * ritual (the {@code old_kai_ritual} sidequest and its challenge).
     *
     * <p>This exists because DMZ prices ultimate as a single {@code -1} cost. Everywhere else that sentinel
     * means "always available", and both our buy paths clamp it with {@code Math.max(0, cost)}, so the skill
     * reads as costing zero TP and a player could take it straight off the form menu for free without ever
     * meeting Old Kai. DMZ itself never had that hole: its own menu refuses a stack skill's first level and
     * its PURCHASE handler bails, so the ritual was the only way in. We opened the hole by generalising the
     * stack-skill buy, and this closes it again for this one skill.
     *
     * <p>Only the first level is locked, deliberately. Anything built ON TOP of the ultimate line stays
     * purchasable from the form menu, which is why the test is {@code currentLevel <= 0} rather than a flat
     * ban on the skill: a server that extends ultimate's costs array gets working levels 2+, while level 1
     * remains the ritual's to give.
     */
    public static boolean isRitualLockedUnlock(String skill, int currentLevel) {
        return currentLevel <= 0
                && RITUAL_ONLY_SKILL.equalsIgnoreCase(skill == null ? null : skill.trim());
    }

    /** buy attempt result: success + chat message (already localised as a Component for the target's client) */
    public record BuyResult(boolean success, Component message) {
    }

    /** DMZ stack-skill ids for the command suggestion list, or empty on failure */
    public static List<String> stackSkillIds() {
        try {
            SkillsConfig cfg = ConfigManager.getSkillsConfig();
            if (cfg != null && cfg.getStackSkills() != null) {
                return new ArrayList<>(cfg.getStackSkills());
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DMZ stack skills: {}", DmzNpc.MODID, t.toString());
        }
        return new ArrayList<>();
    }

    /** Buy the next level of a stack skill, mirroring DMZ's PURCHASE grant/charge/sync. No exception escapes. */
    public static BuyResult buyStackSkill(ServerPlayer player, String rawSkill) {
        if (player == null || rawSkill == null || rawSkill.isBlank()) {
            return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.no_skill"));
        }
        String skill = rawSkill.toLowerCase(Locale.ROOT).trim();
        try {
            SkillsConfig skillsConfig = ConfigManager.getSkillsConfig();
            if (skillsConfig == null) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.config_unavailable"));
            }

            // Must be a STACK skill (kaioken/ultimate). Reject form/ki/strike skills.
            if (skillsConfig.getStackSkills() == null || !skillsConfig.getStackSkills().contains(skill)) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.not_stack_skill", rawSkill));
            }

            // resolve DMZ stats/character via the guarded capability idiom used elsewhere in sdu
            StatsData data = DmzForms.stats(player);
            if (data == null || data.getCharacter() == null || data.getCharacter().getRaceName() == null
                    || data.getCharacter().getRaceName().isEmpty()) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.no_character"));
            }
            String raceName = data.getCharacter().getRaceName();

            // Max level = number of entries in the skill's costs array (matches DMZ refreshRuntimeMaxLevel).
            SkillsConfig.SkillCosts skillCosts = skillsConfig.getSkillCosts(skill);
            List<Integer> costs = skillCosts == null ? null : skillCosts.getCosts();
            if (costs == null || costs.isEmpty()) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.no_costs", skill));
            }
            int maxLevel = costs.size();

            int currentLevel = data.getSkills().getSkillLevel(skill); // 0 if not owned
            if (currentLevel >= maxLevel) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.maxed", skill, currentLevel));
            }

            // Ultimate's unlock belongs to Old Kai, not to a menu. Checked AFTER the maxed test so somebody who
            // already earned it is told they own it rather than being sent back to a ritual they have done.
            // This is the authoritative gate: BuyStackSkillC2S and /rg npc buyskill both land here.
            if (isRitualLockedUnlock(skill, currentLevel)) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.ritual_only", skill));
            }

            // Race gate: empty allowedRaces = all allowed (SkillsConfig.isSkillAllowedForRace handles both).
            if (!skillsConfig.isSkillAllowedForRace(skill, raceName)) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.race_forbidden", raceName, skill));
            }

            // Cost of the NEXT level. A NEGATIVE raw cost is DMZ's "not purchasable at this rung" sentinel: the
            // leading -1 that both kaioken and ultimate carry means the master or a quest grants that rung
            // directly (via setSkillLevel, which does not route through here), so it is never meant to be bought.
            // We used to write int cost = Math.max(0, rawCost), and that clamp is exactly what made kaioken free
            // off the skills menu: -1 read as 0 TP. The clamp is gone. A negative cost is refused outright, which
            // restores DMZ's own cost >= 0 semantics that this method had discarded.
            //
            // This method is the single authoritative choke point both the double-click BuyStackSkillC2S packet
            // and /rg npc buyskill land on, so refusing here closes the exploit for every path at once, including
            // a hand-crafted packet. The guard also covers ultimate's first rung (cost -1), so it overlaps the
            // isRitualLockedUnlock check above on purpose: that check is kept because its ritual-named message is
            // clearer for ultimate, and it stays live for any rung whose cost is not negative.
            Integer rawCost = currentLevel < costs.size() ? costs.get(currentLevel) : null;
            if (rawCost == null) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.no_level_cost", skill, currentLevel + 1));
            }
            if (rawCost < 0) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.not_purchasable", skill));
            }
            int cost = rawCost;

            float tp = data.getResources().getTrainingPoints();
            if (tp < (float) cost) {
                return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.not_enough_tp", cost, (int) tp));
            }

            // Charge + grant exactly like DMZ PURCHASE: remove TP, then set the next skill level.
            data.getResources().removeTrainingPoints((float) cost);
            data.getSkills().setSkillLevel(skill, currentLevel + 1);

            // Sync stats to the client the same way DMZ's UpdateSkillC2S PURCHASE does.
            NetworkHandler.sendToTrackingEntityAndSelf(new StatsSyncS2C(player), player);

            int newLevel = currentLevel + 1;
            // audited: a stack skill (kaioken, ultimate) is real progression bought with real TP, and this method
            // logged nothing on success, so there was no way to tell who had taken one after a free-kaioken
            // exploit. Same [audit] prefix the rest of the suite greps on, naming the player, skill, new level and
            // TP charged.
            DmzNpc.LOGGER.info("[audit] {} bought stack skill {} to level {} for {} TP",
                    player.getName().getString(), skill, newLevel, cost);
            return new BuyResult(true, Component.translatable("message.dmz_ragnarok.npc.buyskill.purchased", skill, newLevel, cost));
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] buyStackSkill failed for {} ({}): {}",
                    DmzNpc.MODID, player.getName().getString(), rawSkill, t.toString());
            return new BuyResult(false, Component.translatable("message.dmz_ragnarok.npc.buyskill.internal_error", rawSkill));
        }
    }
}
