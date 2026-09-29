package net.shurui.shuruisutilities.hub;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

/**
 * The registry of hub rows that belong to a feature rather than to the hub itself.
 *
 * <p>{@link HubServer} and {@link EditorServer} keep the row lists, the labels, the ordering and every public row
 * (the menus, hoverbikes, the Patreon cosmetics menu, form cosmetics, the event hooks). Everything a feature owns
 * is looked up here by its hub key instead, so neither router names a feature package. Each feature registers its
 * rows from its own {@code HubRow<Feature>} class; until a feature moves to the Ragnarok Key that registration is
 * made from core, in {@link CoreHubRows}, and afterwards from the key's install().
 *
 * <p>A key with no row registered behaves exactly like a key the routers never knew: nothing is listed, nothing
 * opens, and an action falls through to the generic refresh, which also does nothing. The keyless allow-list
 * ({@code HubServer.KEYLESS_PUBLIC}) is still applied by HubServer before any of this is consulted.
 */
public final class HubRows
{
    private HubRows() {}

    /** Opens a screen for one player. */
    @FunctionalInterface
    public interface Open
    {
        void open(ServerPlayer player);
    }

    /** A per-player yes or no, such as whether a player hub row is listed. */
    @FunctionalInterface
    public interface Test
    {
        boolean test(ServerPlayer player);
    }

    /**
     * Applies one editor action ({@link PacketEditorAction}). Returns true when the handler re-sent its own screen,
     * false to let {@link EditorServer} run its generic refresh ({@code EditorServer.open(player, editor)}).
     */
    @FunctionalInterface
    public interface Action
    {
        boolean handle(ServerPlayer player, String action, List<String> args);
    }

    /** One hub key's handlers. Every handler is optional; an absent one is inert. */
    public static final class Row
    {
        private final String id;
        private Open hub;
        private Test listed;
        private Open editor;
        private Action action;
        private BooleanSupplier available;

        private Row(String id)
        {
            this.id = id;
        }

        /** What {@code HubServer.tryOpen} does for this key (after the keyless allow-list check). */
        public Row hub(Open hub)
        {
            this.hub = hub;
            return this;
        }

        /**
         * The common admin editor shape: {@code tryOpen} opens {@code EditorServer.open(player, id)} when the
         * player is op or holds {@code node}.
         */
        public Row adminEditor(String node)
        {
            return hub(p -> { if (HubServer.adminEditor(p, node)) EditorServer.open(p, id); });
        }

        /** Whether the player hub ({@code HubServer.openPlayer}) lists this key for the player. */
        public Row listed(Test listed)
        {
            this.listed = listed;
            return this;
        }

        /** What {@code EditorServer.open(player, id)} sends. */
        public Row editor(Open editor)
        {
            this.editor = editor;
            return this;
        }

        /** What {@code EditorServer.handle(player, id, ...)} does. */
        public Row action(Action action)
        {
            this.action = action;
            return this;
        }

        /** Whether the feature behind this key is currently usable, for rows and menu flags that depend on it. */
        public Row available(BooleanSupplier available)
        {
            this.available = available;
            return this;
        }

        /** Store this row under its key, replacing any earlier registration of the same key. */
        public void register()
        {
            ROWS.put(id, this);
        }
    }

    private static final Map<String, Row> ROWS = new ConcurrentHashMap<>();

    static
    {
        CoreHubRows.register();
    }

    /** Start a row for {@code id}; nothing is stored until {@link Row#register()}. */
    public static Row row(String id)
    {
        return new Row(id);
    }

    /** Remove a key's row, if any. */
    public static void unregister(String id)
    {
        ROWS.remove(id);
    }

    /** Whether any row is registered for {@code id}. */
    public static boolean has(String id)
    {
        return ROWS.containsKey(id);
    }

    static void hubOpen(ServerPlayer player, String id)
    {
        Row r = ROWS.get(id);
        if (r != null && r.hub != null)
            r.hub.open(player);
    }

    static boolean listed(ServerPlayer player, String id)
    {
        Row r = ROWS.get(id);
        return r != null && r.listed != null && r.listed.test(player);
    }

    static void editorOpen(ServerPlayer player, String id)
    {
        Row r = ROWS.get(id);
        if (r != null && r.editor != null)
            r.editor.open(player);
    }

    /** True when the row's handler re-sent its own screen (see {@link Action}). */
    static boolean action(ServerPlayer player, String id, String action, List<String> args)
    {
        Row r = ROWS.get(id);
        return r != null && r.action != null && r.action.handle(player, action, args);
    }

    /** The row's availability; false when no row, or no availability, is registered. */
    public static boolean available(String id)
    {
        Row r = ROWS.get(id);
        return r != null && r.available != null && r.available.getAsBoolean();
    }

    // ---- shared helpers for row implementations (moved out of EditorServer unchanged) ----

    /** Push one editor payload ({@link PacketEditorData}). */
    public static void send(ServerPlayer player, String editor, List<String> meta, List<List<String>> rows)
    {
        NetworkUtils.sendTo(new PacketEditorData(editor, new ArrayList<>(meta), rows), player);
    }

    public static int parseI(String s)
    {
        try
        {
            return Integer.parseInt(s.trim());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }

    public static double parseD(String s)
    {
        try
        {
            return Double.parseDouble(s.trim());
        }
        catch (NumberFormatException e)
        {
            return 0.0;
        }
    }

    public static long parseLong(String s)
    {
        try
        {
            return Long.parseLong(s.trim());
        }
        catch (Exception e)
        {
            return 0L;
        }
    }

    public static ResourceLocation safeId(String id)
    {
        try
        {
            return new ResourceLocation(id.trim());
        }
        catch (Exception e)
        {
            return new ResourceLocation("minecraft", "air");
        }
    }
}
