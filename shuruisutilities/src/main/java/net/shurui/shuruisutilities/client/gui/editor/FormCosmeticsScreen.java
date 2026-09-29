package net.shurui.shuruisutilities.client.gui.editor;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.FieldEditScreen;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import net.shurui.dev.sdu.client.gui.theme.GuiTheme;

/**
 * The single form cosmetic editor for a qualifying Patreon supporter: pick ONE of your forms and recolour it, plus
 * an optional outline. Appearance only; there is no stat, scale, cost or unlock control here, because the wire
 * record ({@code FormCosmetic}) has no such field and the server re-validates on save. Colours use SU's color-picker
 * field; the target form is chosen from the forms this player's race actually has (sent as rows by the server).
 *
 * <p>Also carries the five id-string channels (hair state, hair code, model, extra layer, aura), each a cycling
 * picker over exactly the values DragonMineZ ships, so an id the game cannot resolve is not selectable. Still
 * excluded: model SCALE, which changes the hitbox and reach and so is not cosmetic.
 *
 * <p>meta = [entitled, group, form, body1, body2, body3, hair, eye1, eye2, aura, extra, tint, tintIntensity,
 * outlineEnabled, outlinePrimary, outlineSecondary, outlineThickness, hairType, hairCode, model, extraLayer,
 * auraType, then five unit-separated option lists in that same channel order].
 * rows = [groupKey, formName, label] per form, DMZ forms first then stack forms.
 */
public class FormCosmeticsScreen extends FieldEditScreen
{
    // the five id-string channels, in the order the server sends them and the order save() puts them back
    private static final int ID_HAIR_TYPE = 0;
    private static final int ID_HAIR_CODE = 1;
    private static final int ID_MODEL = 2;
    private static final int ID_EXTRA_LAYER = 3;
    private static final int ID_AURA_TYPE = 4;
    private static final int ID_COUNT = 5;
    // current values start at meta[17], their option lists at meta[22]
    private static final int META_ID_VALUES = 17;
    private static final int META_ID_OPTIONS = 22;
    private static final String[] ID_LABEL_KEYS = {
            "gui.dmz_ragnarok.core.cosmetics.form_hair_type",
            "gui.dmz_ragnarok.core.cosmetics.form_hair_code",
            "gui.dmz_ragnarok.core.cosmetics.form_model",
            "gui.dmz_ragnarok.core.cosmetics.form_extra_layer",
            "gui.dmz_ragnarok.core.cosmetics.form_aura_type"
    };

    // The field column keeps its old geometry and a preview column is added to the RIGHT of it, so the existing
    // rows are untouched and only the panel gets wider.
    private static final int FIELDS_W = 300;
    private static final int PREVIEW_W = 120;
    private static final int UI_W = FIELDS_W + PREVIEW_W;
    private static final int UI_H = 252;
    // clearance kept between the footer row and the panel frame on each side
    private static final int FOOTER_MARGIN = 6;


    private final boolean entitled;
    private final List<List<String>> forms;

    // the single styled form, as an index into forms (-1 = none chosen)
    private int formIndex = -1;

    // edited appearance values, persisted across rebuildWidgets()
    private String bodyColor1, bodyColor2, bodyColor3, hairColor, eye1Color, eye2Color, auraColor, extraFormColor;
    private String tintColor, tintIntensity;
    private boolean outlineEnabled;
    private String outlinePrimary, outlineSecondary, outlineThickness;

    // id-string channels, held as an index into the matching options list (-1 = keep the form's own value)
    private final List<List<String>> idOptions = new ArrayList<>();
    private final int[] idIndex = new int[ID_COUNT];

    // index of the top visible field row
    private int scroll = 0;

    // live preview of the form as currently edited (not as saved), driven through the same derive code the real
    // render path uses so what is shown here cannot drift from what other players will see
    private final net.shurui.dev.sdu.client.gui.preview.FormPreview preview =
            new net.shurui.dev.sdu.client.gui.preview.FormPreview();
    // Radians, and pi is facing the viewer: SDU's preview uses the same convention. Starting at 0 is what had the
    // model showing its back.
    private float previewYaw = (float) Math.PI;
    private float previewPitch = 0.0f;
    private boolean draggingPreview = false;
    private boolean hairAutoApplied = false;

    public FormCosmeticsScreen(List<String> meta, List<List<String>> rows)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.cosmetics.form_title"), UI_W, UI_H, null);
        this.entitled = !meta.isEmpty() && Boolean.parseBoolean(meta.get(0));
        this.forms = rows == null ? new ArrayList<>() : rows;

        String group = meta.size() > 1 ? meta.get(1) : "";
        String form = meta.size() > 2 ? meta.get(2) : "";
        bodyColor1 = get(meta, 3);
        bodyColor2 = get(meta, 4);
        bodyColor3 = get(meta, 5);
        hairColor = get(meta, 6);
        eye1Color = get(meta, 7);
        eye2Color = get(meta, 8);
        auraColor = get(meta, 9);
        extraFormColor = get(meta, 10);
        tintColor = get(meta, 11);
        tintIntensity = meta.size() > 12 ? meta.get(12) : "0";
        outlineEnabled = meta.size() > 13 && Boolean.parseBoolean(meta.get(13));
        outlinePrimary = get(meta, 14);
        outlineSecondary = get(meta, 15);
        outlineThickness = meta.size() > 16 ? meta.get(16) : "1.5";

        // id channels: read the option list the server sent, then find the stored value in it. A stored value that
        // is not in the list (DMZ config changed under us) simply reads as "keep the form's own value".
        for (int i = 0; i < ID_COUNT; i++)
        {
            String joined = meta.size() > META_ID_OPTIONS + i ? meta.get(META_ID_OPTIONS + i) : "";
            List<String> opts = new ArrayList<>();
            if (!joined.isEmpty())
                for (String v : joined.split(net.shurui.shuruisutilities.cosmetics.form.FormAppearanceOptions.SEP, -1))
                    if (!v.isEmpty())
                        opts.add(v);
            idOptions.add(opts);

            String current = meta.size() > META_ID_VALUES + i ? meta.get(META_ID_VALUES + i) : "";
            idIndex[i] = -1;
            for (int j = 0; j < opts.size(); j++)
                if (opts.get(j).equalsIgnoreCase(current))
                {
                    idIndex[i] = j;
                    break;
                }
        }

        // preselect the stored form
        for (int i = 0; i < forms.size(); i++)
        {
            List<String> r = forms.get(i);
            if (r.size() >= 2 && r.get(0).equalsIgnoreCase(group) && r.get(1).equalsIgnoreCase(form))
            {
                formIndex = i;
                break;
            }
        }
    }

    private static String get(List<String> l, int i)
    {
        return i < l.size() ? l.get(i) : "";
    }

    // Keep the scrollbar (and any trailing row control) against the right edge of the FIELDS column. The base
    // class derives both from the panel width, which after adding the preview column would draw the bar inside the
    // preview and leave it under the model's drag area.
    @Override
    protected int standardScrollbarX()
    {
        return FIELDS_W - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH;
    }

    @Override
    protected int rowControlRight()
    {
        return FIELDS_W - GuiTheme.SCROLLBAR_PANEL_INSET - GuiTheme.SCROLLBAR_WIDTH - GuiTheme.UNIT;
    }

    // Cap the field column at the fields area, otherwise the base class sizes fields against the (now wider)
    // panel and they run under the preview.
    @Override
    protected int fieldColW()
    {
        return Math.max(1, Math.min(132, FIELDS_W - GuiTheme.CONTENT_PADDING - fieldColX()));
    }

    @Override
    protected void init()
    {
        super.init();
        clearFields();

        if (!entitled)
        {
            // server should have bounced an unentitled player; be defensive and offer only the way back
            label("§c" + tr("gui.dmz_ragnarok.core.cosmetics.form_not_entitled"), 14, 40);
            btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(),
                    Component.translatable("gui.dmz_ragnarok.core.btn.menu"), () -> EditorScreens.reopen("cosmetics"));
            return;
        }

        rowY = net.shurui.dev.sdu.client.gui.theme.GuiTheme.CONTENT_TOP;

        // form selector: a cycling button through this race's forms (none -> first -> ... -> none)
        label(tr("gui.dmz_ragnarok.core.cosmetics.form_form"), 14, rowY + 2);
        String formLabel = formIndex < 0 || formIndex >= forms.size()
                ? tr("gui.dmz_ragnarok.core.common.none")
                : forms.get(formIndex).get(2);
        btn(fieldColX(), rowY, fieldColW(), 11, Component.literal(formLabel), this::cycleForm);
        rowY += ROW_H;

        // Every field row is collected first and only the visible window is emitted, so the channel list can grow
        // without the panel growing past the screen. Rows are uniform ROW_H, which is what scrollList expects.
        List<Runnable> fieldRows = new ArrayList<>();

        // colour channels
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_aura"), () -> auraColor, v -> auraColor = v));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_body1"), () -> bodyColor1, v -> bodyColor1 = v));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_body2"), () -> bodyColor2, v -> bodyColor2 = v));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_body3"), () -> bodyColor3, v -> bodyColor3 = v));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_hair"), () -> hairColor, v -> hairColor = v));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_eye1"), () -> eye1Color, v -> eye1Color = v));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_eye2"), () -> eye2Color, v -> eye2Color = v));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_extra"), () -> extraFormColor, v -> extraFormColor = v));

        // id-string channels: cycling pickers over exactly the values DMZ ships, so nothing unresolvable can be
        // chosen. "(none)" keeps the form's own value.
        for (int i = 0; i < ID_COUNT; i++)
        {
            final int channel = i;
            fieldRows.add(() -> idRow(channel));
        }

        // whole-body tint: colour + strength (0..1). Only applied when a colour is set and strength > 0.
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_tint"), () -> tintColor, v -> tintColor = v));
        fieldRows.add(() -> tf(tr("gui.dmz_ragnarok.core.cosmetics.form_tint_strength"), tintIntensity, v -> tintIntensity = v));

        // outline: on/off + two colours + thickness (0..3)
        fieldRows.add(() -> bf(tr("gui.dmz_ragnarok.core.cosmetics.form_outline"), outlineEnabled, () -> outlineEnabled = !outlineEnabled));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_outline1"), () -> outlinePrimary, v -> outlinePrimary = v));
        fieldRows.add(() -> cf(tr("gui.dmz_ragnarok.core.cosmetics.form_outline2"), () -> outlineSecondary, v -> outlineSecondary = v));
        fieldRows.add(() -> tf(tr("gui.dmz_ragnarok.core.cosmetics.form_outline_thickness"), outlineThickness, v -> outlineThickness = v));

        int listTop = rowY;
        // rowsThatFit reads this screen's own uiHeight (252), so it fills the fields pane correctly; the scrollbar
        // column stays FIELDS_W-based (below), NOT uiWidth, because uiWidth spans the preview pane too.
        int cap = Math.min(rowsThatFit(listTop, ROW_H), fieldRows.size());
        scroll = Math.max(0, Math.min(scroll, Math.max(0, fieldRows.size() - cap)));
        for (int i = scroll; i < Math.min(fieldRows.size(), scroll + cap); i++)
            fieldRows.get(i).run();
        // applyFields() before rebuilding, or text typed into a visible box is lost when the window moves
        scrollList(14, FIELDS_W - 12, listTop, ROW_H, cap, fieldRows.size(), scroll,
                v -> { applyFields(); scroll = v; rebuildWidgets(); });

        // Three buttons centred inside the panel. Derived from UI_W rather than hand-placed: the old fixed offsets
        // put the row at x -2..302 inside a 300 wide panel, so both outer buttons hung over the frame.
        int by = footerY();
        int gap = 8;
        int bw = (UI_W - FOOTER_MARGIN * 2 - gap * 2) / 3;
        int bx = (UI_W - (bw * 3 + gap * 2)) / 2;
        btn(bx, by, bw, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.save"), this::save);
        btn(bx + bw + gap, by, bw, footerBtnHeight(),
                Component.translatable("gui.dmz_ragnarok.core.cosmetics.form_reset"), this::clear);
        btn(bx + (bw + gap) * 2, by, bw, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                () -> EditorScreens.reopen("cosmetics"));
    }

    // one cycling picker: label on the left, current value as a button on the right. Empty option list means DMZ
    // exposed no values for that channel, so the button is inert rather than misleadingly clickable.
    private void idRow(int channel)
    {
        List<String> opts = idOptions.get(channel);
        label(tr(ID_LABEL_KEYS[channel]), 14, rowY + 2);
        int idx = idIndex[channel];
        String shown = idx < 0 || idx >= opts.size()
                ? tr("gui.dmz_ragnarok.core.common.none")
                : opts.get(idx);
        var b = btn(fieldColX(), rowY, fieldColW(), 11, Component.literal(shown), () -> cycleId(channel));
        b.active = !opts.isEmpty();
        rowY += ROW_H;
    }

    private void cycleId(int channel)
    {
        applyFields();
        List<String> opts = idOptions.get(channel);
        if (opts.isEmpty())
            return;
        idIndex[channel]++;
        if (idIndex[channel] >= opts.size())
            idIndex[channel] = -1;
        rebuildWidgets();
    }

    private String idValue(int channel)
    {
        List<String> opts = idOptions.get(channel);
        int idx = idIndex[channel];
        return idx < 0 || idx >= opts.size() ? "" : opts.get(idx);
    }

    private void cycleForm()
    {
        applyFields();
        formIndex++;
        if (formIndex >= forms.size())
            formIndex = -1;
        rebuildWidgets();
    }

    private void save()
    {
        applyFields();
        String group = formIndex >= 0 && formIndex < forms.size() ? forms.get(formIndex).get(0) : "";
        String form = formIndex >= 0 && formIndex < forms.size() ? forms.get(formIndex).get(1) : "";
        List<String> args = new ArrayList<>();
        args.add(group);
        args.add(form);
        args.add(nz(bodyColor1));
        args.add(nz(bodyColor2));
        args.add(nz(bodyColor3));
        args.add(nz(hairColor));
        args.add(nz(eye1Color));
        args.add(nz(eye2Color));
        args.add(nz(auraColor));
        args.add(nz(extraFormColor));
        args.add(nz(tintColor));
        args.add(nz(tintIntensity));
        args.add(Boolean.toString(outlineEnabled));
        args.add(nz(outlinePrimary));
        args.add(nz(outlineSecondary));
        args.add(nz(outlineThickness));
        args.add(idValue(ID_HAIR_TYPE));
        args.add(idValue(ID_HAIR_CODE));
        args.add(idValue(ID_MODEL));
        args.add(idValue(ID_EXTRA_LAYER));
        args.add(idValue(ID_AURA_TYPE));
        EditorScreens.act("cosmetics", "formsave", args);
    }

    private void clear()
    {
        EditorScreens.act("cosmetics", "formclear");
    }

    private static String nz(String s)
    {
        return s == null ? "" : s;
    }


    /** The override as currently EDITED (not as saved), so the preview tracks the controls. */
    private net.shurui.shuruisutilities.cosmetics.form.FormCosmetic editedCosmetic()
    {
        var c = new net.shurui.shuruisutilities.cosmetics.form.FormCosmetic();
        c.group = formIndex >= 0 && formIndex < forms.size() ? forms.get(formIndex).get(0) : "";
        c.form = formIndex >= 0 && formIndex < forms.size() ? forms.get(formIndex).get(1) : "";
        c.bodyColor1 = nz(bodyColor1);
        c.bodyColor2 = nz(bodyColor2);
        c.bodyColor3 = nz(bodyColor3);
        c.hairColor = nz(hairColor);
        c.eye1Color = nz(eye1Color);
        c.eye2Color = nz(eye2Color);
        c.auraColor = nz(auraColor);
        c.extraFormColor = nz(extraFormColor);
        c.tintColor = nz(tintColor);
        c.tintIntensity = parseD(tintIntensity, 0.0);
        c.outlineEnabled = outlineEnabled;
        c.outlinePrimary = nz(outlinePrimary);
        c.outlineSecondary = nz(outlineSecondary);
        c.outlineThickness = parseD(outlineThickness, 1.5);
        c.hairType = idValue(ID_HAIR_TYPE);
        c.hairCode = idValue(ID_HAIR_CODE);
        c.customModel = idValue(ID_MODEL);
        c.extraFormLayer = idValue(ID_EXTRA_LAYER);
        c.auraType = idValue(ID_AURA_TYPE);
        return c.sanitize();
    }

    /**
     * This client's race EXACTLY as DMZ spells it. Passed to the preview, which hands it to
     * {@code Character.setRace}: lower-casing it (which is right for ConfigManager keys, see
     * {@link #localRaceKey()}) is not necessarily right for DMZ's texture lookup, and a race DMZ does not
     * recognise is a plausible reason for the model falling back off its race textures.
     */
    private String localRace()
    {
        try
        {
            var p = minecraft.player;
            if (p == null)
                return "";
            var data = com.dragonminez.common.stats.StatsProvider
                    .get(com.dragonminez.common.stats.StatsCapability.INSTANCE, p).resolve().orElse(null);
            if (data == null || data.getCharacter() == null)
                return "";
            String r = data.getCharacter().getRaceName();
            return r == null ? "" : r;
        }
        catch (Throwable t)
        {
            return "";
        }
    }

    /** Lower-cased race, which is how ConfigManager keys its form tables. */
    private String localRaceKey()
    {
        return localRace().toLowerCase(java.util.Locale.ROOT);
    }

    /** The stock form the edits are applied on top of: a race form, or a stack form (which is race independent). */
    private com.dragonminez.common.config.FormConfig.FormData baseForm(String race, String group, String form)
    {
        if (group.isEmpty() || form.isEmpty())
            return null;
        try
        {
            var fd = com.dragonminez.common.config.ConfigManager.getForm(race, group, form);
            return fd != null ? fd : com.dragonminez.common.config.ConfigManager.getStackForm(group, form);
        }
        catch (Throwable t)
        {
            return null;
        }
    }

    /**
     * DMZ's {@code FormConfig.FormData} mapped onto SDU's mirror of it, which is what the preview widget takes.
     * Only the appearance channels are carried over: the preview draws a throwaway dummy, so multipliers, costs and
     * mastery are meaningless to it, and copying them would only invite someone to think this path touches stats.
     */
    private static net.shurui.dev.sdu.form.FormData toPreviewForm(
            com.dragonminez.common.config.FormConfig.FormData d)
    {
        var f = new net.shurui.dev.sdu.form.FormData();
        f.name = s(d.getName());
        f.customModel = s(d.getCustomModel());
        f.extraFormLayer = s(d.getExtraFormLayer());
        f.extraFormColor = s(d.getExtraFormColor());
        f.hairType = s(d.getHairType());
        f.forcedHairCode = s(d.getForcedHairCode());
        f.hairColor = s(d.getHairColor());
        f.bodyColor1 = s(d.getBodyColor1());
        f.bodyColor2 = s(d.getBodyColor2());
        f.bodyColor3 = s(d.getBodyColor3());
        f.eye1Color = s(d.getEye1Color());
        f.eye2Color = s(d.getEye2Color());
        f.auraType = s(d.getAuraType());
        f.auraColor = s(d.getAuraColor());
        f.tintColor = s(d.getTintColor());
        Integer al = d.getAuraLayer();
        f.auraLayer = al == null ? 0 : al;
        Double ti = d.getTintIntensity();
        f.tintIntensity = ti == null ? 0.0 : ti;
        Boolean hl = d.getHasLightnings();
        f.hasLightnings = hl != null && hl;
        f.lightningColor = s(d.getLightningColor());
        Float[] ms = d.getModelScaling();
        if (ms != null && ms.length >= 3)
            f.modelScaling = new float[] { nzf(ms[0]), nzf(ms[1]), nzf(ms[2]) };
        return f;
    }

    private static String s(String v)
    {
        return v == null ? "" : v;
    }

    // Drawn AFTER super.render, in screen space: renderEntityInInventory needs real screen coordinates, so the
    // virtual panel coords are converted with originX/originY and guiScale rather than drawn inside the scaled pose.
    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);
        if (!entitled || preview.isUnavailable())
            return;
        try
        {
            renderPreview(g);
        }
        catch (Throwable ignored)
        {
            // a preview hiccup must never take the editor down with it
        }
    }

    private void renderPreview(GuiGraphics g)
    {
        var edited = editedCosmetic();
        String race = localRace();
        var base = baseForm(localRaceKey(), edited.group, edited.form);
        if (base == null)
            return;
        // Same derive the real render path uses, so the preview shows exactly what everyone else will see.
        var shown = net.shurui.shuruisutilities.cosmetics.form.client.FormCosmeticClientStore.deriveFor(base, edited);
        var form = shown != null ? shown : base;

        g.flush();
        preview.configure(toPreviewForm(form), race, edited.group,
                !edited.auraColor.isEmpty() || !edited.auraType.isEmpty());
        if (!hairAutoApplied)
        {
            hairAutoApplied = true;
            String code = form.getForcedHairCode();
            if (code != null && !code.isBlank())
                preview.applyHairCode(code);
        }

        // The form's own model scaling is honoured, so a big model previews big: this mirrors what the world render
        // does rather than normalising every form to one size.
        float infl = 1.0f;
        Float[] scaling = form.getModelScaling();
        if (scaling != null && scaling.length >= 3)
        {
            float mx = Math.max(nzf(scaling[0]), Math.max(nzf(scaling[1]), nzf(scaling[2])));
            if (mx > 0.0f)
                infl = mx;
        }
        infl = Math.max(0.1f, infl);

        int top = GuiTheme.CONTENT_TOP;
        int bottom = footerY() - 4;
        int boxH = Math.max(24, bottom - top);
        int cxV = FIELDS_W + PREVIEW_W / 2;
        int modelHV = (int) (boxH * 0.68);
        int feetV = top + boxH / 2 + modelHV / 2;
        int scaleV = Math.max(4, (int) (modelHV / 1.9F / infl));

        int cxS = (int) Math.round(originX() + cxV * guiScale);
        int feetS = (int) Math.round(originY() + feetV * guiScale);
        int scaleS = Math.max(1, (int) Math.round(scaleV * guiScale));

        int clipX0 = (int) Math.round(originX() + (FIELDS_W + 4) * guiScale);
        int clipY0 = (int) Math.round(originY() + top * guiScale);
        int clipX1 = (int) Math.round(originX() + (UI_W - 6) * guiScale);
        int clipY1 = (int) Math.round(originY() + bottom * guiScale);
        g.enableScissor(clipX0, clipY0, clipX1, clipY1);
        preview.renderModel(g, cxS, feetS, scaleS, previewYaw, previewPitch);
        g.disableScissor();
    }

    private static float nzf(Float f)
    {
        return f == null ? 0.0f : f;
    }

    // drag inside the preview column to turn the model
    @Override
    public boolean mouseClicked(double mx, double my, int button)
    {
        double vx = toVirtualX(mx);
        double vy = toVirtualY(my);
        if (button == 0 && vx >= FIELDS_W && vx <= UI_W && vy >= GuiTheme.CONTENT_TOP && vy <= footerY())
        {
            draggingPreview = true;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy)
    {
        if (draggingPreview)
        {
            // Radians per pixel, matching SDU's form preview. The previous values were degree-sized numbers fed
            // into a radian angle, which is why one small drag spun the model several times round.
            previewYaw += (float) (dx * 0.016);
            previewPitch = Math.max(-1.2f, Math.min(1.2f, previewPitch + (float) (dy * 0.016)));
            return true;
        }
        return super.mouseDragged(mx, my, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button)
    {
        if (draggingPreview && button == 0)
            draggingPreview = false;
        return super.mouseReleased(mx, my, button);
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }
}
