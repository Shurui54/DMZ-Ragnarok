package net.shurui.dev.shuruis_dmz_dungeons.client.gui;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.shuruis_dmz_dungeons.block.CustomDrop;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import java.util.List;

// standalone editor for ONE List<CustomDrop>, opened from the crate Loot tab's per-tier "Drops" button. It edits the
// list IN PLACE (the same list the floor config holds), so returning with Back is all it takes for the parent's Save
// to persist it. The rows themselves are the shared DropListEditor body, so this screen and the advanced spawner's
// Drops tab use the exact same drop-row implementation. The single tab caption carries the tier name so it is clear
// which pool is being edited without a separate subtitle line.
public class DropListScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final List<CustomDrop> drops;
    private final String tierCaption;
    private int dropScroll = 0;

    public DropListScreen(Screen parent, String tierCaption, List<CustomDrop> drops) {
        super(Component.translatable("gui.dmz_ragnarok.dungeons.loot.drops_title"), UI_W, UI_H, parent);
        this.tierCaption = tierCaption;
        this.drops = drops;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        rowY = buildTabHeader(null, new String[]{tierCaption}, 0, i -> { });
        DropListEditor.build(this, drops, dropScroll, v -> { dropScroll = v; rebuildWidgets(); });
        btn(UI_W / 2 - 55, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.dungeons.common.back"), () -> {
                    applyFields();
                    back();
                });
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
