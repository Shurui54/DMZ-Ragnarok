package net.shurui.shuruisutilities.client.gravitychamber;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.gravitychamber.GuildGravityChamber;
import net.shurui.shuruisutilities.gravitychamber.PacketSaveChamberGravity;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The guild gravity chamber's gravity and range editor (shift right click). Two stepper rows: gravity 1x to 10x and
 * radius 1 to 25 blocks. Save sends the values back to the server, which re-validates and clamps them. The fixed 1.5x
 * TP multiplier and 10% share ratio are not shown here because they are not editable.
 *
 * <p>Built on {@link SagaBaseScreen} like the rest of the suite's screens rather than on a bare vanilla
 * {@code Screen}. It used to be the latter, which is why it was the one chamber screen that did not carry the suite's
 * frame, fonts or buttons and stood out against every other panel in the mod.
 */
@OnlyIn(Dist.CLIENT)
public class ChamberGravityScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    // Stepper rows. The value sits between its two buttons, which are pinned to the right so both rows line up
    // whatever the label width is.
    private static final int ROW_GRAVITY = 62;
    private static final int ROW_RADIUS = 94;
    private static final int STEP = 20;
    private static final int MINUS_X = UI_W - 104;
    private static final int PLUS_X = UI_W - 44;
    private static final int VALUE_X = UI_W - 74;

    private final BlockPos pos;
    private double gravity;
    private int radius;

    private ChamberGravityScreen(BlockPos pos, double gravity, int radius)
    {
        super(Component.translatable("gui.dmz_ragnarok.guildchamber.gravity.title"), UI_W, UI_H, null);
        this.pos = pos;
        this.gravity = clampGravity(gravity);
        this.radius = clampRadius(radius);
    }

    public static void open(BlockPos pos, double gravity, int radius)
    {
        Minecraft.getInstance().setScreen(new ChamberGravityScreen(pos, gravity, radius));
    }

    private static double clampGravity(double v)
    {
        return Math.max(1.0, Math.min(GuildGravityChamber.MAX_GRAVITY, Math.round(v)));
    }

    private static int clampRadius(int v)
    {
        return Math.max(GuildGravityChamber.MIN_RADIUS, Math.min(GuildGravityChamber.MAX_RADIUS, v));
    }

    @Override
    protected void init()
    {
        super.init();

        label(tr("gui.dmz_ragnarok.guildchamber.gravity.gravity", String.valueOf((int) gravity)), 24, ROW_GRAVITY + 6);
        btn(MINUS_X, ROW_GRAVITY, STEP, STEP, Component.literal("-"), () -> step(-1, 0));
        label(String.valueOf((int) gravity), VALUE_X, ROW_GRAVITY + 6);
        btn(PLUS_X, ROW_GRAVITY, STEP, STEP, Component.literal("+"), () -> step(1, 0));

        label(tr("gui.dmz_ragnarok.guildchamber.gravity.radius", String.valueOf(radius)), 24, ROW_RADIUS + 6);
        btn(MINUS_X, ROW_RADIUS, STEP, STEP, Component.literal("-"), () -> step(0, -1));
        label(String.valueOf(radius), VALUE_X, ROW_RADIUS + 6);
        btn(PLUS_X, ROW_RADIUS, STEP, STEP, Component.literal("+"), () -> step(0, 1));

        commitBtn(UI_W / 2 - 118, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.guildchamber.save"), this::save);
        btn(UI_W / 2 + 8, footerY(), 110, footerBtnHeight(), CommonComponents.GUI_CANCEL, this::onClose);
    }

    // Steppers rebuild the screen so the value labels reflect the new numbers; the labels are laid out in init, not
    // drawn per frame.
    private void step(int gravityDelta, int radiusDelta)
    {
        gravity = clampGravity(gravity + gravityDelta);
        radius = clampRadius(radius + radiusDelta);
        rebuildWidgets();
    }

    private void save()
    {
        NetworkUtils.sendToServer(new PacketSaveChamberGravity(pos, gravity, radius));
        onClose();
    }
}
