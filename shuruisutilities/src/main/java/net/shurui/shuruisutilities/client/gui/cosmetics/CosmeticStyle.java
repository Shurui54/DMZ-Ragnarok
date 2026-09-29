package net.shurui.shuruisutilities.client.gui.cosmetics;

import java.util.HashMap;
import java.util.Map;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import net.shurui.shuruisutilities.client.gui.theme.GuiText;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticDef;
import net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality;
import net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticClientStore;

/**
 * The cosmetics screens' own small visual vocabulary: the quality colours, the item tile, the quality frame and
 * the emblem inside it (the cosmetic's own model, drawn as an item icon).
 *
 * <h2>Why this is not in {@code GuiTheme}</h2>
 * {@code GuiTheme} exists as FIVE near-identical copies, one per original addon, all pointing at the same PNGs.
 * Anything added to one copy and not the other four is divergence, and divergence in that file has already cost
 * this project once. Nothing here is needed by another addon's screens, so it lives beside the screens that use
 * it and READS {@code GuiTheme} for every shared token (the slot art, the text padding, the gold). If a second
 * addon ever needs a cosmetic tile, this class moves; the theme still does not grow.
 *
 * <h2>Quality has to read without text</h2>
 * Normal is deliberately quiet: no frame, neutral text, the plain slot art. Super frames the tile red and Magic
 * frames it pink and breathes, so a grid of forty Normal hats with one Magic in it points at the Magic one from
 * across the room. These two constants are the SINGLE source of the quality colours: every tile frame, name,
 * counter and tooltip goes through {@link #color(net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality)}
 * and {@link #frame(net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticQuality)}, so a colour is changed here
 * and nowhere else.
 *
 * <p>Client only.
 */
public final class CosmeticStyle
{
    private CosmeticStyle()
    {
    }

    /** Super: a clear red, distinct from the old orange so Super reads as Super rather than as a warm Normal. */
    public static final int SUPER = 0xFFD83A34;

    /** Magic: a pink magenta, moved off the old purple so it reads as pink at a glance. */
    public static final int MAGIC = 0xFFD24FC0;

    /** Normal: the theme's ordinary row colour, so Normal reads as "nothing special" rather than as gold. */
    public static final int NORMAL = GuiTheme.COLOR_ROW;

    /** The tile edge when a tile is the current selection. */
    public static final int SELECTED = 0xFFFFFFFF;

    /** The marker colour for "this is the copy you are wearing". */
    public static final int EQUIPPED = GuiTheme.COLOR_TITLE;

    /** Standard tile edge, one virtual px, for tiles with no quality frame. */
    private static final int TILE_EDGE = 0xFF3A3A42;

    /** How much of a tile's edge the drawn item icon occupies. The rest is the slot bevel and the frame. */
    private static final float ICON_FILL = 0.62F;

    /** The Z offset {@link GuiGraphics#renderItem} applies to an item icon before drawing it. */
    private static final float ITEM_Z = 150.0F;

    /**
     * The Z at which a decoration drawn OVER a tile's icon has to sit.
     *
     * <p>Not tidiness, a depth-test fact. {@link GuiGraphics#renderItem} translates the pose by {@link #ITEM_Z}
     * and flushes, and the GUI projection puts a larger Z in FRONT, so anything drawn afterwards at Z 0 fails the
     * depth test and vanishes behind the icon. The tile scales the pose before the icon is drawn, which scales
     * that offset too, so the number this has to beat GROWS WITH THE TILE. Deriving it means a bigger tile later
     * cannot silently start eating its own counter, which a constant picked for today's 42px tile would.
     */
    public static int decorationZ(int size)
    {
        return (int) Math.ceil(ITEM_Z * iconScale(size)) + 50;
    }

    /** Pose scale that takes a 16px item icon to {@link #ICON_FILL} of a tile's edge. */
    private static float iconScale(int size)
    {
        return size * ICON_FILL / 16.0F;
    }

    /** Colour for one quality's name and frame. */
    public static int color(CosmeticQuality quality)
    {
        if (quality == CosmeticQuality.SUPER)
            return SUPER;
        if (quality == CosmeticQuality.MAGIC)
            return MAGIC;
        return NORMAL;
    }

    /** The frame colour for a quality, or 0 when that quality is not framed (Normal). */
    public static int frame(CosmeticQuality quality)
    {
        return quality == CosmeticQuality.NORMAL ? 0 : color(quality);
    }

    /**
     * One item tile: the theme's slot art, the quality frame, and the markers for worn and selected.
     *
     * <p>{@code size} is the drawn edge in virtual units. The art is 36x36 and is scaled rather than sliced,
     * because it is a single bevelled square with nothing to splice.
     */
    public static void tile(GuiGraphics g, int x, int y, int size, CosmeticQuality quality,
            boolean equipped, boolean selected, boolean hovered)
    {
        g.blit(GuiTheme.SLOT, x, y, size, size, 0.0F, 0.0F,
                GuiTheme.SLOT_TEX_W, GuiTheme.SLOT_TEX_H, GuiTheme.SLOT_TEX_W, GuiTheme.SLOT_TEX_H);

        int framed = frame(quality);
        if (framed != 0)
        {
            // Magic breathes. The period is long (about two and a half seconds) so it reads as alive rather than
            // as a blinking warning, and it is derived from the wall clock rather than from game time, so it
            // keeps moving while the game is paused in singleplayer with this screen open.
            int alpha = 0xFF;
            if (quality == CosmeticQuality.MAGIC)
            {
                float t = (System.currentTimeMillis() % 2600L) / 2600.0F;
                alpha = 0x99 + (int) (0x66 * (0.5F + 0.5F * Mth.sin(t * (float) Math.PI * 2.0F)));
            }
            int edge = (framed & 0x00FFFFFF) | (Math.min(0xFF, alpha) << 24);
            outline(g, x, y, size, size, edge);
            outline(g, x + 1, y + 1, size - 2, size - 2, (framed & 0x00FFFFFF) | 0x55000000);
        }
        else
        {
            outline(g, x, y, size, size, TILE_EDGE);
        }

        if (equipped)
        {
            // A solid bar along the bottom inside edge: "this one is on you". Distinct from the quality frame
            // because the two answer different questions and a player must be able to read both at once.
            g.fill(x + 2, y + size - 4, x + size - 2, y + size - 2, EQUIPPED);
        }
        if (selected)
        {
            outline(g, x - 1, y - 1, size + 2, size + 2, SELECTED);
        }
        if (hovered)
        {
            g.fill(x + 1, y + 1, x + size - 1, y + size - 1, GuiTheme.COLOR_HOVER_WASH);
        }
    }

    /**
     * What goes INSIDE a tile: the cosmetic's own model, drawn as an item icon, or its name when there is none.
     *
     * <h2>The model IS the icon</h2>
     * {@link CosmeticDef} carries {@code modelPreviewId}, {@code modelId} and {@code modelSelfId}. The tile uses
     * the preview one and falls back to {@code modelId}, which is the fallback that field's own contract already
     * promises; {@code modelSelfId} is the first-person variant and has no business in a menu. The value resolves
     * to an ITEM and is drawn through {@link GuiGraphics#renderItem}, which is how every other slot in this suite
     * draws a thing (see {@code ItemDisplay}). Nothing here needs per-cosmetic PNG art, and there is no second
     * art path to keep populated.
     *
     * <h2>The text fallback stays</h2>
     * A cosmetic with no model yet, or a model naming something this client does not have, draws its NAME across
     * up to two lines. That is honest: it says which cosmetic the tile is without pretending there is art. It is
     * also what a pet entry will show until pets have an item to stand for them.
     */
    public static void emblem(GuiGraphics g, Font font, String catalogId, String name, int x, int y, int size,
            int color)
    {
        ItemStack icon = iconFor(catalogId);
        // Checked per draw, not cached with the icon: the art is streamed by the Ragnarok Key and can land (or not
        // yet be here) after the icon was resolved. Until it does, the tile shows the name, never a magenta cube.
        if (!icon.isEmpty()
                && net.shurui.shuruisutilities.cosmetics.wardrobe.client.CosmeticArt.hasItemModel(icon))
        {
            float scale = iconScale(size);
            PoseStack pose = g.pose();
            pose.pushPose();
            // Scale about the tile centre, then step back by half an icon, because renderItem centres the model
            // on (x + 8, y + 8) of its own accord.
            pose.translate(x + size / 2.0F, y + size / 2.0F, 0.0F);
            pose.scale(scale, scale, scale);
            pose.translate(-8.0F, -8.0F, 0.0F);
            g.renderItem(icon, 0, 0);
            pose.popPose();
            return;
        }
        String text = name == null || name.isBlank() ? (catalogId == null ? "" : catalogId) : name;
        int inner = Math.max(1, size - 2 * GuiTheme.TEXT_PADDING_X);
        String[] lines = split(font, text, inner);
        int cx = x + size / 2;
        int top = y + size / 2 - (lines.length * font.lineHeight) / 2;
        for (int i = 0; i < lines.length; i++)
            GuiText.drawFittedCentered(g, font, lines[i], cx, top + i * font.lineHeight, inner, font.lineHeight,
                    color);
    }

    /**
     * Break a name over at most two lines at a word boundary, so "Burning Bat Hat" reads as two lines rather
     * than as one shrunk to illegibility. One line when it already fits.
     */
    private static String[] split(Font font, String text, int inner)
    {
        if (font.width(text) <= inner)
            return new String[] { text };
        int best = -1;
        for (int i = 0; i < text.length(); i++)
        {
            if (text.charAt(i) != ' ')
                continue;
            // The last break whose left half still fits: that packs the most onto line one.
            if (font.width(text.substring(0, i)) <= inner)
                best = i;
        }
        if (best <= 0)
            return new String[] { text };
        return new String[] { text.substring(0, best), text.substring(best + 1) };
    }

    // Resolution is cached because it is asked once per tile per frame and a miss walks the item registry. The
    // cache is dropped whenever the CATALOGUE changes, which is the only thing that can change a cosmetic's
    // model id, so an admin editing a model sees the new icon without a relog.
    private static final Map<String, ItemStack> ICONS = new HashMap<>();
    private static int iconsFor = -1;

    /**
     * The stack that stands for a cosmetic in a menu, or {@link ItemStack#EMPTY} when it has no usable model.
     *
     * <p>Public because the shop preview will want the same answer for the same cosmetic, and two places
     * disagreeing about which icon a hat has would be a bug nobody would think to look for.
     */
    public static ItemStack iconFor(String catalogId)
    {
        if (catalogId == null || catalogId.isBlank())
            return ItemStack.EMPTY;
        int version = CosmeticClientStore.catalogVersion();
        if (version != iconsFor)
        {
            iconsFor = version;
            ICONS.clear();
        }
        ItemStack cached = ICONS.get(catalogId);
        if (cached != null)
            return cached;
        CosmeticDef def = CosmeticClientStore.def(catalogId);
        ItemStack resolved = ItemStack.EMPTY;
        if (def != null)
        {
            // The preview variant first, then the third-person model, which is the fallback the field documents.
            String pick = def.modelPreviewId == null || def.modelPreviewId.isBlank() ? def.modelId
                    : def.modelPreviewId;
            Item item = itemFor(pick);
            if (item != null)
                resolved = new ItemStack(item);
        }
        ICONS.put(catalogId, resolved);
        return resolved;
    }

    /**
     * Read a model field as an item.
     *
     * <p>Two spellings are accepted and the second one is the reason this is not a one-liner. A plain item id
     * ({@code dmz_ragnarok:bat_hat}) is the contract. A MODEL PATH ({@code dmz_ragnarok:item/cosmetic/bat_hat})
     * is what this field's javadoc used to ask for, and a bare model path cannot be drawn at all: a model is only
     * baked if something registered it with {@code ModelEvent.RegisterAdditional}, and the catalogue that would
     * name it arrives at LOGIN, long after baking. Rather than leave every record written to the old convention
     * blank, the last path segment is tried as an item id in the same namespace, which is the id such a model
     * would belong to anyway.
     *
     * <p>Never throws. An unparseable value is a cosmetic with no icon, not an exception on the render thread.
     */
    private static Item itemFor(String raw)
    {
        if (raw == null || raw.isBlank())
            return null;
        String value = raw.trim();
        Item direct = lookup(value);
        if (direct != null)
            return direct;
        int slash = value.lastIndexOf('/');
        if (slash < 0 || slash + 1 >= value.length())
            return null;
        int colon = value.indexOf(':');
        String namespace = colon > 0 ? value.substring(0, colon + 1) : "";
        return lookup(namespace + value.substring(slash + 1));
    }

    private static Item lookup(String id)
    {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        if (rl == null)
            return null;
        Item item = ForgeRegistries.ITEMS.getValue(rl);
        // AIR is what an absent id reads as, and an air icon is an invisible tile, so it counts as "no model".
        return item == null || item == Items.AIR ? null : item;
    }

    /** A one-virtual-pixel rectangle outline. */
    public static void outline(GuiGraphics g, int x, int y, int w, int h, int color)
    {
        g.fill(x, y, x + w, y + 1, color);
        g.fill(x, y + h - 1, x + w, y + h, color);
        g.fill(x, y + 1, x + 1, y + h - 1, color);
        g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    /**
     * An effect id as a player should read it: {@code burning_flames} becomes {@code Burning Flames}.
     *
     * <h2>This is the LAST RESORT, not the normal path</h2>
     * {@link net.shurui.shuruisutilities.cosmetics.wardrobe.CosmeticEffect} now carries a real
     * {@code displayName}, and the server puts it straight on the wardrobe row, so a player normally reads the
     * name an admin typed. Effects are NOT synced to clients (the catalogue packet carries definitions only), so
     * there is nothing here to look one up in. This is what happens when the row carries no name: an effect id
     * orphaned by a deleted effect record, or a server older than that row field. Prettifying beats showing a
     * raw snake_case token, and it beats showing nothing at all for a copy that visibly has an effect.
     */
    public static String effectName(String effectId)
    {
        if (effectId == null || effectId.isBlank())
            return "";
        String[] parts = effectId.trim().replace('-', '_').split("_");
        StringBuilder out = new StringBuilder();
        for (String part : parts)
        {
            if (part.isEmpty())
                continue;
            if (out.length() > 0)
                out.append(' ');
            out.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1)
                out.append(part.substring(1));
        }
        return out.length() == 0 ? effectId : out.toString();
    }
}
