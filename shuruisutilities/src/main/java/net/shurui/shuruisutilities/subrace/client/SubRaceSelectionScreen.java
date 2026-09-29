package net.shurui.shuruisutilities.subrace.client;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import org.joml.Quaternionf;
import org.joml.Quaternionfc;

import com.mojang.blaze3d.systems.RenderSystem;

import com.dragonminez.client.gui.buttons.TexturedTextButton;
import com.dragonminez.client.gui.character.util.ScaledScreen;
import com.dragonminez.client.util.TextUtil;
import com.dragonminez.common.stats.character.Character;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.CubeMap;
import net.minecraft.client.renderer.PanoramaRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;

/**
 * SU's sub-race chooser, shown after a player confirms a parent race that has more than one visible option (the parent
 * itself plus one or more unlocked sub-races). It reads as one more of DragonMineZ's character-customization steps: the
 * same green {@code menubig} panel on the left holding a grid of option cards (each card previews that sub-race's model
 * by rendering the local player with its race temporarily applied), the same big centre-right player preview DMZ shows
 * everywhere, DMZ's own step counter and the same textured Back / Next buttons. On Next the chosen id is handed back to
 * the {@code RaceSelectionScreen} mixin, which commits the race and opens DMZ's customization screen. Back returns to
 * the race carousel without committing anything.
 *
 * <p><b>Extends {@link ScaledScreen} as ordinary Java.</b> DMZ is a compile dependency, so this subclasses
 * {@code ScaledScreen} directly to inherit its UI-scale helpers ({@code getUiWidth}, {@code toScreenCoord},
 * {@code beginUiScale}, {@code tr}, {@code txt}, the {@code DMZ_FONT}). This is NOT the thing that previously broke the
 * DMZ client: that was {@code @Shadow}-ing superclass members from a {@code targets=} mixin. No member of
 * {@code ScaledScreen} is shadowed anywhere; this is a plain subclass.
 *
 * <p><b>Coordinate space.</b> {@code ScaledScreen} converts incoming mouse events to UI space before they reach this
 * screen and expects widgets to be added in UI coordinates. Every coordinate here is therefore UI space:
 * {@code getUiWidth()} / {@code getUiHeight()} for layout, {@code toScreenCoord(...)} only where a raw pixel is required
 * (scissor rectangles). Model previews are wrapped in {@code beginUiScale} / {@code endUiScale} so they draw at the
 * same scale as the rest of the screen.
 *
 * <p><b>Preview safety.</b> Each card and the big centre preview render the LOCAL PLAYER with the previewed race
 * temporarily applied to its capability {@link Character} (the same instance DMZ mutates), then RESTORE the original
 * race in a {@code finally}. An exception mid-render would otherwise leave the player's race corrupted client-side,
 * exactly as DMZ guards its own equivalent.
 */
public class SubRaceSelectionScreen extends ScaledScreen
{
    private static final ResourceLocation MENU_BIG =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/gui/menu/menubig.png");
    private static final ResourceLocation BUTTONS_TEXTURE =
            ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/gui/buttons/characterbuttons.png");

    // Card grid geometry. Measured against the menubig panel's actual opaque interior: the 141x213 blit has a ~6px light
    // border, so the usable green interior runs x=7..134 (about 128px wide) and y=7..206. The previous constants
    // (START_X=26, PITCH_X=39) put column 3's right border at panelX+138, past the x=134 inner edge, which clipped the
    // third column and pushed the seventh card out of line. These fit three columns fully inside the interior with even
    // ~4px side margins: 3 cards of 34px plus 5px gaps span 118px, centered in the 128px interior. START_X=13 puts the
    // first card's border outset (x-1) at 12, four pixels in from the x=8 inner edge; PITCH_X=41 (34 card + 2 border
    // outset + 5 gap) lands column 3's right border at panelX+130, four pixels clear of the x=134 inner edge. The Y
    // layout is unchanged (row pitch 49): three rows of a 3-wide grid for seven cards (3+3+1) end at panelY+173, well
    // inside the y=206 interior bottom. The lone seventh card sits in column 0 of row 2, left-aligned with every other
    // row's first card, which is the deliberate, even placement asked for.
    private static final int GRID_COLUMNS = 3;
    private static final int CARD_START_X = 13;
    private static final int CARD_PITCH_X = 41;
    private static final int CARD_PITCH_Y = 49;
    private static final int CARD_W = 34;
    private static final int CARD_H = 44;

    // Head-frame render scale for the option cards. The old full-body fit used ~26 for this 44px card; a ~0.5-block head
    // reads at roughly half the card height when scaled by ~48 (48 * 0.5 * 0.9375 ~= 22px), so 48 is the base. Oversized
    // races are still shrunk from this by getAdjustedModelScale, and the card scissor clips the enlarged body.
    private static final int HEAD_FRAME_SCALE = 48;

    // Right-side name/description text. No boxes: the name and description are drawn as plain bordered text, exactly the
    // way DMZ's own RaceSelectionScreen.renderRaceInfo lays them out (wrapped to a 130px column, line pitch 12, name above
    // the first description line). Same width and line pitch DMZ uses, only kept on the right side of this screen.
    private static final int INFO_PANEL_WIDTH = 130;
    private static final int INFO_PANEL_MARGIN = 10;
    private static final int DESC_LINE_HEIGHT = 12;

    // Card colours, copied from DMZ's renderPreviewGrid.
    private static final int CARD_BODY = -1441722095;
    private static final int BORDER_SELECTED = -1519455;
    private static final int BORDER_UNSELECTED = -14013910;

    // Text colours copied verbatim from DMZ's RaceSelectionScreen.renderRaceInfo: the race NAME is DMZ's soft mint
    // (-8585770 == 0xFF7CFDD6) and the wrapped DESCRIPTION is DMZ's soft grey (-3355444 == 0xFFCCCCCC). The panel heading
    // and counter stay DMZ's soft red / white as before.
    private static final int RACE_NAME_COLOR = -8585770;
    private static final int RACE_DESC_COLOR = -3355444;
    private static final int HEADING_COLOR = 0xFF9B9B;
    private static final int WHITE = 0xFFFFFF;

    private final Screen parent;
    private final Character character;
    private final String parentRaceId;
    private final List<String> options;
    private final Consumer<String> onConfirm;

    private int selected = 0;
    // Previews face the camera, matching DMZ's front-facing customization models.
    private final float previewRotation = 180.0f;

    // Animated panorama background, one for one with DMZ's RaceSelectionScreen / CharacterCustomizationScreen. The
    // cache keys on the race id and memoizes the existence probe, so the "does <key>_panorama_0.png exist" resource
    // lookup runs ONCE per id (never per frame) and the roshi fallback never spams FileNotFoundException. The custom
    // shadow_dragon* races ship no panorama, so this screen resolves to the roshi cubemap in practice.
    private final Map<String, PanoramaRenderer> panoramaCache = new HashMap<>();
    private PanoramaRenderer panorama;

    /**
     * @param parent       the screen to return to on Back (the DMZ race carousel)
     * @param character    the local player's capability character (mutated + restored for previews only)
     * @param parentRaceId the chosen parent race id (index 0 of the option list)
     * @param options      parent id plus every visible sub-race id, in order (from {@link SubRaceClient#visibleOptions})
     * @param onConfirm    invoked with the chosen id when the player presses Next
     */
    public SubRaceSelectionScreen(Screen parent, Character character, String parentRaceId, List<String> options,
            Consumer<String> onConfirm)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.subrace.title"));
        this.parent = parent;
        this.character = character;
        this.parentRaceId = parentRaceId == null ? "" : parentRaceId.toLowerCase(Locale.ROOT);
        this.options = options;
        this.onConfirm = onConfirm;
    }

    @Override
    protected void init()
    {
        super.init();
        // Resolve the background panorama for the race being customised, keyed on parentRaceId (the shadow_dragon*
        // races have no panorama, so this resolves to roshi via getPanorama's fallback). Done here, not per frame.
        this.panorama = getPanorama(parentRaceId);
        // Nav buttons, placed exactly like DMZ's customization nav: bottom-right pair, Back left of Next.
        int buttonY = getUiHeight() - 28;
        int rightX = getUiWidth() - 86;
        int leftX = rightX - 78;
        TexturedTextButton back = new TexturedTextButton.Builder()
                .position(leftX, buttonY).size(74, 20)
                .texture(BUTTONS_TEXTURE).textureCoords(0, 28, 0, 48).textureSize(74, 20)
                .message(tr("gui.dmz_ragnarok.core.subrace.back"))
                .onPress(b -> back())
                .build();
        TexturedTextButton next = new TexturedTextButton.Builder()
                .position(rightX, buttonY).size(74, 20)
                .texture(BUTTONS_TEXTURE).textureCoords(0, 28, 0, 48).textureSize(74, 20)
                .message(tr("gui.dmz_ragnarok.core.subrace.next"))
                .onPress(b -> confirm())
                .build();
        addRenderableWidget(back);
        addRenderableWidget(next);
    }

    private void confirm()
    {
        if (selected < 0 || selected >= options.size())
            return;
        String chosen = options.get(selected);
        if (onConfirm != null)
            onConfirm.accept(chosen);
    }

    private void back()
    {
        Minecraft.getInstance().setScreen(parent);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick)
    {
        // DMZ's own creation screens draw the animated panorama full-screen in RAW screen space, BEFORE any UI scale
        // is pushed (see RaceSelectionScreen.renderPanorama / CharacterCustomizationScreen.renderPanorama). Match that
        // instead of vanilla's dimmed renderBackground, so this step looks like part of DMZ's flow. The panorama frames
        // animate on their own via PanoramaRenderer, so this is not a single static frame.
        renderPanorama(graphics, partialTick);

        // ScaledScreen scales the whole screen by uiScale but converts incoming mouse events to UI space (toUiX/toUiY)
        // ONLY for click/drag/scroll/move, not for render. AbstractWidget.render still hit-tests hover by comparing the
        // raw mouseX/mouseY it is handed against widget rects that were laid out in UI space, so hover registers at the
        // wrong spot unless we convert first. Convert here and pass UI-space coords into super.render, matching how DMZ
        // itself calls super.render (uiMouseX/uiMouseY). This also feeds any tooltip logic in that render path the same
        // converted coords, so tooltips follow the cursor.
        int uiMouseX = (int) Math.round(toUiX(mouseX));
        int uiMouseY = (int) Math.round(toUiY(mouseY));

        beginUiScale(graphics);
        try
        {
            renderLeftPanel(graphics);
            renderBigPreview(graphics);
            renderInfoPanels(graphics);
            renderStepCounter(graphics);
        }
        catch (Throwable ignored)
        {
            // Never let a render fault break the screen; the buttons below still draw and work.
        }
        endUiScale(graphics);
        // Buttons are vanilla widgets added in UI space; render them under the same UI scale as everything else, with
        // the mouse converted to UI space above so their hover highlight lines up with where they are drawn.
        beginUiScale(graphics);
        super.render(graphics, uiMouseX, uiMouseY, partialTick);
        endUiScale(graphics);
    }

    // Draw the resolved panorama full-screen, one for one with DMZ's renderPanorama: a null panorama (resource manager
    // unavailable at init) simply draws nothing, leaving the screen readable rather than crashing.
    private void renderPanorama(GuiGraphics graphics, float partialTick)
    {
        if (panorama != null)
            panorama.render(partialTick, 1.0f);
    }

    // Reimplements DMZ's private getPanorama (RaceSelectionScreen / CharacterCustomizationScreen, identical there):
    // probe textures/gui/background/<key>_panorama_0.png once, cache the result, and fall back to the roshi cubemap when
    // that race has no panorama. computeIfAbsent memoizes the probe so the resource lookup never runs per frame and the
    // fallback path produces no log noise. A blank key resolves straight to roshi, since no "_panorama_0" exists for it.
    private PanoramaRenderer getPanorama(String raceKey)
    {
        String key = raceKey == null ? "" : raceKey;
        return panoramaCache.computeIfAbsent(key, k ->
        {
            ResourceLocation probe = ResourceLocation.fromNamespaceAndPath(
                    "dragonminez", "textures/gui/background/" + k + "_panorama_0.png");
            boolean exists = false;
            if (Minecraft.getInstance().getResourceManager() != null)
                exists = Minecraft.getInstance().getResourceManager().getResource(probe).isPresent();
            ResourceLocation base = exists
                    ? ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/gui/background/" + k + "_panorama")
                    : ResourceLocation.fromNamespaceAndPath("dragonminez", "textures/gui/background/roshi");
            return new PanoramaRenderer(new CubeMap(base));
        });
    }

    // The green menubig panel with the option-card grid, copied from DMZ's left customization panel + renderPreviewGrid.
    private void renderLeftPanel(GuiGraphics graphics)
    {
        int panelX = 12;
        int panelY = getUiHeight() / 2 - 106;
        RenderSystem.enableBlend();
        graphics.blit(MENU_BIG, panelX, panelY, 0, 0, 141, 213);
        RenderSystem.disableBlend();

        // Panel heading in DMZ's soft red.
        MutableComponent heading = tr("gui.dmz_ragnarok.core.subrace.title");
        TextUtil.drawCenteredStringWithBorder(graphics, this.font, heading, panelX + 71, panelY + 10, HEADING_COLOR);

        int startX = panelX + CARD_START_X;
        int startY = panelY + 30;
        for (int i = 0; i < options.size(); i++)
        {
            int col = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            int x = startX + col * CARD_PITCH_X;
            int y = startY + row * CARD_PITCH_Y;
            boolean isSelected = i == selected;

            // Border first as a 1px outset, then the card body, matching DMZ's renderPreviewGrid.
            int border = isSelected ? BORDER_SELECTED : BORDER_UNSELECTED;
            graphics.fill(x - 1, y - 1, x + CARD_W + 1, y + CARD_H + 1, border);
            graphics.fill(x, y, x + CARD_W, y + CARD_H, CARD_BODY);

            // Clip the head-framed preview to the card interior so the enlarged model cannot bleed past its card
            // (scissor takes RAW screen pixels, hence toScreenCoord).
            graphics.enableScissor(toScreenCoord(x + 1), toScreenCoord(y + 1),
                    toScreenCoord(x + CARD_W - 1), toScreenCoord(y + CARD_H - 1));
            renderHeadFramedCard(graphics, options.get(i), x, y);
            graphics.disableScissor();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button)
    {
        // ScaledScreen.mouseClicked converts to UI space before calling super, so the coords here (via the super chain)
        // are already UI space for widgets. For our own card hit-test we must convert the RAW coords ourselves, since
        // this override sits ABOVE ScaledScreen's conversion.
        double uiX = toUiX(mouseX);
        double uiY = toUiY(mouseY);
        int panelX = 12;
        int panelY = getUiHeight() / 2 - 106;
        int startX = panelX + CARD_START_X;
        int startY = panelY + 30;
        for (int i = 0; i < options.size(); i++)
        {
            int col = i % GRID_COLUMNS;
            int row = i / GRID_COLUMNS;
            int x = startX + col * CARD_PITCH_X;
            int y = startY + row * CARD_PITCH_Y;
            if (uiX >= x - 1 && uiX <= x + CARD_W + 1 && uiY >= y - 1 && uiY <= y + CARD_H + 1)
            {
                selected = i;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    // The big centre-right preview: the selected race, rendered large and front-facing, matching DMZ's placement.
    private void renderBigPreview(GuiGraphics graphics)
    {
        String id = (selected >= 0 && selected < options.size()) ? options.get(selected) : parentRaceId;
        int x = getUiWidth() / 2 + 20;
        int y = getUiHeight() / 2 + 60;
        renderPreviewModelVariant(graphics, id, x, y, 75);

        // The selected race's name, centered under the big preview, in white.
        TextUtil.drawCenteredStringWithBorder(graphics, this.font, raceName(id), x, y + 8, WHITE);
    }

    // The selected sub-race's name and description, drawn as plain bordered text with NO box/background, matching
    // DMZ's RaceSelectionScreen.renderRaceInfo one for one: the bold mint name sits one line above the first grey,
    // wrapped description line, both centered on the same column and both drawn with TextUtil.drawCenteredStringWithBorder.
    // The only difference is the column: DMZ anchors its info on the left, here it stays in the right-hand region this
    // screen already used. All lookups are pure reads, so no player state is touched and nothing needs restoring.
    private void renderInfoPanels(GuiGraphics graphics)
    {
        String id = (selected >= 0 && selected < options.size()) ? options.get(selected) : parentRaceId;
        int panelX = getUiWidth() - INFO_PANEL_MARGIN - INFO_PANEL_WIDTH;
        int centerX = panelX + INFO_PANEL_WIDTH / 2;
        // Keep the text block anchored in the same vertical region the old boxes occupied (top of the right column).
        int startY = getUiHeight() / 2 - 94;

        // Push the text forward so it never disappears behind the big preview model, exactly as DMZ does in renderRaceInfo.
        graphics.pose().pushPose();
        graphics.pose().translate(0.0, 0.0, 400.0);

        // Bold mint name, one line above the description. DMZ bolds by prefixing the section-bold code and re-inserting it
        // after every colour code the lang value carries, so an already-coloured race name stays coloured but bold.
        String rawName = raceName(id).getString();
        String boldName = "§l" + rawName.replaceAll("(?i)(§[0-9a-fr])", "$1§l");
        TextUtil.drawCenteredStringWithBorder(graphics, this.font, txt(boldName), centerX, startY - 12, RACE_NAME_COLOR);

        // Grey wrapped description, one line per wrapped row at DMZ's 12px pitch.
        List<String> lines = raceDescriptionLines(id);
        int textY = startY;
        for (String line : lines)
        {
            TextUtil.drawCenteredStringWithBorder(graphics, this.font, txt(line), centerX, textY, RACE_DESC_COLOR);
            textY += DESC_LINE_HEIGHT;
        }

        graphics.pose().popPose();
    }

    // The wrapped description lines for a race id, from the shipped "<key>.desc" lang key, wrapped to the info panel width
    // with DMZ's own text wrapper (same helper, width and font DMZ's renderRaceInfo uses for its race descriptions).
    // Empty when no desc key exists, so nothing is drawn rather than a raw key.
    private List<String> raceDescriptionLines(String id)
    {
        String key = "race.dragonminez." + id + ".desc";
        MutableComponent c = tr(key);
        String value = c.getString();
        if (value.equals(key))
            return java.util.Collections.emptyList();
        return TextUtil.wrap(this.font, value, INFO_PANEL_WIDTH, DMZ_FONT);
    }

    /**
     * Render one option card's preview framed on the model's head: the head is centered horizontally in the card and
     * sits in the card's top third, instead of the whole body being scaled to fit. This is the same
     * {@link #renderPreviewModelVariant} render, only with the scale and the feet anchor chosen so the head, not the
     * whole body, fills the frame. The card scissor set by the caller clips the enlarged body to the card bounds.
     *
     * <p><b>Derivation.</b> {@link InventoryScreen#renderEntityInInventory} places the entity by its FEET at the anchor
     * {@code (x, feetY)} and draws it upward, one block per {@code scale} pixels. So for a model whose vertical scaling
     * is {@code scaleY} (from {@link Character#getResolvedModelScaling}) and whose eye height is {@code eyeH} blocks, the
     * head centre lands at screen {@code feetY - eyeH * scaleY * renderScale}. Setting that equal to the target head
     * position gives the feet anchor. CALCULATED from real values: {@code eyeH} = {@code player.getEyeHeight()},
     * {@code scaleY} = the resolved model Y scaling, {@code cardTop}/{@code cardInnerH} = the card geometry. ASSUMED
     * constants: the eye height is used as the head-centre proxy (a humanoid's eyes sit near the vertical middle of the
     * head), the head is targeted at one third down the card, and {@code HEAD_FRAME_SCALE} is picked so a ~0.5-block head
     * reads at roughly half the 44px card height; oversized races are still shrunk by {@link #getAdjustedModelScale}.
     */
    private void renderHeadFramedCard(GuiGraphics graphics, String raceId, int cardX, int cardY)
    {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null)
            return;

        int cardTop = cardY + 1;
        int cardInnerH = CARD_H - 2;
        int centerX = cardX + CARD_W / 2;

        // Vertical scale factor for this race's model, read live (default 0.9375 when unresolved). The X anchor already
        // sits on the model centreline, so only the Y factor matters for placing the head.
        float scaleY = 0.9375f;
        try
        {
            Float[] resolved = character.getResolvedModelScaling();
            if (resolved != null && resolved.length >= 2 && resolved[1] != null)
                scaleY = resolved[1];
        }
        catch (Throwable ignored)
        {
            // Keep the default scale factor.
        }

        // Real eye height of the entity being rendered; ~1.62 for a standing player. Used as the head-centre proxy.
        float eyeH = player.getEyeHeight();

        int renderScale = getAdjustedModelScale(HEAD_FRAME_SCALE);
        // Target head centre: one third of the way down the card interior.
        int targetHeadY = cardTop + cardInnerH / 3;
        // Feet anchor derived so the head centre lands on the target: feetY = targetHeadY + eyeH * scaleY * renderScale.
        int feetY = targetHeadY + Math.round(eyeH * scaleY * renderScale);

        renderPreviewModelVariant(graphics, raceId, centerX, feetY, renderScale);
    }

    /**
     * Render the local player with {@code raceId} temporarily applied, so the preview shows that race's custom model and
     * tints. Reimplements DMZ's private {@code renderPreviewModelVariant} + {@code getAdjustedModelScale}. The original
     * race is ALWAYS restored in the {@code finally}, so an exception mid-render never leaves the player's race
     * corrupted client-side.
     */
    private void renderPreviewModelVariant(GuiGraphics graphics, String raceId, int x, int y, int scale)
    {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || character == null || raceId == null)
            return;

        String originalRace = character.getRace();
        try
        {
            character.setRace(raceId.toLowerCase(Locale.ROOT));
            int adjustedScale = getAdjustedModelScale(scale);

            Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
            Quaternionf cameraOrientation = new Quaternionf().rotateX(0.0f);
            pose.mul((Quaternionfc) cameraOrientation);

            float yBodyRotO = player.yBodyRot;
            float yRotO = player.getYRot();
            float xRotO = player.getXRot();
            float yHeadRotO = player.yHeadRotO;
            float yHeadRot = player.yHeadRot;
            player.yBodyRot = previewRotation;
            player.setYRot(previewRotation);
            player.setXRot(0.0f);
            player.yHeadRot = previewRotation;
            player.yHeadRotO = previewRotation;

            graphics.pose().pushPose();
            graphics.pose().translate(0.0, 0.0, 150.0);
            InventoryScreen.renderEntityInInventory(graphics, x, y, adjustedScale, pose, cameraOrientation, player);
            graphics.pose().popPose();

            player.yBodyRot = yBodyRotO;
            player.setYRot(yRotO);
            player.setXRot(xRotO);
            player.yHeadRotO = yHeadRotO;
            player.yHeadRot = yHeadRot;
        }
        finally
        {
            // Restore the player's race no matter what, or the client-side model stays stuck on the previewed race.
            character.setRace(originalRace);
        }
    }

    // SU reimplementation of DMZ's private getAdjustedModelScale: shrink oversized races so they fit the frame.
    private int getAdjustedModelScale(int baseScale)
    {
        try
        {
            Float[] resolved = character.getResolvedModelScaling();
            if (resolved != null && resolved.length >= 2 && resolved[0] != null && resolved[1] != null)
            {
                float currentScale = (resolved[0] + resolved[1]) / 2.0f;
                if (currentScale > 1.0f)
                    return (int) (baseScale * (0.9375f / currentScale));
            }
        }
        catch (Throwable ignored)
        {
            // Fall through to the unadjusted scale.
        }
        return baseScale;
    }

    // The step counter, bottom-right above the nav buttons. DMZ's customization shows "N/6" across its six tabs, and the
    // user's mockup shows "1/6" here, so we reuse DMZ's own denominator and show "1/6": this reads as step one of the
    // customization flow (this sub-race step, then DMZ's tabs continue as 2..6) instead of introducing a rival count.
    private void renderStepCounter(GuiGraphics graphics)
    {
        int x = getUiWidth() - 49;
        int y = getUiHeight() - 40;
        TextUtil.drawCenteredStringWithBorder(graphics, this.font, txt("1/6"), x, y, WHITE);
    }

    // The display name for a race id from the shipped lang keys, falling back to the raw id if none is present.
    private Component raceName(String id)
    {
        String key = "race.dragonminez." + id;
        MutableComponent c = tr(key);
        if (c.getString().equals(key))
            return txt(id);
        return c;
    }

    @Override
    public boolean isPauseScreen()
    {
        return false;
    }

    @Override
    public void onClose()
    {
        // Escape behaves like Back: return to the carousel without committing.
        back();
    }
}
