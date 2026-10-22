package net.shurui.dev.sdu.config.editor;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.shurui.dev.sdu.api.RagnarokKey;
import net.shurui.dev.sdu.network.ConfigEditorDataPacket;
import net.shurui.dev.sdu.network.DmzNet;
import net.shurui.dev.sdu.util.SduPerms;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Where every config source and editor link of the suite is registered, and the one place a save is checked and
 * applied. Core, the modules and the key each register their own files (nothing here knows about them), so a module
 * that is not installed simply contributes nothing.
 *
 * <p>Server sources are described and saved over one packet pair ({@code ConfigEditorRequestPacket} C2S,
 * {@code ConfigEditorDataPacket} S2C), one tab per reply. Client sources live in the game client and are saved there
 * with no packet and no permission check (they are the player's own settings).
 */
public final class ConfigEditorRegistry {

    private static final Logger LOG = LogUtils.getLogger();
    /** Most options one search reply carries. */
    private static final int SEARCH_LIMIT = 300;

    private static final List<Supplier<? extends Collection<? extends ConfigSource>>> SERVER =
            new CopyOnWriteArrayList<>();
    private static final List<Supplier<? extends Collection<? extends ConfigSource>>> CLIENT =
            new CopyOnWriteArrayList<>();
    private static final Map<String, EditorLink> LINKS = new LinkedHashMap<>();
    private static volatile Predicate<ServerPlayer> extraPermission = p -> false;
    /** Where a reply goes; a headless test swaps it to read the reply without a client. */
    static volatile java.util.function.BiConsumer<ServerPlayer, ConfigEditorDataPacket> sender = DmzNet::sendConfigEditor;

    private ConfigEditorRegistry() {
    }

    // ---- registration ----

    public static void addServer(ConfigSource source) {
        SERVER.add(() -> List.of(source));
    }

    /** A provider is asked again on every use, for owners whose files are only known once the server is up. */
    public static void addServerProvider(Supplier<? extends Collection<? extends ConfigSource>> provider) {
        SERVER.add(provider);
    }

    public static void addClient(ConfigSource source) {
        CLIENT.add(() -> List.of(source));
    }

    public static void addClientProvider(Supplier<? extends Collection<? extends ConfigSource>> provider) {
        CLIENT.add(provider);
    }

    public static void addLink(EditorLink link) {
        synchronized (LINKS) {
            LINKS.put(link.id(), link);
        }
    }

    /**
     * A permission that also allows editing besides operator level 2 (core passes its {@code su.gui.options} node).
     */
    public static void setExtraPermission(Predicate<ServerPlayer> permission) {
        extraPermission = permission == null ? p -> false : permission;
    }

    /** May this player see and change server options. */
    public static boolean canEdit(ServerPlayer player) {
        if (player == null) {
            return false;
        }
        if (SduPerms.canEdit(player)) {
            return true;
        }
        try {
            return extraPermission.test(player);
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- lookup ----

    /** Every registered source of one side, first registration of an id wins. */
    public static List<ConfigSource> sources(boolean clientSide) {
        Map<String, ConfigSource> byId = new LinkedHashMap<>();
        for (Supplier<? extends Collection<? extends ConfigSource>> p : clientSide ? CLIENT : SERVER) {
            Collection<? extends ConfigSource> got;
            try {
                got = p.get();
            } catch (RuntimeException e) {
                LOG.warn("Config editor: a source provider failed: {}", e.toString());
                continue;
            }
            if (got == null) {
                continue;
            }
            for (ConfigSource s : got) {
                if (s != null) {
                    byId.putIfAbsent(s.id, s);
                }
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** The options a viewer may see: key-only ones only while the key is present, never a secret. */
    private static List<ConfigEntry> visible(List<ConfigSource> sources, boolean keyPresent) {
        List<ConfigEntry> out = new ArrayList<>();
        for (ConfigSource s : sources) {
            List<ConfigEntry> es;
            try {
                es = s.entries();
            } catch (RuntimeException e) {
                LOG.warn("Config editor: {} could not list its options: {}", s.file, e.toString());
                continue;
            }
            for (ConfigEntry e : es) {
                if ((keyPresent || !e.keyOnly) && !ConfigLabels.looksSecret(e.path) && ConfigTabs.known(e.tab)) {
                    out.add(e);
                }
            }
        }
        return out;
    }

    private static boolean matches(ConfigEntry e, String q) {
        return e.label.toLowerCase(Locale.ROOT).contains(q) || e.path.toLowerCase(Locale.ROOT).contains(q)
                || e.file.toLowerCase(Locale.ROOT).contains(q)
                || (e.comment != null && e.comment.toLowerCase(Locale.ROOT).contains(q));
    }

    /** The views of one tab, or of every tab matching {@code query} when it is not blank. */
    public static List<OptionView> views(List<ConfigEntry> entries, String tab, String query) {
        String q = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        List<OptionView> out = new ArrayList<>();
        for (ConfigEntry e : entries) {
            if (q.isEmpty() ? e.tab.equals(tab) : matches(e, q)) {
                out.add(e.view());
                if (!q.isEmpty() && out.size() >= SEARCH_LIMIT) {
                    break;
                }
            }
        }
        return out;
    }

    // ---- the save engine (both sides) ----

    /** What a save did: the per-option refusals, the changes made, and whether any needs a restart. */
    public record SaveResult(Map<String, String> errors, int changed, boolean restartNeeded, List<String> failedFiles) {
    }

    /**
     * Check every edit against its option, store the ones that pass, then commit each touched source once.
     * {@code who} names the editor in the log; {@code audit} writes the {@code [audit]} line per change.
     */
    public static SaveResult apply(List<ConfigEntry> allowed, Map<String, String> edits, String who, boolean audit) {
        Map<String, ConfigEntry> byId = new LinkedHashMap<>();
        for (ConfigEntry e : allowed) {
            byId.put(e.id, e);
        }
        Map<String, String> errors = new LinkedHashMap<>();
        // group the edits by their file, so each file is changed and saved as one unit
        Map<ConfigSource, Map<ConfigEntry, String>> bySource = new LinkedHashMap<>();
        for (Map.Entry<String, String> edit : edits.entrySet()) {
            ConfigEntry e = byId.get(edit.getKey());
            ConfigSource src = e == null ? null : sourceOf(e);
            if (e == null || src == null) {
                errors.put(edit.getKey(), "not an option you can change here");
                continue;
            }
            bySource.computeIfAbsent(src, k -> new LinkedHashMap<>()).put(e, edit.getValue());
        }
        int[] changed = {0};
        boolean[] restart = {false};
        List<String> failed = new ArrayList<>();
        for (Map.Entry<ConfigSource, Map<ConfigEntry, String>> group : bySource.entrySet()) {
            ConfigSource src = group.getKey();
            try {
                src.locked(() -> {
                    boolean touched = false;
                    for (Map.Entry<ConfigEntry, String> edit : group.getValue().entrySet()) {
                        ConfigEntry e = edit.getKey();
                        String bad = e.validate(edit.getValue());
                        if (bad != null) {
                            errors.put(e.id, bad);
                            continue;
                        }
                        String before = e.current();
                        try {
                            e.apply(edit.getValue());
                        } catch (RuntimeException ex) {
                            errors.put(e.id, "could not be stored: " + ex.getMessage());
                            continue;
                        }
                        String after = e.current();
                        if (before.equals(after)) {
                            continue;
                        }
                        touched = true;
                        changed[0]++;
                        restart[0] |= e.restart;
                        if (audit) {
                            LOG.info("[audit] {} changed config {} {} from {} to {}{}", who, e.file, e.path,
                                    shorten(before), shorten(after), e.restart ? " (applies after a restart)" : "");
                        } else {
                            LOG.info("Client option {} {} changed from {} to {}", e.file, e.path, shorten(before),
                                    shorten(after));
                        }
                    }
                    if (touched) {
                        try {
                            src.commit();
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    }
                });
            } catch (RuntimeException ex) {
                LOG.error("Config editor: saving {} failed", src.file, ex);
                failed.add(src.file);
            }
        }
        return new SaveResult(errors, changed[0], restart[0], failed);
    }

    // Entries do not point at their source, so the owner is found by id prefix among the registered sources.
    private static final Map<String, ConfigSource> SOURCE_BY_ID = new java.util.concurrent.ConcurrentHashMap<>();

    private static ConfigSource sourceOf(ConfigEntry e) {
        int colon = e.id.indexOf(':');
        return colon < 0 ? null : SOURCE_BY_ID.get(e.id.substring(0, colon));
    }

    private static List<ConfigEntry> index(List<ConfigSource> sources, boolean keyPresent) {
        for (ConfigSource s : sources) {
            SOURCE_BY_ID.put(s.id, s);
        }
        return visible(sources, keyPresent);
    }

    private static String shorten(String v) {
        String s = v.replace(OptionView.LIST_SEP, ',');
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }

    // ---- server side ----

    private static String who(ServerPlayer p) {
        return p.getGameProfile().getName() + " (" + p.getUUID() + ")";
    }

    /** Send one tab (or a search) to the player. Refuses anyone who may not edit. */
    public static void open(ServerPlayer player, String tab, String query) {
        send(player, tab, query, Map.of(), "", new String[0]);
    }

    public static void save(ServerPlayer player, String tab, String query, Map<String, String> edits) {
        if (!canEdit(player)) {
            LOG.warn("[audit] {} tried to save {} config option(s) without permission; refused", who(player),
                    edits.size());
            return;
        }
        boolean key = RagnarokKey.present();
        List<ConfigEntry> allowed = index(sources(false), key);
        SaveResult r = apply(allowed, edits, who(player), true);
        String statusKey;
        String[] args;
        if (!r.failedFiles().isEmpty()) {
            statusKey = "gui.dmz_ragnarok.npc.options.status.failed";
            args = new String[]{String.join(", ", r.failedFiles())};
        } else if (!r.errors().isEmpty()) {
            statusKey = "gui.dmz_ragnarok.npc.options.status.partial";
            args = new String[]{Integer.toString(r.changed()), Integer.toString(r.errors().size())};
        } else if (r.changed() == 0) {
            statusKey = "gui.dmz_ragnarok.npc.options.status.nochange";
            args = new String[0];
        } else {
            statusKey = r.restartNeeded() ? "gui.dmz_ragnarok.npc.options.status.saved_restart"
                    : "gui.dmz_ragnarok.npc.options.status.saved";
            args = new String[]{Integer.toString(r.changed())};
        }
        send(player, tab, query, r.errors(), statusKey, args);
    }

    public static void openLink(ServerPlayer player, String id) {
        if (!canEdit(player)) {
            return;
        }
        EditorLink link;
        synchronized (LINKS) {
            link = LINKS.get(id);
        }
        if (link == null || !safeTest(link, player)) {
            return;
        }
        link.open().accept(player);
    }

    private static boolean safeTest(EditorLink link, ServerPlayer player) {
        try {
            return link.visible().test(player);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static void send(ServerPlayer player, String tab, String query, Map<String, String> errors,
                             String statusKey, String[] statusArgs) {
        if (!canEdit(player)) {
            player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                    "gui.dmz_ragnarok.npc.options.no_permission"), false);
            return;
        }
        boolean key = RagnarokKey.present();
        List<ConfigEntry> entries = index(sources(false), key);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String t : ConfigTabs.SERVER_ORDER) {
            counts.put(t, 0);
        }
        for (ConfigEntry e : entries) {
            counts.merge(e.tab, 1, Integer::sum);
        }
        List<ConfigEditorDataPacket.LinkView> links = new ArrayList<>();
        synchronized (LINKS) {
            for (EditorLink l : LINKS.values()) {
                if (safeTest(l, player)) {
                    links.add(new ConfigEditorDataPacket.LinkView(l.id(), l.labelKey(), l.tipKey()));
                }
            }
        }
        List<ConfigEditorDataPacket.TabCount> tabs = new ArrayList<>();
        for (Map.Entry<String, Integer> c : counts.entrySet()) {
            if (c.getValue() > 0) {
                tabs.add(new ConfigEditorDataPacket.TabCount(c.getKey(), c.getValue()));
            }
        }
        String t = tab == null || tab.isBlank() || !counts.containsKey(tab) || counts.get(tab) == 0
                ? (tabs.isEmpty() ? ConfigTabs.GENERAL : tabs.get(0).id()) : tab;
        List<OptionView> views = views(entries, t, query);
        sender.accept(player, new ConfigEditorDataPacket(tabs, t, query == null ? "" : query, views, links,
                errors, statusKey, List.of(statusArgs), key));
    }

    // ---- client side ----

    /** Every client option (the Client tab), described locally. */
    public static List<OptionView> clientViews(String query) {
        List<ConfigEntry> entries = index(sources(true), true);
        return views(entries, ConfigTabs.CLIENT, query == null || query.isBlank() ? "" : query);
    }

    /** Save client options locally. Same checks as the server; the player's own settings, so no permission gate. */
    public static SaveResult saveClient(Map<String, String> edits) {
        List<ConfigEntry> entries = index(sources(true), true);
        return apply(entries, edits, "local player", false);
    }
}
