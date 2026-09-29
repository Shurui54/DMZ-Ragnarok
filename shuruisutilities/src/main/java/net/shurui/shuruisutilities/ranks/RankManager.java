package net.shurui.shuruisutilities.ranks;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;

/**
 * Registry of image ranks. Each rank is a badge PNG exposed as a glyph in the {@code shuruisutilities:ranks}
 * bitmap font, so it renders anywhere a {@link Component} is drawn. Assigned to a permission group via
 * {@link #RANK_PROPERTY} (prefix menu in the perms GUI); a player's badge resolves through their groups.
 * Loaded from the generated /rank_index.json.
 */
public final class RankManager
{
    private RankManager() {}

    // badge-glyph font (chat + nameplate size)
    public static final ResourceLocation FONT = new ResourceLocation("dmz_ragnarok", "ranks");

    // smaller variant (same glyphs) for the tab list, where rows are only ~8px tall
    public static final ResourceLocation FONT_SMALL = new ResourceLocation("dmz_ragnarok", "ranks_small");

    // group/user permission property holding a rank name; resolved through the player's groups
    public static final String RANK_PROPERTY = "su.rank";

    // codepoint = static badge glyph (chat + fallback); an animated rank also owns a contiguous run of `frames`
    // glyphs from animStart, each shown frameTime ms and cycled client-side. Still rank = frames <= 1.
    // set: which badge set this rank belongs to. "" is the normal set, the only one offered for assignment.
    public record Rank(String name, String display, int codepoint, int animStart, int frames, int frameTime,
                       String set) {}

    private static final Map<String, Rank> byName = new LinkedHashMap<>();
    private static final List<Rank> ranks = new ArrayList<>();
    // every glyph codepoint a rank owns (static + each frame) -> the rank, for animating chat
    private static final Map<Integer, Rank> byCodepoint = new java.util.HashMap<>();

    // rank used when a player has none assigned (index's "default" field)
    private static String defaultRankName = "";

    // load the rank list from the bundled index; safe on the client
    public static void loadIndex()
    {
        ranks.clear();
        byName.clear();
        byCodepoint.clear();
        try (InputStreamReader r = new InputStreamReader(
                RankManager.class.getResourceAsStream("/rank_index.json"), StandardCharsets.UTF_8))
        {
            JsonObject root = new Gson().fromJson(r, JsonObject.class);
            defaultRankName = root.has("default") ? root.get("default").getAsString() : "";
            root.getAsJsonArray("ranks").forEach(e -> {
                JsonObject o = e.getAsJsonObject();
                int cp = o.get("codepoint").getAsInt();
                Rank rank = new Rank(o.get("name").getAsString(), o.get("display").getAsString(), cp,
                        o.has("animStart") ? o.get("animStart").getAsInt() : cp,
                        o.has("frames") ? o.get("frames").getAsInt() : 1,
                        o.has("frameTime") ? o.get("frameTime").getAsInt() : 100,
                        o.has("set") ? o.get("set").getAsString() : "");
                ranks.add(rank);
                byName.put(rank.name(), rank);
                byCodepoint.put(rank.codepoint(), rank);
                for (int f = 0; f < rank.frames(); f++)
                    byCodepoint.put(rank.animStart() + f, rank);
            });
        }
        catch (Exception e)
        {
            LoggingHandler.sulog.error("[Ranks] Failed to load rank index", e);
        }
        LoggingHandler.sulog.info("[Ranks] Loaded {} rank(s)", ranks.size());
    }

    // default rank, or null if the index defines none
    public static Rank defaultRank()
    {
        ensureIndexLoaded();
        return get(defaultRankName);
    }

    public static void ensureIndexLoaded()
    {
        if (ranks.isEmpty())
            loadIndex();
    }

    /**
     * The ranks that may be ASSIGNED on this server, which is not the same as the ranks that exist: only the normal
     * set (no {@code set} tag in rank_index.json). There is one key, so there is one assignable badge set.
     *
     * <p>{@link #get(String)} is deliberately NOT filtered. A rank already sitting on a group has to keep
     * resolving and rendering, or a badge from another set would turn into a blank glyph and lose which rank the
     * group had.
     */
    public static List<Rank> all()
    {
        List<Rank> out = new ArrayList<>();
        for (Rank r : ranks)
        {
            if (r.set().isEmpty())
            {
                out.add(r);
            }
        }
        return out;
    }

    /** Every rank in the index regardless of licence. For rendering and lookups, never for a pick list. */
    public static List<Rank> allSets()
    {
        return new ArrayList<>(ranks);
    }

    public static Rank get(String name)
    {
        return name == null ? null : byName.get(name.toLowerCase());
    }

    /**
     * How senior a rank is, as its position in the index. Higher means more senior; -1 for no rank at all.
     *
     * <p>There is no priority FIELD on a rank, and adding one would mean an operator keeping two orderings in
     * step. {@code rank_index.json} is already written lowest first (newbie, veteran, ... server manager, owner),
     * so its own order IS the hierarchy and reading position off it cannot drift from what the file says.
     *
     * <p>Consequence worth knowing: this means the ORDER OF THE FILE is load bearing. Inserting a new rank in the
     * middle changes the seniority of everything after it, which is usually what you want, and appending one puts
     * it at the top, which usually is not.
     */
    public static int priorityOf(Rank rank)
    {
        if (rank == null)
            return -1;
        for (int i = 0; i < ranks.size(); i++)
        {
            if (ranks.get(i).name().equals(rank.name()))
                return i;
        }
        return -1;
    }

    // rank from the player's groups' su.rank, else the default rank; null only if neither is defined. The
    // resolution lives in the Ragnarok Key (RankHooks); keyless every player has the default rank.
    public static Rank rankForPlayer(Player player)
    {
        return net.shurui.shuruisutilities.api.key.RankHooks.get().rankForPlayer(player);
    }

    /**
     * Rank for a player who is NOT connected here, resolved from uuid and name (the permission tree is synced
     * network-wide, so their su.rank is readable on any shard). Resolved in the Ragnarok Key (RankHooks); keyless the
     * default rank.
     */
    public static Rank rankForUuid(java.util.UUID uuid, String username)
    {
        return net.shurui.shuruisutilities.api.key.RankHooks.get().rankForUuid(uuid, username);
    }

    // codepoint of a player's group rank badge (0 = none)
    public static int codepointForPlayer(Player player)
    {
        Rank r = rankForPlayer(player);
        return r == null ? 0 : r.codepoint();
    }

    public static MutableComponent glyph(int codepoint)
    {
        return glyph(codepoint, FONT);
    }

    // single-glyph component in the given badge font
    public static MutableComponent glyph(int codepoint, ResourceLocation font)
    {
        return Component.literal(String.valueOf((char) codepoint)).withStyle(Style.EMPTY.withFont(font));
    }

    // badge glyph for rank at wall-clock timeMs: current animation frame, or the static glyph. Called every
    // render frame client-side so animated badges cycle.
    public static int animCodepoint(Rank rank, long timeMs)
    {
        if (rank == null)
            return 0;
        if (rank.frames() <= 1 || rank.frameTime() <= 0)
            return rank.codepoint();
        int frame = (int) ((timeMs / rank.frameTime()) % rank.frames());
        return rank.animStart() + frame;
    }

    // if codepoint is a rank badge glyph (static or any frame), return that rank's frame for timeMs, else
    // codepoint unchanged. Animates a badge baked into immutable text (a chat line) by swapping the glyph per frame.
    public static int animatedCodepoint(int codepoint, long timeMs)
    {
        ensureIndexLoaded();
        Rank rank = byCodepoint.get(codepoint);
        return rank == null ? codepoint : animCodepoint(rank, timeMs);
    }

    public static Component withAnimatedBadge(Rank rank, long timeMs, Component rest)
    {
        return withBadge(animCodepoint(rank, timeMs), rest, FONT);
    }

    public static Component withAnimatedBadge(Rank rank, long timeMs, Component rest, ResourceLocation font)
    {
        return withBadge(animCodepoint(rank, timeMs), rest, font);
    }

    // prepend the rank badge to rest, keeping the badge's ranks-font Style off the following text: badge + rest
    // are siblings of a neutral parent so rest keeps its own font. Fixes the "everything after the badge is a
    // box" bug. rest unchanged when there's no rank.
    public static Component withBadge(int codepoint, Component rest)
    {
        return withBadge(codepoint, rest, FONT);
    }

    public static Component withBadge(int codepoint, Component rest, ResourceLocation font)
    {
        if (codepoint <= 0)
            return rest;
        return Component.empty().append(glyph(codepoint, font)).append(Component.literal(" ")).append(rest);
    }
}
