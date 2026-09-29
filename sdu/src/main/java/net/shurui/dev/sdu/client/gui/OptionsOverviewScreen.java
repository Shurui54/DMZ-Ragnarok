package net.shurui.dev.sdu.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.OpenEditorRequestPacket;
import net.shurui.dev.sdu.network.SaveConfigPacket;

/**
 * One-stop "everything you can configure" screen, reached from {@link SduHubScreen}. Top group: the four
 * editable server-config options (a C2S {@link SaveConfigPacket} applies + saves them, op-gated). Bottom
 * group: a read-only directory of editor areas, with the two that have a direct open path (Forms/Races)
 * clickable to jump there.
 *
 * <p>Values are seeded from the server (the addon's config is COMMON, not auto-synced), pushed in the
 * {@code OpenOptionsPacket} that opens this screen.
 */
public class OptionsOverviewScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] SECTIONS = {"gui.dmz_ragnarok.npc.options.tab.settings", "gui.dmz_ragnarok.npc.options.tab.directory"};

    private boolean requireOpToEdit;
    private int maxNpcHealth;
    private boolean enableDmzIntegration;
    private boolean enableAuraStacking;
    private int shadowDummyCooldownSeconds;
    private int shadowDummyMaxAlivePerParty;
    private int partyTpFalloffThreshold;
    private int partyTpFalloffStepPercent;
    private int partyTpFalloffFloorPercent;
    private int section;
    // Vertical scroll for the (potentially tall) section content uses the inherited SagaBaseScreen band.

    private OptionsOverviewScreen(boolean requireOpToEdit, int maxNpcHealth,
                                  boolean enableDmzIntegration, boolean enableAuraStacking,
                                  int shadowDummyCooldownSeconds, int shadowDummyMaxAlivePerParty,
                                  int partyTpFalloffThreshold, int partyTpFalloffStepPercent,
                                  int partyTpFalloffFloorPercent) {
        super(Component.translatable("gui.dmz_ragnarok.npc.options.title"), UI_W, UI_H, null);
        this.requireOpToEdit = requireOpToEdit;
        this.maxNpcHealth = maxNpcHealth;
        this.enableDmzIntegration = enableDmzIntegration;
        this.enableAuraStacking = enableAuraStacking;
        this.shadowDummyCooldownSeconds = shadowDummyCooldownSeconds;
        this.shadowDummyMaxAlivePerParty = shadowDummyMaxAlivePerParty;
        this.partyTpFalloffThreshold = partyTpFalloffThreshold;
        this.partyTpFalloffStepPercent = partyTpFalloffStepPercent;
        this.partyTpFalloffFloorPercent = partyTpFalloffFloorPercent;
    }

    /** Open the Options overview on the client (invoked by {@code OpenOptionsPacket}). */
    public static void open(boolean requireOpToEdit, int maxNpcHealth,
                            boolean enableDmzIntegration, boolean enableAuraStacking,
                            int shadowDummyCooldownSeconds, int shadowDummyMaxAlivePerParty,
                            int partyTpFalloffThreshold, int partyTpFalloffStepPercent,
                            int partyTpFalloffFloorPercent) {
        Minecraft.getInstance().setScreen(new OptionsOverviewScreen(
                requireOpToEdit, maxNpcHealth, enableDmzIntegration, enableAuraStacking,
                shadowDummyCooldownSeconds, shadowDummyMaxAlivePerParty,
                partyTpFalloffThreshold, partyTpFalloffStepPercent, partyTpFalloffFloorPercent));
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        int belowTabs = buildTabHeader(tr("gui.dmz_ragnarok.npc.options.subtitle"), trAll(SECTIONS), section, this::selectSection);
        int contentTop = belowTabs;
        int contentBottom = UI_H - 30;

        beginScrollBand(contentTop, contentBottom);
        rowY = contentTop - scroll;
        buildSection();
        finishScrollBand(contentTop, contentBottom, rowY);

        if (section == 0) {
            btn(UI_W / 2 - 118, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.options.save"),
                    () -> { applyFields(); save(); });
        }
        btn(UI_W / 2 + 8, footerY(), 110, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.menu"),
                () -> minecraft.setScreen(new SduHubScreen()));
    }

    private void selectSection(int i) {
        applyFields();
        section = i;
        scroll = 0;
        rebuildWidgets();
    }

    private void buildSection() {
        if (section == 0) {
            buildSettings();
        } else {
            buildDirectory();
        }
    }

    private void buildSettings() {
        label("§e" + tr("gui.dmz_ragnarok.npc.options.hdr_server"), 14, rowY);
        rowY += ROW_H;
        bf(tr("gui.dmz_ragnarok.npc.options.require_op"), requireOpToEdit, () -> requireOpToEdit = !requireOpToEdit);
        tip(tr("gui.dmz_ragnarok.npc.options.t_require_op"));
        tf(tr("gui.dmz_ragnarok.npc.options.max_npc_health"), intStr(maxNpcHealth),
                v -> maxNpcHealth = clampHealth(parseI(v, maxNpcHealth)));
        tip(tr("gui.dmz_ragnarok.npc.options.t_max_npc_health"));
        bf(tr("gui.dmz_ragnarok.npc.options.dmz_integration"), enableDmzIntegration, () -> enableDmzIntegration = !enableDmzIntegration);
        tip(tr("gui.dmz_ragnarok.npc.options.t_dmz_integration"));
        bf(tr("gui.dmz_ragnarok.npc.options.aura_stacking"), enableAuraStacking, () -> enableAuraStacking = !enableAuraStacking);
        tip(tr("gui.dmz_ragnarok.npc.options.t_aura_stacking"));

        rowY += 4;
        label("§e" + tr("gui.dmz_ragnarok.npc.options.hdr_shadow_training"), 14, rowY);
        rowY += ROW_H;
        tf(tr("gui.dmz_ragnarok.npc.options.shadow_cooldown"), intStr(shadowDummyCooldownSeconds),
                v -> shadowDummyCooldownSeconds = clampShadowCooldown(parseI(v, shadowDummyCooldownSeconds)));
        tip(tr("gui.dmz_ragnarok.npc.options.t_shadow_cooldown"));
        tf(tr("gui.dmz_ragnarok.npc.options.shadow_max_alive"), intStr(shadowDummyMaxAlivePerParty),
                v -> shadowDummyMaxAlivePerParty = clampShadowMaxAlive(parseI(v, shadowDummyMaxAlivePerParty)));
        tip(tr("gui.dmz_ragnarok.npc.options.t_shadow_max_alive"));

        rowY += 4;
        label("§e" + tr("gui.dmz_ragnarok.npc.options.hdr_party_tp"), 14, rowY);
        rowY += ROW_H;
        tf(tr("gui.dmz_ragnarok.npc.options.tp_falloff_threshold"), intStr(partyTpFalloffThreshold),
                v -> partyTpFalloffThreshold = clampTpThreshold(parseI(v, partyTpFalloffThreshold)));
        tip(tr("gui.dmz_ragnarok.npc.options.t_tp_falloff_threshold"));
        tf(tr("gui.dmz_ragnarok.npc.options.tp_falloff_step"), intStr(partyTpFalloffStepPercent),
                v -> partyTpFalloffStepPercent = clampTpPercent(parseI(v, partyTpFalloffStepPercent)));
        tip(tr("gui.dmz_ragnarok.npc.options.t_tp_falloff_step"));
        tf(tr("gui.dmz_ragnarok.npc.options.tp_falloff_floor"), intStr(partyTpFalloffFloorPercent),
                v -> partyTpFalloffFloorPercent = clampTpPercent(parseI(v, partyTpFalloffFloorPercent)));
        tip(tr("gui.dmz_ragnarok.npc.options.t_tp_falloff_floor"));

        rowY += 4;
        label("§7" + tr("gui.dmz_ragnarok.npc.options.save_note"), 14, rowY);
        rowY += ROW_H;
    }

    private int clampHealth(int v) {
        return Math.max(net.shurui.dev.sdu.Config.MAX_NPC_HEALTH_MIN,
                Math.min(net.shurui.dev.sdu.Config.MAX_NPC_HEALTH_MAX, v));
    }

    private int clampShadowCooldown(int v) {
        return Math.max(net.shurui.dev.sdu.Config.SHADOW_DUMMY_COOLDOWN_SECONDS_MIN,
                Math.min(net.shurui.dev.sdu.Config.SHADOW_DUMMY_COOLDOWN_SECONDS_MAX, v));
    }

    private int clampShadowMaxAlive(int v) {
        return Math.max(net.shurui.dev.sdu.Config.SHADOW_DUMMY_MAX_ALIVE_PER_PARTY_MIN,
                Math.min(net.shurui.dev.sdu.Config.SHADOW_DUMMY_MAX_ALIVE_PER_PARTY_MAX, v));
    }

    private int clampTpThreshold(int v) {
        return Math.max(net.shurui.dev.sdu.Config.PARTY_TP_FALLOFF_THRESHOLD_MIN,
                Math.min(net.shurui.dev.sdu.Config.PARTY_TP_FALLOFF_THRESHOLD_MAX, v));
    }

    private int clampTpPercent(int v) {
        return Math.max(net.shurui.dev.sdu.Config.PARTY_TP_FALLOFF_PERCENT_MIN,
                Math.min(net.shurui.dev.sdu.Config.PARTY_TP_FALLOFF_PERCENT_MAX, v));
    }

    private void save() {
        DmzNet.sendToServer(new SaveConfigPacket(requireOpToEdit, maxNpcHealth, enableDmzIntegration, enableAuraStacking,
                shadowDummyCooldownSeconds, shadowDummyMaxAlivePerParty,
                partyTpFalloffThreshold, partyTpFalloffStepPercent, partyTpFalloffFloorPercent));
    }

    /** A directory row: a label plus a hover tip, optionally clickable to open the named editor area. */
    private void dirRow(String labelKey, String tipKey, Runnable open) {
        label("§b" + tr(labelKey), 14, rowY + 2);
        if (open != null) {
            // The action sends an open-request packet; the editor the server opens back (a tick or more later)
            // plays the one navigation sound, so this initiator stays silent. Right-aligned to rowControlRight()
            // so it clears the panel edge and scroll gutter (the old x=250 ran off the 300-wide panel).
            btn(rowControlRight() - 78, rowY, 78, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.npc.btn.open"), open).opensScreen();
        }
        tooltip(12, rowY, 236, ROW_H, tr(tipKey));
        rowY += ROW_H + 2;
    }

    private void buildDirectory() {
        label("§e" + tr("gui.dmz_ragnarok.npc.options.hdr_directory"), 14, rowY);
        rowY += ROW_H + 2;
        dirRow("gui.dmz_ragnarok.npc.options.dir_forms", "gui.dmz_ragnarok.npc.options.t_dir_forms",
                () -> DmzNet.sendToServer(new OpenEditorRequestPacket("form")));
        dirRow("gui.dmz_ragnarok.npc.options.dir_form_types", "gui.dmz_ragnarok.npc.options.t_dir_form_types", null);
        dirRow("gui.dmz_ragnarok.npc.options.dir_races", "gui.dmz_ragnarok.npc.options.t_dir_races",
                () -> DmzNet.sendToServer(new OpenEditorRequestPacket("race")));
        dirRow("gui.dmz_ragnarok.npc.options.dir_classes", "gui.dmz_ragnarok.npc.options.t_dir_classes", null);
        dirRow("gui.dmz_ragnarok.npc.options.dir_racials", "gui.dmz_ragnarok.npc.options.t_dir_racials", null);
        dirRow("gui.dmz_ragnarok.npc.options.dir_sagas", "gui.dmz_ragnarok.npc.options.t_dir_sagas",
                () -> DmzNet.sendToServer(new OpenEditorRequestPacket("saga")));
        dirRow("gui.dmz_ragnarok.npc.options.dir_sidequests", "gui.dmz_ragnarok.npc.options.t_dir_sidequests",
                () -> DmzNet.sendToServer(new OpenEditorRequestPacket("sidequest")));
        dirRow("gui.dmz_ragnarok.npc.options.dir_wishes", "gui.dmz_ragnarok.npc.options.t_dir_wishes",
                () -> DmzNet.sendToServer(new OpenEditorRequestPacket("wish")));
    }

}
