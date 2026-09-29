package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.network.C2S.UpdateSkillC2S;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.shurui.dev.sdu.compat.DmzSkills;
import net.shurui.dev.sdu.form.FormQuestGate;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Locale;
import java.util.function.Supplier;

// Server-side twin of SkillsMenuScreenMixin's tree-buy bypass. DMZ's UpdateSkillC2S UPGRADE handler blocks a
// level-0 form buy when isMasterOnlyFormSkill(data, skillName) returns isFormSkillBuyFromMaster(skillName).
// We cancel that to false whenever the buying player's race has a REAL admin-set price for that form at the
// level being bought, so ANY player with enough TP can buy from the skills tree, no hidden master-only gate.
//
// Index semantics: the UPGRADE case only consults isMasterOnlyFormSkill while the skill is at level 0 and
// charges computeTpCost(data, skillName, skill.getLevel()) (indexes prices[level]). We read the same level
// via getSkillLevel(skillName) and check getFormSkillTpCosts(formType)[level]. null / -1 / MAX_VALUE /
// out-of-range = "no real price", we do nothing, so quest-gated / Priceless forms keep DMZ's gate.
//
// remap=false on the descriptor per sdu's DMZ-jar convention. Registered in the COMMON mixins array.
@Mixin(targets = "com.dragonminez.common.network.C2S.UpdateSkillC2S", remap = false)
public abstract class UpdateSkillC2SMixin {

    // packet's private final fields (javap: SkillAction action, String skillName). read-only, so we can
    // classify the action + skill before DMZ's handle body runs.
    @Shadow @Final private UpdateSkillC2S.SkillAction action;
    @Shadow @Final private String skillName;

    // Fixes the MASTER-NPC (King Kai) stack-skill purchase. King Kai's MastersSkillsScreen "Purchase" button
    // sends the SAME UpdateSkillC2S(PURCHASE, "kaioken"/"ultimate", cost) as the tree, but DMZ's server
    // PURCHASE branch bails at notOwned = !hasSkill(name): once a stack skill exists at level 0 hasSkill is
    // already true, so it breaks before granting. We intercept at HEAD for exactly a PURCHASE of a configured
    // stack skill and route through our grant path (DmzSkills.buyStackSkill, same as /rg npc buyskill and the
    // menu packet), then cancel so DMZ's broken PURCHASE body never runs.
    //
    // Scope: ONLY PURCHASE + stack skill. Form skills, upgrades, toggles, non-stack purchases return early and
    // stay vanilla DMZ, so the form-gate injects below are unaffected. The client MENU uses our separate
    // BuyStackSkillC2S packet, so no double-grant. Fail-open: any unexpected shape lets DMZ's handle run.
    @Inject(
        method = "handle(Ljava/util/function/Supplier;)V",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private void sdu$grantStackSkillPurchase(Supplier<NetworkEvent.Context> ctx, CallbackInfo ci) {
        try {
            if (this.action != UpdateSkillC2S.SkillAction.PURCHASE || this.skillName == null) {
                return; // not a purchase: DMZ handles upgrades/toggles
            }
            String skill = this.skillName.toLowerCase(Locale.ROOT);
            if (!ConfigManager.getSkillsConfig().getStackSkills().contains(skill)) {
                return; // not a stack skill: form/other purchases stay vanilla
            }
            NetworkEvent.Context context = ctx.get();
            ServerPlayer player = context.getSender();
            if (player != null) {
                // grant on the server thread (we cancelled DMZ's enqueue, so we own this work)
                context.enqueueWork(() -> {
                    DmzSkills.BuyResult r = DmzSkills.buyStackSkill(player, this.skillName);
                    if (r != null && !r.success()) {
                        player.sendSystemMessage(r.message());
                    }
                });
            }
            // we handled it: ack and stop DMZ's broken PURCHASE body
            context.setPacketHandled(true);
            ci.cancel();
        } catch (Throwable t) {
            // fail open: let DMZ's handle run
        }
    }

    // RE-CREATE A FORM SKILL ROW THAT NO LONGER EXISTS, so the skills tree can buy it again.
    //
    // DMZ's UPGRADE branch opens with `Skill skill = skills.getSkill(name); if (skill == null) return;` and that
    // return is silent: no message, no sound, no log. Every player normally HAS a row for every form skill, sitting
    // at level 0, so the tree only ever upgrades an existing row and the case never comes up. But anything that
    // REMOVES the row rather than zeroing it (DMZ's own unlearn, /dmzskills, our own tombstone scrub when a form
    // type is deleted) leaves the player in a state the tree cannot get out of: the form is gone, the row is gone,
    // and clicking to buy it back does nothing at all, for ever.
    //
    // Skills.setSkillLevel creates the row when it is absent, and computes the right max level while it does, so
    // putting it back at level 0 restores exactly the state a fresh character has. DMZ's own body then runs
    // unchanged and every gate still applies: race, the master-only check, the quest gate, the price and the TP.
    // Nothing is granted here, only the ability to be asked for.
    //
    // Form skills only, and never a stack skill, which has its own purchase path above.
    @Inject(
        method = "lambda$handle$0",
        at = @At("HEAD"),
        require = 0,
        remap = false
    )
    private void sdu$restoreMissingFormSkillRow(ServerPlayer player, StatsData data, CallbackInfo ci) {
        try {
            if (this.action != UpdateSkillC2S.SkillAction.UPGRADE || this.skillName == null || data == null) {
                return;
            }
            var skills = data.getSkills();
            if (skills == null || skills.getSkill(this.skillName) != null) {
                return; // the row is there, so this is an ordinary upgrade
            }
            String skill = this.skillName.toLowerCase(Locale.ROOT);
            var config = ConfigManager.getSkillsConfig();
            if (config == null
                    || !config.getFormSkills().contains(skill)
                    || config.getStackSkills().contains(skill)) {
                return;
            }
            skills.setSkillLevel(skill, 0);
            net.shurui.dev.sdu.DmzNpc.LOGGER.info(
                    "[{}] restored missing form skill row '{}' at level 0 for {} so it can be bought again",
                    net.shurui.dev.sdu.DmzNpc.MODID, skill, player.getGameProfile().getName());
        } catch (Throwable t) {
            // fail open: DMZ behaves exactly as it did before
        }
    }

    @Inject(
        method = "isMasterOnlyFormSkill(Lcom/dragonminez/common/stats/StatsData;Ljava/lang/String;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void sdu$allowTreeBuyWithRealPrice(StatsData data, String skillName,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (data == null || skillName == null || skillName.isEmpty()) {
            return;
        }
        try {
            int level = data.getSkills().getSkillLevel(skillName); // level being bought (0 for a first buy)
            String race = data.getCharacter() != null ? data.getCharacter().getRaceName() : "";
            RaceCharacterConfig charConfig = ConfigManager.getRaceCharacter(race);
            if (charConfig == null) {
                return;
            }
            Integer[] prices = charConfig.getFormSkillTpCosts(skillName);
            if (prices == null || level < 0 || level >= prices.length) {
                return;
            }
            Integer price = prices[level];
            // real buyable price (non-null, >= 0, not the MAX_VALUE "Priceless" sentinel) = tree-buyable, so
            // cancel the master-only gate. anything else falls through to DMZ.
            if (price != null && price >= 0 && price != Integer.MAX_VALUE) {
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            // never break DMZ's gate; defer to vanilla
        }
    }

    // Quest-gated form purchases (authoritative server gate). isSkillAllowedForPlayerRace(data, name) is the
    // FIRST guard DMZ consults: PURCHASE needs it directly (master-NPC buy) and UPGRADE needs it while the
    // skill is at level 0 (the tree first buy, sent as UPGRADE). TOGGLE computes but never reads it, and an
    // UPGRADE of an owned form (getLevel() > 0) bypasses it, so returning false here blocks ONLY the initial
    // buy of an unowned form, never a toggle or owned-form upgrade. We return false (and warn) exactly when
    // the target is a mapped form the player doesn't own and whose gating quest is incomplete. Level->=1
    // upgrade gating (when gateUpgrades) is handled in sdu$gateUpgradeCost.
    @Inject(
        method = "isSkillAllowedForPlayerRace(Lcom/dragonminez/common/stats/StatsData;Ljava/lang/String;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void sdu$gateInitialFormBuy(StatsData data, String skillName,
                                               CallbackInfoReturnable<Boolean> cir) {
        try {
            // only the level-1 buy of an unowned form. owned forms are never blocked here; higher levels go
            // through computeTpCost (sdu$gateUpgradeCost).
            if (FormQuestGate.blocksInitialBuy(data, skillName)) {
                // proof-log so any server-side refusal of kaioken/ultimate (or a gated form) is visible. a
                // block implies the gating quest is incomplete (blocksInitialBuy requires it), so completed=false.
                net.shurui.dev.sdu.DmzNpc.LOGGER.info(
                        "[{}] server form-gate BLOCKED {} to level {} (quest {} completed={})",
                        net.shurui.dev.sdu.DmzNpc.MODID, skillName, 1,
                        FormQuestGate.blockingQuestId(data, skillName, 1), false);
                FormQuestGate.notifyLocked(data, skillName, 1);
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            // fail open: defer to vanilla
        }
    }

    // Quest-gated form buys/upgrades, per target level. computeTpCost is consulted by the UPGRADE branch
    // (which includes the tree FIRST buy, sent as UPGRADE with currentLevel == 0) with the skill's CURRENT
    // level to price the next; a -1 return fails DMZ's upgradeCost >= 0 guard, cleanly aborting with no TP
    // spent. Target level is currentLevel + 1: if a gate resolves to that (skill, level) and its quest is
    // incomplete, abort. Single choke point for the tree first-buy AND every upgrade; the master-NPC level-1
    // PURCHASE path (reads its price directly, not here) is covered by sdu$gateInitialFormBuy. For a form
    // skill this is only reached from buy/upgrade, so -1 affects nothing but a gated form's buy at that level.
    @Inject(
        method = "computeTpCost(Lcom/dragonminez/common/stats/StatsData;Ljava/lang/String;I)I",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void sdu$gateUpgradeCost(StatsData data, String skillName, int currentLevel,
                                            CallbackInfoReturnable<Integer> cir) {
        try {
            // buying at level (currentLevel + 1); currentLevel == 0 is the level-1 tree first buy, which must
            // ALSO be gated here (the tree first-buy is sent as UPGRADE and does call computeTpCost).
            int targetLevel = currentLevel + 1;
            if (FormQuestGate.blocksUpgradeToLevel(data, skillName, targetLevel)) {
                // proof-log so a server-side block of any gated level is visible. block implies the gating
                // quest is incomplete (blocksUpgradeToLevel requires it), so completed=false.
                net.shurui.dev.sdu.DmzNpc.LOGGER.info(
                        "[{}] server form-gate BLOCKED {} to level {} (quest {} completed={})",
                        net.shurui.dev.sdu.DmzNpc.MODID, skillName, targetLevel,
                        FormQuestGate.blockingQuestId(data, skillName, targetLevel), false);
                FormQuestGate.notifyLocked(data, skillName, targetLevel);
                cir.setReturnValue(-1);
            }
        } catch (Throwable t) {
            // fail open: defer to vanilla
        }
    }

    // ALIGNMENT-gated form UNLOCK, master-NPC buy path. The twin of sdu$gateInitialFormBuy: refuse the level-1
    // buy of an unowned form whose alignment unlock-window excludes this character's alignment, and tell them the
    // range. Same choke point (isSkillAllowedForPlayerRace) MixinDmzSsgPurchaseGate uses for the SSG buy.
    @Inject(
        method = "isSkillAllowedForPlayerRace(Lcom/dragonminez/common/stats/StatsData;Ljava/lang/String;)Z",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void sdu$gateInitialFormBuyAlignment(StatsData data, String skillName,
                                                        CallbackInfoReturnable<Boolean> cir) {
        try {
            if (net.shurui.dev.sdu.form.FormAlignmentGate.blocksUnlock(data, skillName, 1)) {
                net.shurui.dev.sdu.DmzNpc.LOGGER.info(
                        "[{}] server form-alignment-gate BLOCKED unlock of {} to level {}",
                        net.shurui.dev.sdu.DmzNpc.MODID, skillName, 1);
                net.shurui.dev.sdu.form.FormAlignmentGate.notifyUnlockBlocked(data, skillName, 1);
                cir.setReturnValue(false);
            }
        } catch (Throwable t) {
            // fail open: defer to vanilla
        }
    }

    // ALIGNMENT-gated form UNLOCK, tree buy path. The twin of sdu$gateUpgradeCost: return -1 (DMZ aborts the buy
    // with no TP spent) when the form being bought at (currentLevel + 1) is that form's unlock level and the
    // character's alignment is outside the unlock-window. blocksUnlock only fires at the form's own unlock level
    // for an unowned form, so an ordinary upgrade of an owned form is never touched here.
    @Inject(
        method = "computeTpCost(Lcom/dragonminez/common/stats/StatsData;Ljava/lang/String;I)I",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        remap = false
    )
    private static void sdu$gateUpgradeCostAlignment(StatsData data, String skillName, int currentLevel,
                                                     CallbackInfoReturnable<Integer> cir) {
        try {
            int targetLevel = currentLevel + 1;
            if (net.shurui.dev.sdu.form.FormAlignmentGate.blocksUnlock(data, skillName, targetLevel)) {
                net.shurui.dev.sdu.DmzNpc.LOGGER.info(
                        "[{}] server form-alignment-gate BLOCKED unlock of {} to level {}",
                        net.shurui.dev.sdu.DmzNpc.MODID, skillName, targetLevel);
                net.shurui.dev.sdu.form.FormAlignmentGate.notifyUnlockBlocked(data, skillName, targetLevel);
                cir.setReturnValue(-1);
            }
        } catch (Throwable t) {
            // fail open: defer to vanilla
        }
    }

    // SAY WHY A FORM WOULD NOT BUY. Every refusal computeTpCost can make comes back as the same -1, and the
    // caller compares cost < 0 and returns without a message, a sound or a log line. From the player's side a
    // purchase they can afford simply does not happen, and "clicking it does nothing" is the only symptom a
    // missing price list, a maxed skill and a trailing comma in character.json all produce.
    //
    // At RETURN so it sees the final answer including our own gate above, and read-only: it never changes the
    // value, so nothing here can make a refused purchase succeed or a valid one fail. The quest gate reports
    // itself, so a refusal that still has a real price is left alone rather than explained twice.
    @Inject(
        method = "computeTpCost(Lcom/dragonminez/common/stats/StatsData;Ljava/lang/String;I)I",
        at = @At("RETURN"),
        require = 0,
        remap = false
    )
    private static void sdu$explainRefusedFormBuy(StatsData data, String skillName, int currentLevel,
                                                  CallbackInfoReturnable<Integer> cir) {
        Integer cost = cir.getReturnValue();
        if (cost != null && cost < 0) {
            net.shurui.dev.sdu.form.FormBuyDiagnostics.explainRefusal(data, skillName, currentLevel);
        }
    }
}
