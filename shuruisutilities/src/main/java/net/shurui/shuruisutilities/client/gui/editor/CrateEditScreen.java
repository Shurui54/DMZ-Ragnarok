package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzDropdown;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistries;

// crate edit view: tabbed. Settings edits display name + key item (and can hand out a key); Rewards lists the
// weighted rewards (delete/edit/add) and can copy this crate's loot onto another crate. a reward is an item
// (searchable dropdown + count), a console command (%player substituted) or a chat message. changes act() to the
// server, which re-sends the crate.
// meta = [name, keyItem, displayName, otherCrateName...], rows = [describe, weight, itemId, type, count, rawText].
public class CrateEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final String[] SECTION_KEYS = {
            "gui.dmz_ragnarok.core.crate.tab_settings", "gui.dmz_ragnarok.core.crate.tab_rewards" };

    // all registered item ids, sorted; built once and shared as dropdown options
    private static List<String> itemIds;
    private static List<Component> itemOptions;

    private final String name;
    private String keyItem;
    private String displayName;
    private final List<List<String>> rewards;
    private final List<String> otherCrates;   // every OTHER crate name, for the copy-loot target dropdown
    private int section = 0;
    private int rewardScroll = 0;

    private int rewardType = 0;   // 0 = item, 1 = command, 2 = message
    private int itemIndex = -1;   // selected index into itemOptions
    private String countStr = "1";
    private String weightStr = "1";
    private String cmdStr = "";
    private String msgStr = "";
    // -1 = the add block appends a new reward; >= 0 = it updates rewards.get(editIndex) in place. Reset for free
    // when the server re-sends the crate after any save (a fresh CrateEditScreen defaults it back to -1).
    private int editIndex = -1;
    private int copyIndex = 0;

    private DmzDropdown typeDd, itemDd, copyDd;
    private EditBox countBox, weightBox, cmdBox, msgBox;

    public CrateEditScreen(List<String> meta, List<List<String>> rewards)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.crates"), UI_W, UI_H, null);
        this.name = meta.get(0);
        this.keyItem = meta.size() > 1 ? meta.get(1) : "";
        this.displayName = meta.size() > 2 ? meta.get(2) : "";
        this.otherCrates = meta.size() > 3 ? new ArrayList<>(meta.subList(3, meta.size())) : new ArrayList<>();
        this.rewards = rewards;
    }

    private static void ensureItemList()
    {
        if (itemIds != null)
            return;
        List<String> ids = new ArrayList<>();
        for (ResourceLocation rl : ForgeRegistries.ITEMS.getKeys())
            ids.add(rl.toString());
        Collections.sort(ids);
        List<Component> opts = new ArrayList<>(ids.size());
        for (String s : ids)
            opts.add(Component.literal(s));
        itemIds = ids;
        itemOptions = opts;
    }

    private void selectSection(int s)
    {
        applyFields();
        stashInputs();
        section = s;
        rewardScroll = 0;
        rebuildWidgets();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        typeDd = itemDd = copyDd = null;
        countBox = weightBox = cmdBox = msgBox = null;
        rowY = buildNamedTabHeader(name, trAll(SECTION_KEYS), section, this::selectSection);

        if (section == 0)
            buildSettings();
        else
            buildRewards();

        btn(14, footerY(), 60, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.reopen("crates"));
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private void buildSettings()
    {
        tf(tr("gui.dmz_ragnarok.core.crate.display_name"), displayName, v -> displayName = v);
        tip(tr("gui.dmz_ragnarok.core.crate.display_name_tip"));
        tf(tr("gui.dmz_ragnarok.core.crate.key_item"), keyItem, v -> keyItem = v);
        tip(tr("gui.dmz_ragnarok.core.crate.key_item_tip"));
        btn(14, UI_H - 44, 90, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.save"), () -> {
            applyFields();
            EditorScreens.act("crates", "settings", name, displayName, keyItem.trim());
        });
        btn(110, UI_H - 44, 90, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.crate.give_key"),
                () -> EditorScreens.act("crates", "givekey", name));
    }

    private void buildRewards()
    {
        int top = rowY;

        label("§7" + tr("gui.dmz_ragnarok.core.crate.add_reward"), 14, top, 0xFFB0B0B0);
        int tRow = top + 11;
        label("§7" + tr("gui.dmz_ragnarok.core.crate.type"), 14, tRow + 2);
        // Display labels only; the reward type is selected by index (see the switch on rewardType), so
        // translating these keeps the same order and count as the persisted index.
        typeDd = dropdown(56, tRow, 120, options(trAll(new String[] {
                "gui.dmz_ragnarok.core.crate.type_item", "gui.dmz_ragnarok.core.crate.type_command",
                "gui.dmz_ragnarok.core.crate.type_message" })), rewardType);
        tooltip(56, tRow, 120, 11, tr("gui.dmz_ragnarok.core.crate.type_tip"));

        int inRow = tRow + 15;
        int bRow = inRow + 22;
        switch (rewardType)
        {
            case 1 -> buildCmdAdd(inRow, bRow);
            case 2 -> buildMsgAdd(inRow, bRow);
            default -> buildItemAdd(inRow, bRow);
        }

        // copy-loot control: overwrite another crate's rewards with a deep copy of this one's. only shown when there
        // is a target to pick; the dropdown already excludes this crate (the server drops self from the meta list).
        int listTop = bRow + 20;
        if (!otherCrates.isEmpty())
        {
            int cRow = bRow + 18;
            label("§7" + tr("gui.dmz_ragnarok.core.crate.copy_to"), 14, cRow + 2);
            copyIndex = Math.min(copyIndex, otherCrates.size() - 1);
            copyDd = dropdown(70, cRow, 110, otherCrateOptions(), copyIndex);
            tooltip(70, cRow, 110, 11, tr("gui.dmz_ragnarok.core.crate.copy_to_tip"));
            btn(186, cRow - 1, 92, GuiTheme.BUTTON_HEIGHT,
                    Component.translatable("gui.dmz_ragnarok.core.crate.copy_replace"), () -> {
                        if (copyDd != null)
                            copyIndex = copyDd.getIndex();
                        if (copyIndex >= 0 && copyIndex < otherCrates.size())
                            EditorScreens.act("crates", "copyrewards", name, otherCrates.get(copyIndex));
                    });
            tooltip(186, cRow - 1, 92, GuiTheme.BUTTON_HEIGHT, tr("gui.dmz_ragnarok.core.crate.copy_replace_tip"));
            listTop = cRow + 18;
        }

        int rh = 13;
        // the add block is drawn ABOVE listTop; the reward list is the bottom-most content, so it fills down to the
        // footer with no reserve.
        int cap = rowsThatFit(listTop, rh);
        rewardScroll = Math.max(0, Math.min(rewardScroll, Math.max(0, rewards.size() - cap)));
        int end = Math.min(rewards.size(), rewardScroll + cap);
        label("§7" + tr("gui.dmz_ragnarok.core.crate.rewards", rewards.size()), 14, listTop - 10, 0xFFB0B0B0);
        // Edit + Delete are both trailing controls; the 13px row is already tight, so both buttons are narrowed to
        // 34 (rather than growing the row) and the description trim is shortened to match the reduced label width.
        for (int i = rewardScroll; i < end; i++)
        {
            final int index = i;
            List<String> r = rewards.get(i);
            int ry = listTop + (i - rewardScroll) * rh;
            int delX = rowControlRight() - 34;
            int editX = delX - 2 - 34;
            label("§f" + trim(net.shurui.shuruisutilities.util.output.ChatOutputHandler.formatColors(r.get(0))) + "  §7w:" + r.get(1), 14, ry + 3, 0xFFFFFFFF);
            btn(editX, ry, 34, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.edit"),
                    () -> beginEdit(index));
            btn(delX, ry, 34, GuiTheme.ROW_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                    () -> EditorScreens.act("crates", "delreward", name, Integer.toString(index)));
        }
        scrollList(14, uiWidth, listTop, rh, cap, rewards.size(), rewardScroll,
                v -> { rewardScroll = v; rebuildWidgets(); });
    }

    private void buildItemAdd(int inRow, int bRow)
    {
        ensureItemList();
        if (itemIndex < 0)
            itemIndex = Math.max(0, itemIds.indexOf("minecraft:diamond"));
        itemIndex = Math.min(itemIndex, itemOptions.size() - 1);
        itemDd = dropdown(14, inRow, 200, itemOptions, itemIndex).searchable();
        tooltip(14, inRow, 200, 11, tr("gui.dmz_ragnarok.core.crate.item_pick_tip"));

        label("§7" + tr("gui.dmz_ragnarok.core.crate.count"), 14, bRow - 9);
        countBox = field(14, bRow, 40, countStr);
        label("§7" + tr("gui.dmz_ragnarok.core.crate.weight"), 58, bRow - 9);
        weightBox = field(58, bRow, 40, weightStr);
        tooltip(58, bRow, 40, 10, tr("gui.dmz_ragnarok.core.crate.weight_tip"));
        btn(104, bRow - 1, 76, GuiTheme.BUTTON_HEIGHT, addOrUpdateLabel("gui.dmz_ragnarok.core.crate.add_item"), () -> {
            stashInputs();
            if (itemDd != null)
                itemIndex = itemDd.getIndex();
            if (itemIndex >= 0 && itemIndex < itemIds.size())
            {
                if (editIndex >= 0)
                    EditorScreens.act("crates", "editreward", name, Integer.toString(editIndex), "0",
                            itemIds.get(itemIndex), countStr.trim(), weightStr.trim());
                else
                    EditorScreens.act("crates", "addreward", name, itemIds.get(itemIndex),
                            countStr.trim(), weightStr.trim());
            }
        });
        cancelBtn(184, bRow - 1);
    }

    private void buildCmdAdd(int inRow, int bRow)
    {
        cmdBox = field(14, inRow, 272, cmdStr);
        cmdBox.setHint(Component.translatable("gui.dmz_ragnarok.core.crate.cmd_hint"));
        cmdBox.setMaxLength(256);
        tooltip(14, inRow, 272, 10, tr("gui.dmz_ragnarok.core.crate.cmd_tip"));

        label("§7" + tr("gui.dmz_ragnarok.core.crate.weight"), 14, bRow - 9);
        weightBox = field(14, bRow, 40, weightStr);
        tooltip(14, bRow, 40, 10, tr("gui.dmz_ragnarok.core.crate.weight_tip"));
        btn(60, bRow - 1, 96, GuiTheme.BUTTON_HEIGHT, addOrUpdateLabel("gui.dmz_ragnarok.core.crate.add_command"), () -> {
            stashInputs();
            if (!cmdStr.trim().isBlank())
            {
                if (editIndex >= 0)
                    EditorScreens.act("crates", "editreward", name, Integer.toString(editIndex), "1",
                            cmdStr.trim(), weightStr.trim());
                else
                    EditorScreens.act("crates", "addcmd", name, cmdStr.trim(), weightStr.trim());
            }
        });
        cancelBtn(160, bRow - 1);
    }

    private void buildMsgAdd(int inRow, int bRow)
    {
        msgBox = field(14, inRow, 272, msgStr);
        msgBox.setHint(Component.translatable("gui.dmz_ragnarok.core.crate.msg_hint"));
        msgBox.setMaxLength(256);
        tooltip(14, inRow, 272, 10, tr("gui.dmz_ragnarok.core.crate.msg_tip"));

        label("§7" + tr("gui.dmz_ragnarok.core.crate.weight"), 14, bRow - 9);
        weightBox = field(14, bRow, 40, weightStr);
        tooltip(14, bRow, 40, 10, tr("gui.dmz_ragnarok.core.crate.weight_tip"));
        btn(60, bRow - 1, 96, GuiTheme.BUTTON_HEIGHT, addOrUpdateLabel("gui.dmz_ragnarok.core.crate.add_message"), () -> {
            stashInputs();
            if (!msgStr.trim().isBlank())
            {
                if (editIndex >= 0)
                    EditorScreens.act("crates", "editreward", name, Integer.toString(editIndex), "2",
                            msgStr.trim(), weightStr.trim());
                else
                    EditorScreens.act("crates", "addmsg", name, msgStr.trim(), weightStr.trim());
            }
        });
        cancelBtn(160, bRow - 1);
    }

    // the add block's confirm button reads Update while editing an existing reward, Add otherwise
    private Component addOrUpdateLabel(String addKey)
    {
        return Component.translatable(editIndex >= 0 ? "gui.dmz_ragnarok.core.crate.update_reward" : addKey);
    }

    // only present while editing: leaves the block in add mode without touching the server
    private void cancelBtn(int x, int bRow)
    {
        if (editIndex < 0)
            return;
        btn(x, bRow, 56, GuiTheme.BUTTON_HEIGHT, Component.translatable("gui.dmz_ragnarok.core.btn.cancel"), () -> {
            stashInputs();
            editIndex = -1;
            rebuildWidgets();
        });
    }

    // load a reward row back into the add block and switch to update mode. the raw command/message text and the
    // item count come from the row payload (indices 3..5), never parsed out of the lossy describe string.
    private void beginEdit(int index)
    {
        stashInputs();
        List<String> r = rewards.get(index);
        int t = r.size() > 3 ? parse(r.get(3), 0) : 0;
        rewardType = t;
        weightStr = r.get(1);
        if (t == 1)
        {
            cmdStr = r.size() > 5 ? r.get(5) : "";
        }
        else if (t == 2)
        {
            msgStr = r.size() > 5 ? r.get(5) : "";
        }
        else
        {
            ensureItemList();
            itemIndex = Math.max(0, itemIds.indexOf(r.get(2)));
            countStr = r.size() > 4 ? r.get(4) : "1";
        }
        editIndex = index;
        rebuildWidgets();
    }

    private List<Component> otherCrateOptions()
    {
        List<Component> opts = new ArrayList<>(otherCrates.size());
        for (String s : otherCrates)
            opts.add(Component.literal(s));
        return opts;
    }

    private static int parse(String s, int fallback)
    {
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (NumberFormatException e)
        {
            return fallback;
        }
    }

    // read the shown add fields into instance state so they survive a rebuild
    private void stashInputs()
    {
        if (countBox != null)
            countStr = countBox.getValue();
        if (weightBox != null)
            weightStr = weightBox.getValue();
        if (cmdBox != null)
            cmdStr = cmdBox.getValue();
        if (msgBox != null)
            msgStr = msgBox.getValue();
    }

    @Override
    protected void onExtraDropdown(DmzDropdown dropdown, int row)
    {
        if (dropdown == typeDd)
        {
            stashInputs();
            rewardType = row;
            rebuildWidgets();
        }
        else if (dropdown == itemDd)
        {
            itemIndex = row;
        }
        else if (dropdown == copyDd)
        {
            copyIndex = row;
        }
    }

    // keep long reward descriptions from overrunning the row's Edit/Delete buttons (shorter now that the row carries
    // two trailing controls rather than one)
    private static String trim(String s)
    {
        return s.length() > 30 ? s.substring(0, 29) + "…" : s;
    }
}
