package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;
import net.shurui.shuruisutilities.client.gui.SUHubScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

import net.minecraft.network.chat.Component;

/**
 * Hologram edit view: tabbed Position, Lines, Style and Effects. Fields persist across tab switches so Save
 * gathers all four.
 *
 * <p>Nothing here is typed that does not have to be. Every on/off option is a toggle and every option with a
 * known set of answers is a dropdown, including the ones that are really numbers: a scale, a wrap width and an
 * opacity all have a handful of sensible values and a free text box for them only invites a typo that silently
 * falls back to the old value. Coordinates and the text lines stay as fields, because those genuinely are free
 * form.
 *
 * <p>Colours are a NAMED palette here rather than a hex box. That covers what anyone actually reaches for, and
 * {@code /hologram style <name> background|glowcolor #rrggbb} still takes any colour at all for the cases it
 * does not.
 *
 * <p>Save sends the same fixed layout the server sends back: name, dim, X, Y, Z, the twelve style fields, then
 * the lines. Options the screen does not show (yaw, pitch and view range, which are {@code /hologram style} only)
 * survive because the server copies the existing hologram's style before applying what came back.
 */
public class HologramEditScreen extends FieldEditScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;
    private static final int MAX_LINES = 6;

    /** Fields ahead of the lines, matching EditorServer.HOLO_FIXED. */
    private static final int FIXED = 20;

    private static final String[] SECTION_KEYS = {
            "gui.dmz_ragnarok.core.holo.tab_position", "gui.dmz_ragnarok.core.holo.tab_lines",
            "gui.dmz_ragnarok.core.holo.tab_style", "gui.dmz_ragnarok.core.holo.tab_effects" };

    private static final List<String> BILLBOARDS = List.of("center", "vertical", "horizontal", "fixed");
    private static final List<String> ALIGNS = List.of("center", "left", "right");
    private static final List<String> SCALES = List.of("0.5", "0.75", "1.0", "1.25", "1.5", "2.0", "3.0");
    private static final List<String> WIDTHS = List.of("100", "200", "300", "400", "600");
    private static final List<String> OPACITIES = List.of("64", "128", "192", "255");

    /** How wide a picture is drawn, in blocks. Not a multiplier: 4 is four blocks across, whatever the file is. */
    private static final List<String> GIF_SIZES = List.of("1", "2", "3", "4", "6", "8", "12", "16");

    /** Named colours, as ARGB for a background and RGB for an outline. Blank is "none". */
    private static final Map<String, Integer> PALETTE = new LinkedHashMap<>();
    static
    {
        PALETTE.put("black", 0x000000);
        PALETTE.put("dark grey", 0x404040);
        PALETTE.put("grey", 0x808080);
        PALETTE.put("white", 0xFFFFFF);
        PALETTE.put("red", 0xE03B3B);
        PALETTE.put("orange", 0xFF7A18);
        PALETTE.put("yellow", 0xF2C94C);
        PALETTE.put("green", 0x45C34A);
        PALETTE.put("aqua", 0x5BE7DA);
        PALETTE.put("blue", 0x3D7EFF);
        PALETTE.put("purple", 0x9B4DE0);
        PALETTE.put("pink", 0xFF8FD0);
    }

    private final String name;
    private String dim;
    private String x, y, z;
    private final String[] lines = new String[MAX_LINES];

    private String scale, billboard, align, lineWidth, opacity, item;
    private String glowColor, background;
    private boolean glow, shadow, seeThrough, backlight;

    /** The gifs the server has in its folder, and which of them this hologram plays. */
    private final List<String> gifs;
    private String gif = "";
    /** How wide that picture is drawn, in blocks. */
    private String gifSize = "4";
    /** Where the gif line sat, so an edit puts it back where the author had it rather than at the bottom. */
    private int gifLine = -1;

    /** Nudges away from the anchor, as "x,y,z" in blocks. Blank or 0,0,0 leaves a hologram where it was. */
    private String textOffset = "0,0,0";
    private String gifOffset = "0,0,0";

    private int section = 0;

    public HologramEditScreen(List<String> gifs, List<String> row)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.hub.holograms"), UI_W, UI_H, null);
        this.gifs = gifs == null ? List.of() : gifs;
        this.name = row.get(0);
        this.dim = at(row, 1, "minecraft:overworld");
        this.x = at(row, 2, "0");
        this.y = at(row, 3, "0");
        this.z = at(row, 4, "0");
        this.scale = at(row, 5, "1.0");
        this.billboard = at(row, 6, "center");
        this.align = at(row, 7, "center");
        this.lineWidth = at(row, 8, "200");
        this.opacity = at(row, 9, "-1");
        this.item = at(row, 10, "");
        this.glow = Boolean.parseBoolean(at(row, 11, "false"));
        this.glowColor = colourName(at(row, 12, ""));
        this.background = colourName(at(row, 13, ""));
        this.shadow = Boolean.parseBoolean(at(row, 14, "false"));
        this.seeThrough = Boolean.parseBoolean(at(row, 15, "false"));
        this.backlight = Boolean.parseBoolean(at(row, 16, "false"));
        this.gifSize = trimZero(at(row, 17, "4"));
        this.textOffset = at(row, 18, "0,0,0");
        this.gifOffset = at(row, 19, "0,0,0");
        // The gif is owned by its dropdown, not by the line box, so it is lifted out of the lines here and put
        // back on save. Otherwise the same thing would be editable in two places and the two could disagree.
        List<String> text = new ArrayList<>();
        for (int i = FIXED; i < row.size(); i++)
        {
            String raw = row.get(i);
            String tagged = gifTag(raw);
            if (tagged != null && gif.isEmpty())
            {
                gif = tagged;
                gifLine = text.size();
            }
            else
            {
                text.add(raw);
            }
        }
        for (int i = 0; i < MAX_LINES; i++)
            lines[i] = i < text.size() ? text.get(i) : "";
    }

    /** The gif named by a {@code <gif:name>} line, or null if this is ordinary text. */
    private static String gifTag(String line)
    {
        if (line == null)
            return null;
        String s = line.trim().toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("<gif:") && s.endsWith(">") ? s.substring(5, s.length() - 1).trim() : null;
    }

    private static String at(List<String> row, int index, String fallback)
    {
        return row.size() > index ? row.get(index) : fallback;
    }

    /** A whole number arrives as "4.0" but the dropdown lists "4", and a value it cannot match shows as blank. */
    private static String trimZero(String value)
    {
        String s = value == null ? "" : value.trim();
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** The palette name closest to a stored hex value, so a colour set by command still shows as itself. */
    private static String colourName(String hex)
    {
        if (hex == null || hex.isBlank())
            return "";
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        try
        {
            int rgb = (int) (Long.parseLong(s, 16) & 0xFFFFFF);
            for (Map.Entry<String, Integer> e : PALETTE.entrySet())
                if (e.getValue() == rgb)
                    return e.getKey();
        }
        catch (NumberFormatException ignored)
        {
            // not a colour we wrote; fall through and show nothing rather than guess
        }
        return "";
    }

    /** A palette name back to the hex the server stores. Alpha is added for a background, which needs one. */
    private static String colourHex(String palette, boolean withAlpha)
    {
        Integer rgb = PALETTE.get(palette == null ? "" : palette);
        if (rgb == null)
            return "";
        return withAlpha ? String.format("#C0%06X", rgb) : String.format("#%06X", rgb);
    }

    private void selectSection(int s)
    {
        applyFields();
        section = s;
        rebuildWidgets();
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();
        rowY = buildNamedTabHeader(name, trAll(SECTION_KEYS), section, this::selectSection);

        switch (section)
        {
        case 0 ->
        {
            tf(tr("gui.dmz_ragnarok.core.holo.dimension"), dim, v -> dim = v);
            tip(tr("gui.dmz_ragnarok.core.holo.dimension_tip"));
            tf(tr("gui.dmz_ragnarok.core.holo.x"), x, v -> x = v);
            tf(tr("gui.dmz_ragnarok.core.holo.y"), y, v -> y = v);
            tf(tr("gui.dmz_ragnarok.core.holo.z"), z, v -> z = v);
        }
        case 1 ->
        {
            for (int i = 0; i < MAX_LINES; i++)
            {
                final int idx = i;
                tf(tr("gui.dmz_ragnarok.core.holo.line", i + 1), lines[i], v -> lines[idx] = v);
            }
            tip(tr("gui.dmz_ragnarok.core.holo.lines_tip"));
        }
        case 2 ->
        {
            df(tr("gui.dmz_ragnarok.core.holo.scale"), SCALES, scale, v -> scale = v);
            df(tr("gui.dmz_ragnarok.core.holo.billboard"), BILLBOARDS, billboard, v -> billboard = v);
            df(tr("gui.dmz_ragnarok.core.holo.align"), ALIGNS, align, v -> align = v);
            df(tr("gui.dmz_ragnarok.core.holo.line_width"), WIDTHS, lineWidth, v -> lineWidth = v);
            df(tr("gui.dmz_ragnarok.core.holo.opacity"), OPACITIES, opacity, v -> opacity = v);
            df(tr("gui.dmz_ragnarok.core.holo.item"), itemIds(), item, v -> item = v);
            tf(tr("gui.dmz_ragnarok.core.holo.text_offset"), textOffset, v -> textOffset = v);
        }
        default ->
        {
            bf(tr("gui.dmz_ragnarok.core.holo.glow"), glow, () -> glow = !glow);
            df(tr("gui.dmz_ragnarok.core.holo.glow_color"), paletteNames(), glowColor, v -> glowColor = v);
            df(tr("gui.dmz_ragnarok.core.holo.background"), paletteNames(), background, v -> background = v);
            bf(tr("gui.dmz_ragnarok.core.holo.shadow"), shadow, () -> shadow = !shadow);
            bf(tr("gui.dmz_ragnarok.core.holo.see_through"), seeThrough, () -> seeThrough = !seeThrough);
            bf(tr("gui.dmz_ragnarok.core.holo.backlight"), backlight, () -> backlight = !backlight);
            df(tr("gui.dmz_ragnarok.core.holo.gif"), gifs, gif, v -> gif = v);
            df(tr("gui.dmz_ragnarok.core.holo.gif_size"), GIF_SIZES, gifSize, v -> gifSize = v);
            tf(tr("gui.dmz_ragnarok.core.holo.gif_offset"), gifOffset, v -> gifOffset = v);
            tip(tr("gui.dmz_ragnarok.core.holo.gif_tip"));
        }
        }

        btn(14, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(82, footerY(), 64, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.delete"),
                () -> EditorScreens.act("holograms", "delete", name));
        btn(150, footerY(), 56, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.back"),
                () -> EditorScreens.reopen("holograms"));
        btn(UI_W - 62, footerY(), 48, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openAdminHub);
    }

    private static List<String> paletteNames()
    {
        return new ArrayList<>(PALETTE.keySet());
    }

    // built once. The dropdown is searchable, so the whole registry is usable, but rebuilding this list on every
    // tab switch would walk every item in the pack for nothing.
    private static List<String> itemIdCache;

    private static List<String> itemIds()
    {
        if (itemIdCache == null)
        {
            List<String> ids = new ArrayList<>();
            for (var id : net.minecraftforge.registries.ForgeRegistries.ITEMS.getKeys())
                ids.add(id.toString());
            ids.sort(String::compareTo);
            itemIdCache = ids;
        }
        return itemIdCache;
    }

    private void save()
    {
        applyFields();
        List<String> args = new ArrayList<>();
        args.add(name);
        args.add(dim.trim());
        args.add(x.trim());
        args.add(y.trim());
        args.add(z.trim());
        args.add(scale.trim());
        args.add(billboard.trim());
        args.add(align.trim());
        args.add(lineWidth.trim());
        args.add(opacity.trim());
        args.add(item.trim());
        args.add(Boolean.toString(glow));
        args.add(colourHex(glowColor, false));
        args.add(colourHex(background, true));
        args.add(Boolean.toString(shadow));
        args.add(Boolean.toString(seeThrough));
        args.add(Boolean.toString(backlight));
        args.add(gifSize.trim());
        args.add(textOffset.trim());
        args.add(gifOffset.trim());
        // put the gif back among the lines, where the author had it if it was already there
        List<String> text = new ArrayList<>();
        for (String l : lines)
            if (l != null && !l.isBlank())
                text.add(l);
        if (gif != null && !gif.isBlank())
        {
            int at = gifLine >= 0 && gifLine <= text.size() ? gifLine : text.size();
            text.add(at, "<gif:" + gif.trim() + ">");
        }
        args.addAll(text);
        EditorScreens.act("holograms", "save", args);
    }
}
