package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// world border editor for the player's current dim: enable, center, half-size, shape. Save applies to whichever
// dim the editor is in. meta = [dim, enabled, centerX, centerZ, sizeX, sizeZ, shape].
public class WorldBorderScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final List<String> SHAPES = List.of("BOX", "ELLIPSOID", "CYLINDER");

    private final String dim;
    private boolean enabled;
    private String centerX, centerZ, sizeX, sizeZ;
    private String shape;

    public WorldBorderScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.worldborder"), UI_W, UI_H, null);
        this.dim = meta.get(0);
        this.enabled = meta.size() > 1 && Boolean.parseBoolean(meta.get(1));
        this.centerX = meta.size() > 2 ? meta.get(2) : "0";
        this.centerZ = meta.size() > 3 ? meta.get(3) : "0";
        this.sizeX = meta.size() > 4 ? meta.get(4) : "32768";
        this.sizeZ = meta.size() > 5 ? meta.get(5) : "32768";
        this.shape = meta.size() > 6 ? meta.get(6) : "BOX";
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        headerName = dim;
        rowY = 34;
        bf(tr("gui.dmz_ragnarok.core.wb.enabled"), enabled, () -> enabled = !enabled);
        tip(tr("gui.dmz_ragnarok.core.wb.enabled_tip"));
        tf(tr("gui.dmz_ragnarok.core.wb.center_x"), centerX, v -> centerX = v);
        tf(tr("gui.dmz_ragnarok.core.wb.center_z"), centerZ, v -> centerZ = v);
        tf(tr("gui.dmz_ragnarok.core.wb.radius_x"), sizeX, v -> sizeX = v);
        tip(tr("gui.dmz_ragnarok.core.wb.radius_tip"));
        tf(tr("gui.dmz_ragnarok.core.wb.radius_z"), sizeZ, v -> sizeZ = v);
        df(tr("gui.dmz_ragnarok.core.wb.shape"), SHAPES, shape, v -> shape = v);
        tip(tr("gui.dmz_ragnarok.core.wb.shape_tip"));

        btn(14, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void save()
    {
        applyFields();
        EditorScreens.act("worldborder", "save", Boolean.toString(enabled), centerX.trim(), centerZ.trim(),
                sizeX.trim(), sizeZ.trim(), shape);
    }
}
