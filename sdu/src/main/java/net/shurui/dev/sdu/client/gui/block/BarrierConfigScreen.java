package net.shurui.dev.sdu.client.gui.block;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.DmzRaces;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.SaveBarrierPacket;

/**
 * Admin GUI for one Level Barrier block's required DMZ level (int &ge; 0) and race. Seeded by
 * {@link net.shurui.dev.sdu.network.OpenBarrierConfigPacket}; Save sends {@link SaveBarrierPacket} and the
 * server re-gates, reach-checks, and applies the level to the WHOLE face-adjacent group. Client-only:
 * instantiated only from that packet's {@code Dist.CLIENT} handler.
 */
public class BarrierConfigScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final BlockPos pos;
    private int requiredLevel;
    private String requiredRace;

    private BarrierConfigScreen(BlockPos pos, int requiredLevel, String requiredRace) {
        super(Component.translatable("gui.dmz_ragnarok.npc.barrier.title"), UI_W, UI_H, null);
        this.pos = pos;
        this.requiredLevel = requiredLevel;
        this.requiredRace = requiredRace == null ? "" : requiredRace;
    }

    public static void open(BlockPos pos, int requiredLevel, String requiredRace) {
        Minecraft.getInstance().setScreen(new BarrierConfigScreen(pos, requiredLevel, requiredRace));
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.barrier.subtitle");

        rowY = 34;
        tf(tr("gui.dmz_ragnarok.npc.barrier.level"), intStr(requiredLevel),
                v -> requiredLevel = Math.max(0, parseI(v, requiredLevel)));
        tip(tr("gui.dmz_ragnarok.npc.barrier.t_level"));

        df(tr("gui.dmz_ragnarok.npc.barrier.race"), DmzRaces.raceIds(), requiredRace, v -> requiredRace = v);
        tip(tr("gui.dmz_ragnarok.npc.barrier.t_race"));

        commitBtn(uiWidth / 2 - 118, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.npc.btn.save"),
                () -> { applyFields(); save(); onClose(); });
        btn(uiWidth / 2 + 8, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.npc.btn.cancel"),
                this::onClose);
    }

    private void save() {
        DmzNet.sendToServer(new SaveBarrierPacket(pos, requiredLevel, requiredRace));
    }
}
