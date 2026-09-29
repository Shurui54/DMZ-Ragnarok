package net.shurui.dev.sdu.form;

import com.dragonminez.common.quest.QuestReward;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Display-only synthetic {@link QuestReward} injected client-side into DMZ's quest GUI, so a quest that
 * gates a form purchase ({@link FormQuestGateConfig}) shows a "Purchase of: &lt;form&gt; Unlocks" line. Never
 * part of a real reward set and never reaches the server: {@link #giveReward} is a no-op. Reports
 * {@link RewardType#SKILL} so DMZ draws its blue-capsule skill icon and formats the row like a real reward.
 *
 * <p>The gate is enforced server-side ({@code UpdateSkillC2SMixin}); this only advertises it. Carries the
 * gated form-skill name so {@code QuestTreeScreenMixin}'s reverse lookup can build one per gated form.
 */
public final class FormPurchaseReward extends QuestReward {

    /** Lang key for the reward line. Arg %s = the gated form's display name. */
    public static final String LABEL_KEY = "reward.dmz_ragnarok.npc.form_purchase";

    private final String formSkill;

    public FormPurchaseReward(String formSkill) {
        super(RewardType.SKILL);
        this.formSkill = formSkill == null ? "" : formSkill;
    }

    /** The DMZ form-skill name this line advertises (e.g. {@code superform}). */
    public String formSkill() {
        return this.formSkill;
    }

    /** Display-only: this reward is never granted (the gate only unlocks the ability to BUY the form). */
    @Override
    public void giveReward(ServerPlayer player) {
        // no-op
    }

    @Override
    public Component getDescription() {
        return Component.translatable(LABEL_KEY, FormQuestGate.formDisplayName(this.formSkill));
    }
}
