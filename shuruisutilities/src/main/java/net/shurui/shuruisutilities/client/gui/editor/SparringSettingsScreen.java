package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

/**
 * Sparring Settings editor, reached from the Shurui's Utilities admin hub (never the SDU editor). Tabbed so the
 * whole {@link net.shurui.shuruisutilities.sparring.SparConfig} fits. meta is a fixed-order snapshot built by
 * {@code HubRowSparring.sparringMeta()}; Save sends the same order back to {@code HubRowSparring.handleSparring()}.
 */
public class SparringSettingsScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final String[] SECTION_KEYS = {
            "gui.dmz_ragnarok.core.spar.tab_general",
            "gui.dmz_ragnarok.core.spar.tab_timing",
            "gui.dmz_ragnarok.core.spar.tab_rewards",
            "gui.dmz_ragnarok.core.spar.tab_scaling",
            "gui.dmz_ragnarok.core.spar.tab_healing" };

    private int section = 0;

    // booleans
    private boolean enabled, allowGuild, allowParty, requireBase, scaleProgression, blockHealing, blockFood;
    // numeric text fields
    private String maxSep, inviteExpire, cooldown, idleTimeout, maxDuration;
    private String tpPerBar, tpPerMinute, tpTransfer, perSparCap, globalMult;
    private String closenessExp, minCloseness, rewardCoeff, rewardExp, flatTpPerPoint;
    private String extraIds;

    public SparringSettingsScreen(List<String> m)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.sparringsettings"), UI_W, UI_H, null);
        enabled = b(m, 0, true);
        allowGuild = b(m, 1, true);
        allowParty = b(m, 2, true);
        requireBase = b(m, 3, true);
        maxSep = s(m, 4, "64.0");
        inviteExpire = s(m, 5, "30");
        cooldown = s(m, 6, "600");
        idleTimeout = s(m, 7, "20");
        maxDuration = s(m, 8, "300");
        tpPerBar = s(m, 9, "3.0");
        tpPerMinute = s(m, 10, "2.0");
        tpTransfer = s(m, 11, "25.0");
        perSparCap = s(m, 12, "40.0");
        globalMult = s(m, 13, "1.0");
        closenessExp = s(m, 14, "2.0");
        minCloseness = s(m, 15, "0.34");
        scaleProgression = b(m, 16, true);
        rewardCoeff = s(m, 17, "3.4");
        rewardExp = s(m, 18, "0.6");
        flatTpPerPoint = s(m, 19, "5000");
        blockHealing = b(m, 20, true);
        blockFood = b(m, 21, false);
        extraIds = s(m, 22, "");
    }

    private static String s(List<String> m, int i, String def)
    {
        return m.size() > i ? m.get(i) : def;
    }

    private static boolean b(List<String> m, int i, boolean def)
    {
        return m.size() > i ? Boolean.parseBoolean(m.get(i)) : def;
    }

    private void selectSection(int sec)
    {
        applyFields();
        section = sec;
        rebuildWidgets();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        rowY = buildTabHeader(null, trAll(SECTION_KEYS), section, this::selectSection);

        switch (section)
        {
            case 0 ->
            {
                bf(tr("gui.dmz_ragnarok.core.spar.enabled"), enabled, () -> enabled = !enabled);
                tip(tr("gui.dmz_ragnarok.core.spar.enabled_tip"));
                bf(tr("gui.dmz_ragnarok.core.spar.allow_guild"), allowGuild, () -> allowGuild = !allowGuild);
                tip(tr("gui.dmz_ragnarok.core.spar.allow_guild_tip"));
                bf(tr("gui.dmz_ragnarok.core.spar.allow_party"), allowParty, () -> allowParty = !allowParty);
                tip(tr("gui.dmz_ragnarok.core.spar.allow_party_tip"));
                bf(tr("gui.dmz_ragnarok.core.spar.require_base"), requireBase, () -> requireBase = !requireBase);
                tip(tr("gui.dmz_ragnarok.core.spar.require_base_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.max_sep"), maxSep, v -> maxSep = v);
                tip(tr("gui.dmz_ragnarok.core.spar.max_sep_tip"));
            }
            case 1 ->
            {
                tf(tr("gui.dmz_ragnarok.core.spar.invite_expire"), inviteExpire, v -> inviteExpire = v);
                tip(tr("gui.dmz_ragnarok.core.spar.invite_expire_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.cooldown"), cooldown, v -> cooldown = v);
                tip(tr("gui.dmz_ragnarok.core.spar.cooldown_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.idle_timeout"), idleTimeout, v -> idleTimeout = v);
                tip(tr("gui.dmz_ragnarok.core.spar.idle_timeout_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.max_duration"), maxDuration, v -> maxDuration = v);
                tip(tr("gui.dmz_ragnarok.core.spar.max_duration_tip"));
            }
            case 2 ->
            {
                tf(tr("gui.dmz_ragnarok.core.spar.tp_per_bar"), tpPerBar, v -> tpPerBar = v);
                tip(tr("gui.dmz_ragnarok.core.spar.tp_per_bar_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.tp_per_minute"), tpPerMinute, v -> tpPerMinute = v);
                tip(tr("gui.dmz_ragnarok.core.spar.tp_per_minute_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.tp_transfer"), tpTransfer, v -> tpTransfer = v);
                tip(tr("gui.dmz_ragnarok.core.spar.tp_transfer_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.per_spar_cap"), perSparCap, v -> perSparCap = v);
                tip(tr("gui.dmz_ragnarok.core.spar.per_spar_cap_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.global_mult"), globalMult, v -> globalMult = v);
                tip(tr("gui.dmz_ragnarok.core.spar.global_mult_tip"));
            }
            case 3 ->
            {
                tf(tr("gui.dmz_ragnarok.core.spar.closeness_exp"), closenessExp, v -> closenessExp = v);
                tip(tr("gui.dmz_ragnarok.core.spar.closeness_exp_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.min_closeness"), minCloseness, v -> minCloseness = v);
                tip(tr("gui.dmz_ragnarok.core.spar.min_closeness_tip"));
                bf(tr("gui.dmz_ragnarok.core.spar.scale_progression"), scaleProgression, () -> scaleProgression = !scaleProgression);
                tip(tr("gui.dmz_ragnarok.core.spar.scale_progression_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.reward_coeff"), rewardCoeff, v -> rewardCoeff = v);
                tip(tr("gui.dmz_ragnarok.core.spar.reward_coeff_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.reward_exp"), rewardExp, v -> rewardExp = v);
                tip(tr("gui.dmz_ragnarok.core.spar.reward_exp_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.flat_tp"), flatTpPerPoint, v -> flatTpPerPoint = v);
                tip(tr("gui.dmz_ragnarok.core.spar.flat_tp_tip"));
            }
            default ->
            {
                bf(tr("gui.dmz_ragnarok.core.spar.block_healing"), blockHealing, () -> blockHealing = !blockHealing);
                tip(tr("gui.dmz_ragnarok.core.spar.block_healing_tip"));
                bf(tr("gui.dmz_ragnarok.core.spar.block_food"), blockFood, () -> blockFood = !blockFood);
                tip(tr("gui.dmz_ragnarok.core.spar.block_food_tip"));
                tf(tr("gui.dmz_ragnarok.core.spar.extra_ids"), extraIds, v -> extraIds = v);
                tip(tr("gui.dmz_ragnarok.core.spar.extra_ids_tip"));
            }
        }

        btn(14, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void save()
    {
        applyFields();
        EditorScreens.act("sparringsettings", "save",
                Boolean.toString(enabled),
                Boolean.toString(allowGuild),
                Boolean.toString(allowParty),
                Boolean.toString(requireBase),
                maxSep.trim(),
                inviteExpire.trim(),
                cooldown.trim(),
                idleTimeout.trim(),
                maxDuration.trim(),
                tpPerBar.trim(),
                tpPerMinute.trim(),
                tpTransfer.trim(),
                perSparCap.trim(),
                globalMult.trim(),
                closenessExp.trim(),
                minCloseness.trim(),
                Boolean.toString(scaleProgression),
                rewardCoeff.trim(),
                rewardExp.trim(),
                flatTpPerPoint.trim(),
                Boolean.toString(blockHealing),
                Boolean.toString(blockFood),
                extraIds.trim());
    }
}
