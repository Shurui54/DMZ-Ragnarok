package net.shurui.dev.sdu.client.gui.block;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveGravityChamberPacket;

/**
 * Admin GUI for one Gravity Chamber block: TP multiplier, shared-pool fraction (0..1), radius (&ge;1).
 * Seeded by {@link net.shurui.dev.sdu.network.OpenGravityChamberConfigPacket}; Save sends
 * {@link SaveGravityChamberPacket} and the server re-gates, reach-checks, writes the BE. Client-only:
 * instantiated only from that packet's {@code Dist.CLIENT} handler.
 *
 * <p>With a WorldEdit region override set, the radius field is inactive at runtime (the region wins in
 * {@code area()}); the screen still lets radius be edited.
 */
public class GravityChamberConfigScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final BlockPos pos;
    private final boolean hasRegion;

    private double multiplier;
    private double shareFraction;
    private int radius;
    private double gravity;

    private GravityChamberConfigScreen(BlockPos pos, double multiplier, double shareFraction,
                                       int radius, double gravity, boolean hasRegion) {
        super(Component.translatable("gui.dmz_ragnarok.npc.gravitychamber.title"), UI_W, UI_H, null);
        this.pos = pos;
        this.multiplier = multiplier;
        this.shareFraction = shareFraction;
        this.radius = radius;
        this.gravity = gravity;
        this.hasRegion = hasRegion;
    }

    public static void open(BlockPos pos, double multiplier, double shareFraction, int radius,
                            double gravity, boolean hasRegion) {
        Minecraft.getInstance().setScreen(
                new GravityChamberConfigScreen(pos, multiplier, shareFraction, radius, gravity, hasRegion));
    }

    /**
     * Gravity field's upper bound. Pulls DMZ's device cap
     * ({@code ConfigManager.getServerConfig().getGravity().getDeviceMaxGravity()}) to match its clamp;
     * falls back to 10.0 when the config isn't populated yet.
     */
    private static double maxGravity() {
        try {
            Integer cap = com.dragonminez.common.config.ConfigManager.getServerConfig()
                    .getGravity().getDeviceMaxGravity();
            if (cap != null && cap >= 1) {
                return cap;
            }
        } catch (Throwable ignored) {
            // config not ready or accessor changed, use the default cap
        }
        return 10.0;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        headerSubtitle = hasRegion
                ? tr("gui.dmz_ragnarok.npc.gravitychamber.subtitle_region")
                : tr("gui.dmz_ragnarok.npc.gravitychamber.subtitle");

        rowY = 34;
        tf(tr("gui.dmz_ragnarok.npc.gravitychamber.multiplier"), dbl(multiplier),
                v -> multiplier = parseD(v, multiplier));
        tip(tr("gui.dmz_ragnarok.npc.gravitychamber.t_multiplier"));
        tf(tr("gui.dmz_ragnarok.npc.gravitychamber.share"), dbl(shareFraction),
                v -> shareFraction = Math.max(0.0, Math.min(1.0, parseD(v, shareFraction))));
        tip(tr("gui.dmz_ragnarok.npc.gravitychamber.t_share"));
        tf(tr("gui.dmz_ragnarok.npc.gravitychamber.radius"), intStr(radius),
                v -> radius = Math.max(1, parseI(v, radius)));
        tip(tr("gui.dmz_ragnarok.npc.gravitychamber.t_radius"));
        tf(tr("gui.dmz_ragnarok.npc.gravitychamber.gravity"), dbl(gravity),
                v -> gravity = Math.max(1.0, Math.min(maxGravity(), parseD(v, gravity))));
        tip(tr("gui.dmz_ragnarok.npc.gravitychamber.t_gravity"));

        commitBtn(uiWidth / 2 - 118, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.npc.btn.save"),
                () -> { applyFields(); save(); onClose(); });
        btn(uiWidth / 2 + 8, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.npc.btn.cancel"),
                this::onClose);
    }

    private void save() {
        DmzNet.sendToServer(new SaveGravityChamberPacket(pos, multiplier, shareFraction, radius, gravity));
    }
}
