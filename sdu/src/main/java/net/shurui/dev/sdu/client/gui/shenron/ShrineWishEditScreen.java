package net.shurui.dev.sdu.client.gui.shenron;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.shenron.ShrineWish;

/**
 * Detail sub-screen for one {@link ShrineWish} in the admin shrine config: {@code tf} fields for id/name/
 * description plus a commands list (one command per line, edited as a {@code " | "}-joined string like the
 * DMZ wish editor's command field, each supporting the {@code %player%} placeholder). Edits mutate the shared
 * {@code ShrineWish} instance in memory; the parent {@link ShrineConfigScreen} persists everything on Save.
 */
public class ShrineWishEditScreen extends FieldEditScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final ShrineWish wish;

    public ShrineWishEditScreen(Screen parent, ShrineWish wish) {
        super(Component.translatable("gui.dmz_ragnarok.npc.shrineconfig.edit_wish"), UI_W, UI_H, parent);
        this.wish = wish;
    }

    @Override
    protected void init() {
        super.init();
        clearFields();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.subtitle.editor");

        rowY = 34;
        tf(tr("gui.dmz_ragnarok.npc.shrineconfig.wish_id"), wish.id, v -> wish.id = v.trim());
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_wish_id"));
        tf(tr("gui.dmz_ragnarok.npc.shrineconfig.wish_name"), wish.name, v -> wish.name = v);
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_wish_name"));
        tf(tr("gui.dmz_ragnarok.npc.shrineconfig.wish_desc"), wish.description, v -> wish.description = v);
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_wish_desc"));
        tf(tr("gui.dmz_ragnarok.npc.shrineconfig.wish_commands"), String.join(" | ", wish.commands), this::setCommands);
        tip(tr("gui.dmz_ragnarok.npc.shrineconfig.t_wish_commands"));

        btn(uiWidth / 2 - 55, footerY(), 110, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.npc.btn.back"), () -> { applyFields(); back(); });
    }

    private void setCommands(String v) {
        wish.commands.clear();
        for (String part : v.split("\\|")) {
            String s = part.trim();
            if (!s.isEmpty()) {
                wish.commands.add(s);
            }
        }
    }
}
