package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

// portal edit view: one portal's config (frame flag, frame block, fill type nether/end, fill dye). Save rebuilds
// it server-side. meta = [name, hasFrame, frameMaterial, fillType, fillColor].
public class PortalEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private static final List<String> FILL_TYPES = List.of("nether", "end");
    private static final List<String> DYES = List.of("white", "orange", "magenta", "light_blue", "yellow", "lime",
            "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private final String name;
    private boolean frame;
    private String frameMaterial;
    private String fillType;
    private String fillColor;

    public PortalEditScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.portals"), UI_W, UI_H, null);
        this.name = meta.get(0);
        this.frame = meta.size() > 1 && Boolean.parseBoolean(meta.get(1));
        this.frameMaterial = meta.size() > 2 ? meta.get(2) : "minecraft:obsidian";
        this.fillType = meta.size() > 3 ? meta.get(3) : "nether";
        this.fillColor = meta.size() > 4 ? meta.get(4) : "";
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        headerName = name;
        rowY = 34;
        bf(tr("gui.dmz_ragnarok.core.portal.has_frame"), frame, () -> frame = !frame);
        tip(tr("gui.dmz_ragnarok.core.portal.has_frame_tip"));
        tf(tr("gui.dmz_ragnarok.core.portal.frame_material"), frameMaterial, v -> frameMaterial = v);
        tip(tr("gui.dmz_ragnarok.core.portal.frame_material_tip"));
        df(tr("gui.dmz_ragnarok.core.portal.fill_type"), FILL_TYPES, fillType, v -> fillType = v);
        tip(tr("gui.dmz_ragnarok.core.portal.fill_type_tip"));
        df(tr("gui.dmz_ragnarok.core.portal.fill_colour"), DYES, fillColor, v -> fillColor = v);
        tip(tr("gui.dmz_ragnarok.core.portal.fill_colour_tip"));

        btn(14, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(82, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                () -> EditorScreens.act("portals", "delete", name));
        btn(150, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.reopen("portals"));
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void save()
    {
        applyFields();
        EditorScreens.act("portals", "save", name, Boolean.toString(frame), frameMaterial.trim(),
                fillType, fillColor);
    }
}
