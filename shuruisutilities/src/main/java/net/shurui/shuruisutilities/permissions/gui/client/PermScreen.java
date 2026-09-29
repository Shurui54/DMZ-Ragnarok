package net.shurui.shuruisutilities.permissions.gui.client;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.permissions.gui.PacketPermAction;
import net.shurui.shuruisutilities.permissions.gui.PermGuiData;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The permission GROUP LIST (server-driven). Clicking a group asks the server to open that group's editor;
 * the server replies with {@code MODE_EDIT} data which is shown in a dedicated {@link GroupEditScreen}
 * (opened via {@code setScreen}, the same reliable path this list uses). Creating a group is a
 * {@link PacketPermAction} that refreshes the list.
 */
public class PermScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int LIST_TOP = 34;
    private static final int ROW_H = 16;

    private PermGuiData data;
    private int scroll = 0;
    private EditBox createBox;

    public PermScreen(PermGuiData data)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.perms.title"), UI_W, UI_H, null);
        this.data = data;
    }

    // network entry, routes by mode: list stays here, edit opens the dedicated editor screen
    public static void openOrUpdate(PermGuiData data)
    {
        Minecraft mc = Minecraft.getInstance();
        if (data.mode == PermGuiData.MODE_NODES)
        {
            if (mc.screen instanceof GroupEditScreen g)
                g.onNodes(data.context, data.perms);
            return;
        }
        if (data.mode == PermGuiData.MODE_EDIT)
        {
            GroupEditScreen.open(data);
            return;
        }
        if (mc.screen instanceof PermScreen ps)
        {
            ps.data = data;
            ps.rebuildWidgets();
        }
        else
        {
            mc.setScreen(new PermScreen(data));
        }
    }

    static void act(String action, String a, String b)
    {
        NetworkUtils.sendToServer(new PacketPermAction(action, a, b));
    }

    static void act(String action, String a, String b, String c)
    {
        NetworkUtils.sendToServer(new PacketPermAction(action, a, b, c));
    }

    @Override
    protected void init()
    {
        super.init();
        headerSubtitle = "Groups (" + data.groups.size() + ")";
        List<String> groups = new ArrayList<>(data.groups);
        // Reserve holds the footer-level "new group name" field (drawn at UI_H - 22) clear of the list.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H, 10);
        scroll = Math.max(0, Math.min(scroll, Math.max(0, groups.size() - maxRows)));
        int end = Math.min(groups.size(), scroll + maxRows);
        for (int i = scroll; i < end; i++)
        {
            final String name = groups.get(i);
            int ry = LIST_TOP + (i - scroll) * ROW_H;
            rowBtn(14, ry, rowControlRight() - 14, ROW_H,
                    Component.literal(net.shurui.shuruisutilities.client.gui.GroupNames.display(name)),
                    () -> act("open", name, "")).color(0xFFFFFF55);
        }
        scrollList(14, uiWidth, LIST_TOP, ROW_H, maxRows, groups.size(), scroll, v -> { scroll = v; rebuildWidgets(); });

        createBox = field(14, UI_H - 22, 150, "");
        createBox.setHint(Component.literal("new group name"));
        createBox.setMaxLength(64);
        btn(168, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.new"), () -> {
            if (!createBox.getValue().isBlank())
                act("create", createBox.getValue().trim(), "");
        });
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }
}
