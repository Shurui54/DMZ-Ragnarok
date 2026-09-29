package net.shurui.shuruisutilities.client.gravitychamber;

import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.gravitychamber.PacketOpenChamberSpar;
import net.shurui.shuruisutilities.gravitychamber.PacketStartChamberSpar;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The guild gravity chamber's sparring menu (normal right click). One row per candidate opponent (self, online
 * guildmates and online party members); clicking one asks the server to spawn a scaled copy of that member to spar.
 *
 * <p>Built on {@link SagaBaseScreen} like the rest of the suite's screens rather than on a bare vanilla
 * {@code Screen}, which is what it used to be and why it carried none of the suite's frame, fonts or buttons.
 *
 * <p>The old hand rolled pager is gone with it. A long list now scrolls, which is what every other list in the suite
 * does, so the same drag and wheel behaviour applies here and there are no next and previous buttons competing with
 * the footer for space.
 */
@OnlyIn(Dist.CLIENT)
public class ChamberSparScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final int LIST_TOP = 48;
    private static final int ROW_H = 22;
    private static final int LIST_LEFT = 24;
    private static final int LIST_RIGHT = UI_W - 24;

    private final BlockPos pos;
    private final List<PacketOpenChamberSpar.Candidate> candidates;
    private int scroll;

    private ChamberSparScreen(BlockPos pos, List<PacketOpenChamberSpar.Candidate> candidates)
    {
        super(Component.translatable("gui.dmz_ragnarok.guildchamber.spar.title"), UI_W, UI_H, null);
        this.pos = pos;
        this.candidates = candidates;
    }

    public static void open(BlockPos pos, List<PacketOpenChamberSpar.Candidate> candidates)
    {
        Minecraft.getInstance().setScreen(new ChamberSparScreen(pos, candidates));
    }

    @Override
    protected void init()
    {
        super.init();

        // Narrow the rows if the scrollbar column would otherwise run over them.
        int rowWidth = reserveScrollbar(LIST_LEFT, LIST_RIGHT - LIST_LEFT);
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        for (int i = 0; i < maxRows && scroll + i < candidates.size(); i++)
        {
            PacketOpenChamberSpar.Candidate candidate = candidates.get(scroll + i);
            rowBtn(LIST_LEFT, LIST_TOP + i * ROW_H, rowWidth, 20,
                    Component.literal(candidate.name()), () -> spar(candidate));
        }
        scrollList(LIST_LEFT, uiWidth, LIST_TOP, ROW_H, maxRows, candidates.size(), scroll, s ->
        {
            scroll = s;
            rebuildWidgets();
        });

        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), CommonComponents.GUI_CANCEL, this::onClose);
    }

    private void spar(PacketOpenChamberSpar.Candidate candidate)
    {
        NetworkUtils.sendToServer(new PacketStartChamberSpar(pos, candidate.id()));
        onClose();
    }
}
