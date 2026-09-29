package net.shurui.dev.sdu.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.api.SduHub;
import net.shurui.dev.sdu.api.SduHubExtensions;
import net.shurui.dev.sdu.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.network.OpenEditorRequestPacket;

import java.util.ArrayList;
import java.util.List;

/**
 * The single entry point for every editor. Opened by {@code /rg npc edit}; each row opens one editor and the
 * editors return here via their "Menu" button. Other mods add entries at the bottom: single flat rows via
 * {@link SduHub}, or whole collapsible {@link SduHubExtensions dropdown sections} (Raid Bosses, Tournaments)
 * that expand into that mod's editor screens. The list scrolls when rows overflow.
 */
public class SduHubScreen extends SagaBaseScreen {

    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int ROW_H = 22;
    private static final int LIST_TOP = 34;

    /**
     * A hub row. A plain editor row has {@code open} set (label + click action); a dropdown-section row
     * has {@code section} set instead (label is the section header, its dropdown holds the entries).
     */
    private record Item(Component label, Runnable open, SduHubExtensions.Section section) {
        static Item leaf(Component label, Runnable open) {
            return new Item(label, open, null);
        }

        static Item dropdown(SduHubExtensions.Section section) {
            return new Item(section.label(), null, section);
        }
    }

    private final List<Item> items = new ArrayList<>();
    /** Maps a live dropdown widget (of a visible section row) back to its section, for click dispatch. */
    private final java.util.Map<DmzDropdown, SduHubExtensions.Section> sectionByDropdown = new java.util.HashMap<>();
    private int scroll;

    public SduHubScreen() {
        super(Component.translatable("gui.dmz_ragnarok.npc.hub.title"), UI_W, UI_H, null);
        // sdu's own editors are the first dropdown section, rendered exactly like the sibling addons' sections
        // (Raid Bosses, Tournaments, ...), so the hub is a clean list of per-mod dropdowns.
        List<SduHubExtensions.Entry> own = new ArrayList<>();
        String[][] builtins = {
                {"gui.dmz_ragnarok.npc.hub.races", "race"}, {"gui.dmz_ragnarok.npc.hub.forms", "form"}, {"gui.dmz_ragnarok.npc.hub.sagas", "saga"},
                {"gui.dmz_ragnarok.npc.hub.sidequests", "sidequest"}, {"gui.dmz_ragnarok.npc.hub.wishes", "wish"},
                {"gui.dmz_ragnarok.npc.hub.shrines", "shrine"}, {"gui.dmz_ragnarok.npc.hub.options", "options"},
        };
        for (String[] b : builtins) {
            final String which = b[1];
            String entryId = "dmz_ragnarok:" + which;
            // Filtered HERE, as the hub opens, not where rows are declared: the switchboard belongs to the
            // server being played on, and the client only learns it at login, so this is the first moment the
            // answer is known and about the right server.
            if (!net.shurui.dev.sdu.modules.HubGate.entryVisible(entryId)) {
                continue;
            }
            own.add(new SduHubExtensions.Entry(entryId, Component.translatable(b[0]), null,
                    () -> DmzNet.sendToServer(new OpenEditorRequestPacket(which))));
        }
        // Legacy single flat entries other mods registered via SduHub also live inside sdu's section.
        for (SduHub.Entry e : SduHub.entries()) {
            own.add(new SduHubExtensions.Entry("dmz_ragnarok:legacy:" + e.id(),
                    Component.translatable(e.labelKey()), null, e.open()));
        }
        // An empty section is dropped rather than drawn as a header that expands into nothing.
        if (!own.isEmpty() && net.shurui.dev.sdu.modules.HubGate.sectionVisible("npc")) {
            items.add(Item.dropdown(new SduHubExtensions.Section(
                    "npc", Component.translatable("gui.dmz_ragnarok.npc.hub.section.npc"), own)));
        }
        // Whole dropdown sections other mods registered (each a collapsible row) follow sdu's own. A section whose
        // module is off goes entirely; one that merely loses some rows keeps the rest.
        for (SduHubExtensions.Section s : SduHubExtensions.sections()) {
            if (!net.shurui.dev.sdu.modules.HubGate.sectionVisible(s.id())) {
                continue;
            }
            List<SduHubExtensions.Entry> kept = new ArrayList<>();
            for (SduHubExtensions.Entry e : s.entries()) {
                if (net.shurui.dev.sdu.modules.HubGate.entryVisible(e.id())) {
                    kept.add(e);
                }
            }
            if (kept.isEmpty()) {
                continue;
            }
            items.add(Item.dropdown(kept.size() == s.entries().size()
                    ? s
                    : new SduHubExtensions.Section(s.id(), s.label(), kept)));
        }
    }

    /** Open the hub on the client (invoked by OpenHubPacket). */
    public static void open() {
        Minecraft.getInstance().setScreen(new SduHubScreen());
    }

    @Override
    protected void init() {
        super.init();
        headerSubtitle = tr("gui.dmz_ragnarok.npc.hub.subtitle");
        sectionByDropdown.clear();
        // Rows are derived, not fixed: on the shared canvas a hardcoded 6 stopped the list a third of the way
        // down an otherwise empty panel.
        int maxRows = rowsThatFit(LIST_TOP, ROW_H);
        int total = items.size();
        scroll = Math.max(0, Math.min(scroll, Math.max(0, total - maxRows)));
        int end = Math.min(total, scroll + maxRows);
        if (total > maxRows) {
            label(tr("gui.dmz_ragnarok.npc.list.count", scroll + 1, end, total), 10, 20);
        }

        int y = LIST_TOP;
        for (int i = scroll; i < end; i++) {
            final Item it = items.get(i);
            if (it.section() != null) {
                // Dropdown row: options from the section's entries; picks are dispatched in onDropdownSelect.
                SduHubExtensions.Section section = it.section();
                List<Component> opts = new ArrayList<>();
                for (SduHubExtensions.Entry e : section.entries()) {
                    opts.add(e.label());
                }
                // Every hub entry opens an editor, so mark the dropdown to play no commit sound: the one sound is
                // the editor's own open ("navigate"). Full content width, centred (the old literal 26/178
                // predates the shared canvas and left rows left of centre).
                DmzDropdown d = dropdown(rowBandLeft(), y + 4, rowBandWidth(), opts, 0).opensScreen();
                sectionByDropdown.put(d, section);
            } else {
                btn(rowBandLeft(), y, rowBandWidth(), GuiTheme.BUTTON_HEIGHT, it.label(), it.open());
            }
            y += ROW_H;
        }
        scrollList(rowBandLeft(), uiWidth, LIST_TOP, ROW_H, maxRows, total, scroll,
                v -> { scroll = v; rebuildWidgets(); });

        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.npc.btn.close"), this::onClose);
    }

    /** Dropdown-section row chosen: run the selected entry's open action (usually sends an open packet). */
    @Override
    protected void onDropdownSelect(DmzDropdown dropdown, int row) {
        SduHubExtensions.Section section = sectionByDropdown.get(dropdown);
        if (section == null || row < 0 || row >= section.entries().size()) {
            return;
        }
        section.entries().get(row).open().run();
    }
}
