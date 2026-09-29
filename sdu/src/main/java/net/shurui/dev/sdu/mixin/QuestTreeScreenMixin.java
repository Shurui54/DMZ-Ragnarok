package net.shurui.dev.sdu.mixin;

import com.dragonminez.common.quest.Difficulty;
import com.dragonminez.common.quest.PlayerQuestData;
import com.dragonminez.common.quest.Quest;
import com.dragonminez.common.quest.QuestObjective;
import com.dragonminez.common.quest.QuestReward;
import com.dragonminez.common.quest.QuestRegistry;
import com.dragonminez.common.quest.Saga;
import com.dragonminez.common.quest.objectives.KillObjective;
import com.dragonminez.common.quest.rewards.CommandReward;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.EntityType;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.client.ClientPreviewClones;
import net.shurui.dev.sdu.client.ClientQuestKeys;
import net.shurui.dev.sdu.compat.cnpc.CnpcPreviewConfig;
import net.shurui.dev.sdu.form.FormPurchaseReward;
import net.shurui.dev.sdu.form.FormQuestGate;
import net.shurui.dev.sdu.saga.HardSagaGate;
import net.shurui.dev.sdu.saga.SagaGate;
import net.shurui.dev.sdu.saga.SagaQuestGateConfig;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

// DMZ's quest tree hides COMMAND rewards from getDisplayRewards. Our TECHNIQUE reward is a COMMAND running
// `sdu givetechnique ...`, so it'd be invisible in the saga menu. We add those technique commands back into
// the display list so the granted technique shows (name from CommandRewardMixin). remap=false (DMZ class);
// require=0 so a signature change can't crash the screen.
@Mixin(targets = "com.dragonminez.client.gui.character.QuestTreeScreen", remap = false)
public abstract class QuestTreeScreenMixin {

    @Shadow
    private Quest selectedQuest;

    @Shadow
    private StatsData statsData;

    @Shadow
    private int currentSagaIndex;

    @Shadow
    private List<Saga> availableSagas;

    // DMZ's rendered navigator list. rebuildNavigatorEntries repopulates it from a HARDCODED SAGA_CATALOG
    // (12 fixed rows) plus loaded sagas, so deleting a saga's json empties its DATA but the catalog row stays
    // on screen; we prune the dead rows at TAIL. wildcard shadow because NavigatorEntry is a private nested
    // record we can't name here; erasure makes the descriptor match.
    @Shadow
    private List<?> navigatorEntries;

    // DMZ's fixed difficulty option list ({EASY, NORMAL, HARD}) and the modal-visibility predicate. read the
    // list to locate the HARD row, the predicate to know the one-time picker is on screen (it never shows once
    // a difficulty is chosen, so an already-running hard saga is never affected).
    @Shadow
    @Final
    private static Difficulty[] DIFFICULTY_OPTIONS;

    @Shadow
    private boolean shouldShowDifficultySelect() {
        throw new AssertionError();
    }

    // cached reflective handles for the private PanelRect geometry of a difficulty option.
    // getDifficultyOptionRect returns the private nested record PanelRect, so we call it and its accessors by
    // reflection. Any mismatch latches the HARD gate off (sdu$diffGateFailed) so the picker still works, just
    // without the prestige lock veil.
    private static Method sdu$getDifficultyOptionRect;
    private static Method sdu$rectContains;
    private static Method sdu$rectX;
    private static Method sdu$rectY;
    private static Method sdu$rectRight;
    private static Method sdu$rectBottom;
    private static boolean sdu$diffGateReady;
    private static boolean sdu$diffGateFailed;

    // the six comingSoon=true catalog rows (daima, gt, dball, beerus, rof, u7vsu6) are unreleased teasers with
    // a null saga, so the placeholder prune below drops them along with removed base sagas. false keeps them
    // pruned; flip to true to bring the teasers back.
    private static final boolean SDU_KEEP_COMING_SOON_TEASERS = false;

    // cached reflective handles for DMZ's private RewardBlock ctor and wrapText
    private static Constructor<?> sdu$rewardBlockCtor;
    private static Method sdu$wrapText;
    private static boolean sdu$formPurchaseReady;
    private static boolean sdu$formPurchaseFailed;

    // cached reflective handles for the private NavigatorEntry record. isPlaceholderSaga() encodes the exact
    // discriminator (type==SAGA && saga==null && sagaId!=null); comingSoon() spares the teaser rows.
    private static Method sdu$isPlaceholderSaga;
    private static Method sdu$navComingSoon;
    private static boolean sdu$navPruneReady;
    private static boolean sdu$navPruneFailed;

    // real saved-clone name in a KILL objective line ("Defeat <name>") instead of the bare sdu:dmz_fighter
    // name. objective stores only the entity id, so we correlate by quest: selectedQuest is the line's quest,
    // the KILL ordinal indexes the KILL-ordered clone configs in ClientPreviewClones. only the entity-name
    // substring is rewritten; counters untouched.
    @Inject(method = "getObjectiveText", at = @At("RETURN"), require = 0, remap = false, cancellable = true)
    private void sdu$objectiveCloneName(PlayerQuestData data, String questId, QuestObjective objective,
                                        int index, int required, CallbackInfoReturnable<String> cir) {
        if (!(objective instanceof KillObjective kill) || selectedQuest == null) {
            return;
        }
        String line = cir.getReturnValue();
        if (line == null) {
            return;
        }
        String key = ClientQuestKeys.keyFor(selectedQuest);
        if (key == null) {
            return;
        }
        int killOrdinal = sdu$killOrdinal(objective);
        if (killOrdinal < 0) {
            return;
        }
        CnpcPreviewConfig cfg = ClientPreviewClones.get(key, killOrdinal);
        if (cfg == null || cfg.name() == null || cfg.name().isBlank()) {
            return;
        }
        String defaultName = sdu$entityName(kill.getEntityId());
        if (defaultName != null && !defaultName.isBlank() && line.contains(defaultName)) {
            cir.setReturnValue(line.replace(defaultName, cfg.name()));
        }
    }

    // 0-based index of objective among selectedQuest's KILL objectives, or -1
    private int sdu$killOrdinal(QuestObjective objective) {
        int k = 0;
        for (QuestObjective o : selectedQuest.getObjectives()) {
            boolean isKill = o instanceof KillObjective;
            if (o == objective) {
                return isKill ? k : -1;
            }
            if (isKill) {
                k++;
            }
        }
        return -1;
    }

    // translated display name for an entity id (what the default line shows), or null
    private static String sdu$entityName(String entityId) {
        return EntityType.byString(entityId).map(t -> t.getDescription().getString()).orElse(null);
    }

    // cached reflective handle for DMZ's private questProgressKey(Saga, Quest), used by the sequential-objective
    // filter below. A mismatch latches sdu$objFilterFailed so the objective list falls back to DMZ's full one.
    private static Method sdu$questProgressKeyMethod;
    private static boolean sdu$objFilterReady;
    private static boolean sdu$objFilterFailed;

    // Owner request: for a NON parallel_objectives quest, show only the objective you are currently doing (the
    // first one that is not complete), in order. Once it is done the next one shows. A parallel quest, whose
    // order does not matter, still shows every objective. buildObjectiveRenderLines lists ALL objectives with
    // + / x markers, so we prune it at RETURN to the single active block.
    //
    // "Active" is the first objective whose progress is below its requirement, which is exactly DMZ's own
    // first-uncompleted rule for sequential progress (QuestEvents.isFirstUncompleted / isKillObjectiveUnlocked
    // gate every kind of progress on the objectives before it being done), so the objective we show is always
    // one the server would actually credit right now: the display and the progress logic cannot disagree.
    //
    // We keep DMZ's requirements/header prefix untouched and only drop the non-active objective blocks. An
    // objective's first rendered line starts with the marker "+ " (done) or "x " (to do); its wrapped
    // continuation lines start with two spaces; requirement and header lines are all emitted before the first
    // objective. If the line shape does not match one block per objective (a DMZ format change) we fail open and
    // leave the full list. remap=false (DMZ class), require=0.
    @Inject(method = "buildObjectiveRenderLines", at = @At("RETURN"), require = 0, remap = false, cancellable = true)
    private void sdu$currentObjectiveOnly(Saga saga, int textWidth, CallbackInfoReturnable<List<String>> cir) {
        if (sdu$objFilterFailed) {
            return;
        }
        Quest quest = this.selectedQuest;
        if (quest == null || this.statsData == null || quest.isParallelObjectives()) {
            return; // no quest, nothing to read against, or a parallel quest that must show everything
        }
        List<QuestObjective> objectives = quest.getObjectives();
        if (objectives == null || objectives.size() <= 1) {
            return; // one (or no) objective: nothing to collapse
        }
        List<String> lines = cir.getReturnValue();
        if (lines == null || lines.isEmpty()) {
            return;
        }
        try {
            String questKey = sdu$questProgressKey(saga);
            if (questKey == null) {
                return;
            }
            PlayerQuestData pqd = this.statsData.getPlayerQuestData();
            int active = -1;
            for (int i = 0; i < objectives.size(); i++) {
                if (pqd.getObjectiveProgress(questKey, i) < quest.getObjectiveRequired(pqd, questKey, i)) {
                    active = i;
                    break;
                }
            }
            if (active < 0) {
                return; // every objective complete: leave DMZ's full list (a finished quest reads fine as-is)
            }
            // Everything before the first marker line is the requirements/header prefix; keep it verbatim.
            int firstObj = -1;
            for (int i = 0; i < lines.size(); i++) {
                if (sdu$isObjectiveHead(lines.get(i))) {
                    firstObj = i;
                    break;
                }
            }
            if (firstObj < 0) {
                return; // no objective lines recognised: fail open
            }
            List<List<String>> blocks = new ArrayList<>();
            List<String> current = null;
            for (int i = firstObj; i < lines.size(); i++) {
                String s = lines.get(i);
                if (sdu$isObjectiveHead(s)) {
                    current = new ArrayList<>();
                    blocks.add(current);
                }
                if (current == null) {
                    return; // a continuation before any head: shape unexpected, fail open
                }
                current.add(s);
            }
            if (blocks.size() != objectives.size()) {
                return; // line shape is not one block per objective: fail open, show DMZ's full list
            }
            List<String> out = new ArrayList<>(lines.subList(0, firstObj));
            out.addAll(blocks.get(active));
            cir.setReturnValue(out);
        } catch (Throwable t) {
            sdu$objFilterFailed = true; // DMZ internals differ; leave its objective list alone
            DmzNpc.LOGGER.debug("[{}] sequential objective filter disabled: {}", DmzNpc.MODID, t.toString());
        }
    }

    // An objective's first render line begins with DMZ's + (done) or x (to do) marker; requirement and header
    // lines never do (they use a leading indent, a "- " bullet, or a trailing ":" label).
    private static boolean sdu$isObjectiveHead(String line) {
        return line != null && (line.startsWith("+ ") || line.startsWith("x "));
    }

    // DMZ's private questProgressKey(Saga, Quest) for the selected quest, or null on any reflection mismatch.
    private String sdu$questProgressKey(Saga saga) throws Exception {
        if (!sdu$objFilterReady) {
            Class<?> screen = Class.forName("com.dragonminez.client.gui.character.QuestTreeScreen");
            sdu$questProgressKeyMethod = screen.getDeclaredMethod("questProgressKey", Saga.class, Quest.class);
            sdu$questProgressKeyMethod.setAccessible(true);
            sdu$objFilterReady = true;
        }
        Object key = sdu$questProgressKeyMethod.invoke(this, saga, this.selectedQuest);
        return key instanceof String ? (String) key : null;
    }

    @Inject(method = "getDisplayRewards", at = @At("RETURN"), require = 0, remap = false)
    private void sdu$includeTechniqueRewards(Quest quest, CallbackInfoReturnable<List<QuestReward>> cir) {
        if (quest == null || quest.getRewards() == null) {
            return;
        }
        List<QuestReward> shown = cir.getReturnValue();
        if (shown == null) {
            return;
        }
        for (QuestReward r : quest.getRewards()) {
            if (r instanceof CommandReward cr) {
                String cmd = cr.getCommand();
                // Our TECHNIQUE reward and our custom-NBT ITEM reward both ride a hidden COMMAND; re-add them
                // so the granted technique / tagged item shows in the saga menu rewards list.
                boolean isTechnique = cmd != null && cmd.startsWith("sdu givetechnique");
                boolean isNbtItem = "gui.dmz_ragnarok.quests.rewards.item_nbt".equals(cr.getTranslationKey());
                if ((isTechnique || isNbtItem) && !shown.contains(r)) {
                    shown.add(r);
                }
            }
        }
    }

    // Advertises quest-gated form purchases in the saga GUI rewards list. If selectedQuest gates form buys
    // (via synced FormQuestGateConfig) we append one reward block per gated form at buildRewardBlocks TAIL.
    // Each carries a display-only FormPurchaseReward (type SKILL), so DMZ's render loop draws it with the same
    // blue-capsule icon, wrapped text, scroll and tooltip as real skill rewards ("Purchase of: <form>
    // Unlocks"), and downstream measurement/scroll/hitbox all key off the same list.
    //
    // RewardBlock is a private record and wrapText a private method, both via cached reflection. Any mismatch
    // disables the feature (sdu$formPurchaseFailed), never DMZ's rewards.
    @Inject(method = "buildRewardBlocks", at = @At("TAIL"), require = 0, remap = false)
    @SuppressWarnings("unchecked")
    private void sdu$appendFormPurchaseRewards(int width, CallbackInfoReturnable<List<Object>> cir) {
        if (sdu$formPurchaseFailed || this.selectedQuest == null) {
            return;
        }
        try {
            String questId = sdu$selectedQuestId();
            if (questId == null) {
                return;
            }
            List<String> forms = FormQuestGate.formsGatedByQuest(questId);
            if (forms.isEmpty()) {
                return;
            }
            List<Object> blocks = cir.getReturnValue();
            if (blocks == null) {
                return;
            }
            sdu$ensureFormPurchaseReady();
            int textWidth = Math.max(20, width - 40);
            int lineHeight = Math.max(10, 9 + 1); // mirrors DMZ getDetailLineHeight()
            int iconSize = 16;
            for (String formSkill : forms) {
                FormPurchaseReward reward = new FormPurchaseReward(formSkill);
                String desc = reward.getDescription().getString();
                List<String> lines = (List<String>) sdu$wrapText.invoke(this, desc, textWidth);
                if (lines == null || lines.isEmpty()) {
                    lines = List.of(desc);
                }
                int blockH = Math.max(iconSize + 2, lines.size() * lineHeight) + 4;
                Object block = sdu$rewardBlockCtor.newInstance(reward, lines, blockH, (Component) null, false, 0);
                blocks.add(block);
            }
        } catch (Throwable t) {
            sdu$formPurchaseFailed = true; // DMZ internals differ; disable, don't break its rewards list
            DmzNpc.LOGGER.debug("[{}] form-purchase reward rows disabled: {}", DmzNpc.MODID, t.toString());
        }
    }

    // Makes the appended form-purchase rows render INLINE (always visible), not just in the hover tooltip.
    // renderRewardsSection clips each block's lines to a running typewriter reveal whose length comes from
    // buildRewardsText(getDisplayRewards(...)), built from the quest's REAL rewards only. Our synthetic
    // FormPurchaseReward blocks aren't in it, so the revealedChars - consumedChars budget runs out before our
    // rows and truncates them to nothing (icon-only, tooltip still full). Appending each gated form's
    // description here in the SAME order + SAME '\n' separator the render loop counts (desc.length() + 1)
    // grows the budget to cover our rows. Icon + tooltip untouched.
    //
    // buildRewardsText only feeds the typewriter in renderRewardsSection (section sizing uses
    // buildRewardBlocks directly), so this changes reveal accounting only. Shares sdu$formPurchaseFailed.
    @Inject(method = "buildRewardsText", at = @At("RETURN"), require = 0, remap = false, cancellable = true)
    private void sdu$revealFormPurchaseText(List<QuestReward> rewards, CallbackInfoReturnable<String> cir) {
        if (sdu$formPurchaseFailed || this.selectedQuest == null) {
            return;
        }
        try {
            String questId = sdu$selectedQuestId();
            if (questId == null) {
                return;
            }
            List<String> forms = FormQuestGate.formsGatedByQuest(questId);
            if (forms.isEmpty()) {
                return;
            }
            String base = cir.getReturnValue();
            StringBuilder sb = new StringBuilder(base == null ? "" : base);
            for (String formSkill : forms) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(new FormPurchaseReward(formSkill).getDescription().getString());
            }
            cir.setReturnValue(sb.toString());
        } catch (Throwable t) {
            sdu$formPurchaseFailed = true;
            DmzNpc.LOGGER.debug("[{}] form-purchase inline reveal disabled: {}", DmzNpc.MODID, t.toString());
        }
    }

    // rebuilds the config questId for selectedQuest, matching DMZ's private questProgressKey
    private String sdu$selectedQuestId() {
        Quest quest = this.selectedQuest;
        if (quest == null) {
            return null;
        }
        if (quest.isSideQuest() && quest.getStringId() != null) {
            return quest.getStringId();
        }
        if (this.availableSagas == null || this.availableSagas.isEmpty()
                || this.currentSagaIndex < 0 || this.currentSagaIndex >= this.availableSagas.size()) {
            return null;
        }
        Saga saga = this.availableSagas.get(this.currentSagaIndex);
        if (saga == null || saga.getId() == null) {
            return null;
        }
        return PlayerQuestData.sagaQuestKey(saga.getId(), quest.getId());
    }

    // strips the hardcoded catalog placeholder rows for sagas with NO loaded data, so removing a saga's json
    // removes it from the screen. runs at TAIL, after DMZ built navigatorEntries from SAGA_CATALOG + loaded
    // sagas. a placeholder row is a NavEntryType.SAGA entry with a null saga field; DMZ's own
    // NavigatorEntry.isPlaceholderSaga() encodes exactly that test. comingSoon teasers are also placeholders,
    // pruned alongside removed base sagas unless SDU_KEEP_COMING_SOON_TEASERS is on.
    //
    // currentSagaIndex indexes availableSagas, not navigatorEntries, so pruning render rows here can't desync
    // the selection; no clamp needed. remap=false, require=0. any reflection mismatch trips sdu$navPruneFailed
    // and leaves DMZ's list untouched.
    @Inject(method = "rebuildNavigatorEntries", at = @At("TAIL"), require = 0, remap = false)
    private void sdu$pruneRemovedSagaRows(CallbackInfo ci) {
        if (sdu$navPruneFailed || this.navigatorEntries == null || this.navigatorEntries.isEmpty()) {
            return;
        }
        try {
            sdu$ensureNavPruneReady();
            java.util.Iterator<?> it = this.navigatorEntries.iterator();
            while (it.hasNext()) {
                Object entry = it.next();
                if (entry == null) {
                    continue;
                }
                boolean placeholder = (Boolean) sdu$isPlaceholderSaga.invoke(entry);
                if (!placeholder) {
                    continue; // real loaded saga or a non-saga nav row (quests, secrets): leave it
                }
                if (SDU_KEEP_COMING_SOON_TEASERS && (Boolean) sdu$navComingSoon.invoke(entry)) {
                    continue; // teaser row and the keep-flag is on, so leave it visible
                }
                it.remove(); // a base saga whose json was deleted: gone from the screen
            }
        } catch (Throwable t) {
            sdu$navPruneFailed = true; // DMZ internals differ; disable, don't break its navigator
            DmzNpc.LOGGER.debug("[{}] saga-row prune disabled: {}", DmzNpc.MODID, t.toString());
        }
    }

    // resolves + caches the private NavigatorEntry.isPlaceholderSaga() and comingSoon() handles once
    private void sdu$ensureNavPruneReady() throws Exception {
        if (sdu$navPruneReady) {
            return;
        }
        Class<?> navEntry = Class.forName("com.dragonminez.client.gui.character.QuestTreeScreen$NavigatorEntry");
        sdu$isPlaceholderSaga = navEntry.getDeclaredMethod("isPlaceholderSaga");
        sdu$isPlaceholderSaga.setAccessible(true);
        sdu$navComingSoon = navEntry.getDeclaredMethod("comingSoon");
        sdu$navComingSoon.setAccessible(true);
        sdu$navPruneReady = true;
    }

    // resolves + caches DMZ's private RewardBlock ctor and the wrapText method
    private void sdu$ensureFormPurchaseReady() throws Exception {
        if (sdu$formPurchaseReady) {
            return;
        }
        Class<?> screen = Class.forName("com.dragonminez.client.gui.character.QuestTreeScreen");
        Class<?> rewardBlock = Class.forName("com.dragonminez.client.gui.character.QuestTreeScreen$RewardBlock");
        sdu$rewardBlockCtor = rewardBlock.getDeclaredConstructor(
                QuestReward.class, List.class, int.class, Component.class, boolean.class, int.class);
        sdu$rewardBlockCtor.setAccessible(true);
        sdu$wrapText = screen.getDeclaredMethod("wrapText", String.class, int.class);
        sdu$wrapText.setAccessible(true);
        sdu$formPurchaseReady = true;
    }

    // Prestige gate on HARD, client half (server half is SetStoryDifficultyC2SMixin; both ask HardSagaGate so
    // they never disagree). HARD stays VISIBLE but greyed with a padlock when the local player has never
    // prestiged, because the picker is a fixed-index 3-box modal whose layout would break if a row were
    // removed. Eligibility is HardSagaGate.clientMayUseHard(), pushed by SU on login / prestige / slot switch;
    // defaults true, so the option shows normally until SU says otherwise (and always when sdu runs without SU).

    // blocks selecting the HARD box for an ineligible player. handleDifficultySelectClick is DMZ's single
    // funnel for the picker, so consuming the click here (return true, no SetStoryDifficultyC2S sent) is the
    // whole client guard; the crafted-packet case is caught server-side.
    @Inject(method = "handleDifficultySelectClick", at = @At("HEAD"), require = 0, remap = false, cancellable = true)
    private void sdu$blockHardDifficultyClick(double uiMouseX, double uiMouseY, int button,
                                              CallbackInfoReturnable<Boolean> cir) {
        if (HardSagaGate.clientMayUseHard()) {
            return;
        }
        try {
            if (!this.shouldShowDifficultySelect()) {
                return;
            }
            Object rect = sdu$hardOptionRect();
            if (rect == null || !(Boolean) sdu$rectContains.invoke(rect, uiMouseX, uiMouseY)) {
                return;
            }
            Minecraft.getInstance().getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.get(), 0.7F));
            cir.setReturnValue(true); // consume the click; HARD is not sent
        } catch (Throwable t) {
            sdu$diffGateFailed = true; // DMZ internals differ; leave the picker fully working
            DmzNpc.LOGGER.debug("[{}] hard-difficulty click gate disabled: {}", DmzNpc.MODID, t.toString());
        }
    }

    // draws the grey veil + padlock over the HARD box and a "Requires Prestige 1" tooltip on hover, at TAIL so
    // DMZ has already drawn the box (mouse and rects are in the same UI-scaled space this method draws in).
    @Inject(method = "renderDifficultySelectOverlay", at = @At("TAIL"), require = 0, remap = false)
    private void sdu$lockHardDifficultyOverlay(GuiGraphics graphics, int mouseX, int mouseY, CallbackInfo ci) {
        if (HardSagaGate.clientMayUseHard()) {
            return;
        }
        try {
            Object rect = sdu$hardOptionRect();
            if (rect == null) {
                return;
            }
            int left = (Integer) sdu$rectX.invoke(rect);
            int top = (Integer) sdu$rectY.invoke(rect);
            int right = (Integer) sdu$rectRight.invoke(rect);
            int bottom = (Integer) sdu$rectBottom.invoke(rect);

            graphics.pose().pushPose();
            graphics.pose().translate(0.0D, 0.0D, 200.0D);
            graphics.fill(left, top, right, bottom, 0x99101010);
            sdu$drawPadlock(graphics, (left + right) / 2, (top + bottom) / 2);
            graphics.pose().popPose();

            if ((Boolean) sdu$rectContains.invoke(rect, (double) mouseX, (double) mouseY)) {
                graphics.renderComponentTooltip(
                        Minecraft.getInstance().font,
                        List.of(Component.literal("§cRequires Prestige 1")),
                        mouseX, mouseY);
            }
        } catch (Throwable t) {
            sdu$diffGateFailed = true;
            DmzNpc.LOGGER.debug("[{}] hard-difficulty lock overlay disabled: {}", DmzNpc.MODID, t.toString());
        }
    }

    // the PanelRect for the HARD row, or null if the row / handles can't be resolved. reflection because PanelRect
    // is a private nested record; handles cached once.
    private Object sdu$hardOptionRect() throws Exception {
        if (sdu$diffGateFailed || DIFFICULTY_OPTIONS == null) {
            return null;
        }
        int hardIndex = -1;
        for (int i = 0; i < DIFFICULTY_OPTIONS.length; i++) {
            if (DIFFICULTY_OPTIONS[i] == Difficulty.HARD) {
                hardIndex = i;
                break;
            }
        }
        if (hardIndex < 0) {
            return null;
        }
        sdu$ensureDiffGateReady();
        return sdu$getDifficultyOptionRect.invoke(this, hardIndex);
    }

    // resolves + caches DMZ's private getDifficultyOptionRect and the PanelRect record accessors
    private void sdu$ensureDiffGateReady() throws Exception {
        if (sdu$diffGateReady) {
            return;
        }
        Class<?> screen = Class.forName("com.dragonminez.client.gui.character.QuestTreeScreen");
        Class<?> panelRect = Class.forName("com.dragonminez.client.gui.character.QuestTreeScreen$PanelRect");
        sdu$getDifficultyOptionRect = screen.getDeclaredMethod("getDifficultyOptionRect", int.class);
        sdu$getDifficultyOptionRect.setAccessible(true);
        sdu$rectContains = panelRect.getDeclaredMethod("contains", double.class, double.class);
        sdu$rectContains.setAccessible(true);
        sdu$rectX = panelRect.getDeclaredMethod("x");
        sdu$rectX.setAccessible(true);
        sdu$rectY = panelRect.getDeclaredMethod("y");
        sdu$rectY.setAccessible(true);
        sdu$rectRight = panelRect.getDeclaredMethod("right");
        sdu$rectRight.setAccessible(true);
        sdu$rectBottom = panelRect.getDeclaredMethod("bottom");
        sdu$rectBottom.setAccessible(true);
        sdu$diffGateReady = true;
    }

    // Addon saga prerequisite: a saga may be locked until a NAMED (saga, quest) pair is completed, ANDed with
    // DMZ's native previous-saga gate. DMZ evaluates the saga lock here in isSagaUnlockedByPreviousCompletion,
    // so we AND our gate in at RETURN: if DMZ says unlocked but our gated quest is not complete, force locked.
    // The gate map is synced from the server (SagaQuestGateConfig).
    //
    // Fail OPEN on an unresolved reference (missing saga/quest id in the client registry): never keep a saga
    // permanently locked over a bad reference (the silent-lock trap). remap=false, require=0.
    @Inject(method = "isSagaUnlockedByPreviousCompletion", at = @At("RETURN"), require = 0, remap = false,
            cancellable = true)
    private void sdu$sagaQuestGate(Saga saga, CallbackInfoReturnable<Boolean> cir) {
        if (!Boolean.TRUE.equals(cir.getReturnValue()) || saga == null || this.statsData == null) {
            return; // DMZ already locks it, or nothing to evaluate against
        }
        try {
            // same rule the server enforces (SagaGate), so screen lock and server refusal can't disagree.
            // client resolves sagas from the synced client registry.
            if (!SagaGate.questGateSatisfied(saga, this.statsData.getPlayerQuestData(),
                    id -> QuestRegistry.getClientSagas().get(id))) {
                cir.setReturnValue(false); // gating quest not done: saga stays locked
            }
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] saga quest-gate check disabled: {}", DmzNpc.MODID, t.toString());
        }
    }

    // Names the gating quest in the saga's lock tooltip when OUR quest gate is the blocker, so a quest-locked
    // saga is never a silent "[L]". DMZ's getSagaLockTooltip only describes the previous-saga gate (null for a
    // saga blocked solely by our quest gate), so we supply the reason at HEAD.
    @Inject(method = "getSagaLockTooltip", at = @At("HEAD"), require = 0, remap = false, cancellable = true)
    private void sdu$sagaQuestGateTooltip(Saga saga, CallbackInfoReturnable<Component> cir) {
        if (saga == null || this.statsData == null) {
            return;
        }
        try {
            SagaQuestGateConfig.Gate gate = SagaQuestGateConfig.get(saga.getId());
            if (gate == null || !gate.isValid()) {
                return;
            }
            Saga target = QuestRegistry.getClientSagas().get(gate.gateSaga);
            if (target == null) {
                return;
            }
            Quest gq = target.getQuestById(gate.gateQuest);
            if (gq == null) {
                return; // unresolved: fail open (no lock, no tooltip)
            }
            String key = PlayerQuestData.sagaQuestKey(gate.gateSaga, gate.gateQuest);
            if (this.statsData.getPlayerQuestData().isQuestCompleted(key)) {
                return; // our gate is satisfied; let DMZ describe any remaining previous-saga gate
            }
            String title = gq.getTitle();
            Component name = title != null && !title.isBlank()
                    ? Component.translatable(title) : Component.literal("#" + gate.gateQuest);
            cir.setReturnValue(Component.translatable("gui.dmz_ragnarok.quest_tree.saga_locked_quest.tooltip", name));
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] saga quest-gate tooltip disabled: {}", DmzNpc.MODID, t.toString());
        }
    }

    // small vector padlock at (cx, cy) from filled rects, matching RaceSelectionScreenMixin's lock veil style
    private static void sdu$drawPadlock(GuiGraphics graphics, int cx, int cy) {
        int bodyColor = 0xFFDDDDDD;
        int shackleColor = 0xFFBBBBBB;
        int bw = 10;
        int bh = 8;
        int bx = cx - bw / 2;
        int by = cy - 1;
        graphics.fill(bx, by, bx + bw, by + bh, bodyColor);
        graphics.fill(cx - 1, by + 2, cx + 1, by + 6, 0xFF303030);
        int shTop = by - 6;
        graphics.fill(cx - 4, shTop, cx - 2, by, shackleColor);
        graphics.fill(cx + 2, shTop, cx + 4, by, shackleColor);
        graphics.fill(cx - 4, shTop, cx + 4, shTop + 2, shackleColor);
    }
}
