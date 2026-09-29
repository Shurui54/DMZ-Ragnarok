package net.shurui.shuruisutilities.client.gui.editor;

import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

// display options for one NPC region (from NpcRegionEditScreen): entry title, description, difficulty (both on
// the HUD), plus entry-title + HUD toggles. edits the parent's pending fields; nothing sent until parent Save.
// these apply to the WHOLE region: every selection shares one title/HUD/merged outline.
public class NpcRegionDisplayScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    private final NpcRegionEditScreen parent;

    public NpcRegionDisplayScreen(NpcRegionEditScreen parent)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.npcdisp.title"), UI_W, UI_H, parent);
        this.parent = parent;
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        headerSubtitle = tr("gui.dmz_ragnarok.core.npcdisp.subtitle");
        rowY = 30;

        tf(tr("gui.dmz_ragnarok.core.npcdisp.field_title"), parent.title, v -> parent.title = v);
        tip(tr("gui.dmz_ragnarok.core.npcdisp.title_tip"));
        tf(tr("gui.dmz_ragnarok.core.npcdisp.difficulty"), parent.difficulty, v -> parent.difficulty = v);
        tip(tr("gui.dmz_ragnarok.core.npcdisp.difficulty_tip"));
        tf(tr("gui.dmz_ragnarok.core.npcdisp.description"), parent.description, v -> parent.description = v);
        tip(tr("gui.dmz_ragnarok.core.npcdisp.description_tip"));
        bf(tr("gui.dmz_ragnarok.core.npcdisp.show_title"), parent.showTitle, () -> parent.showTitle = !parent.showTitle);
        tip(tr("gui.dmz_ragnarok.core.npcdisp.show_title_tip"));
        bf(tr("gui.dmz_ragnarok.core.npcdisp.show_hud"), parent.showHud, () -> parent.showHud = !parent.showHud);
        tip(tr("gui.dmz_ragnarok.core.npcdisp.show_hud_tip"));
        cf(tr("gui.dmz_ragnarok.core.npcdisp.map_color"), () -> parent.color, v -> parent.color = v == null ? "" : v);
        tip(tr("gui.dmz_ragnarok.core.npcdisp.map_color_tip"));

        rowY += 4;
        label("§7" + tr("gui.dmz_ragnarok.core.npcdisp.span_note1"), 14, rowY);
        rowY += 10;
        label("§7" + tr("gui.dmz_ragnarok.core.npcdisp.span_note2"), 14, rowY);

        int by = footerY();
        btn(UI_W / 2 - 52, by, 104, footerBtnHeight(), Component.literal("§a").append(Component.translatable("gui.dmz_ragnarok.core.npc.done")), () -> {
            applyFields();
            Minecraft.getInstance().setScreen(parent);
        });
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
