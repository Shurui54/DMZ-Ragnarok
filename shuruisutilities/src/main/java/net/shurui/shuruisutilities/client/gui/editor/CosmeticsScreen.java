package net.shurui.shuruisutilities.client.gui.editor;

import java.util.List;

import net.shurui.shuruisutilities.client.gui.DmzTextureButton;
import net.shurui.shuruisutilities.client.gui.EditorScreens;
import net.shurui.shuruisutilities.client.gui.preview.LivePlayerPreview;
import net.shurui.shuruisutilities.client.gui.saga.SagaBaseScreen;
import net.shurui.dev.sdu.client.gui.theme.GuiTheme;
import net.shurui.shuruisutilities.patreon.client.PatreonLinkClient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * The cosmetics front door: your character on the left, everything cosmetic you can reach on the right.
 *
 * <p>This was three stacked buttons on an otherwise empty panel and its own comment called it "the hub the other
 * cosmetics will hang off". It is now the hub: the same live race-model portrait the wardrobe uses (see
 * {@link LivePlayerPreview}), turning slowly, beside the entries. Nothing was invented to fill space and there is
 * no status banner; every entry says its own state in its own label, which is what the label was already doing
 * for the Patreon row.
 *
 * <h2>The entries</h2>
 * <ul>
 *   <li>Wardrobe: shown only when the server says the module is live here ({@code meta[4]}). A button that opens
 *       nothing is worse than no button, and the server re-checks on the open regardless.</li>
 *   <li>Form appearance: ENABLED only when the SERVER reported this player as entitled ({@code meta[2]}). A
 *       client can never enable it on its own and the handler re-checks the reward.</li>
 *   <li>Patreon: one button and nothing else. {@code meta[3]} carries the backend base URL and the button runs
 *       the whole flow client side (prove this account to Mojang, trade that proof for a personal link, open the
 *       browser). Signing in to Patreon binds THIS account automatically, so there is no code to copy. If that
 *       cannot work (no Mojang session, or the server switched the feature off) the fallbacks are
 *       {@code /patreon claim} and the website, which is where a typed code belongs: on the rare path.</li>
 * </ul>
 *
 * <p>{@code meta = [patreonConfigured, currentTierDisplayName, hasFormCosmeticReward, backendBaseUrlOrEmpty,
 * wardrobeAvailable]}, read defensively past the end so an older server produces a duller menu rather than a
 * failed open.
 */
public class CosmeticsScreen extends SagaBaseScreen
{
    private static final int UI_W = GuiTheme.SCREEN_W;
    private static final int UI_H = GuiTheme.SCREEN_H;

    // The portrait column, and the entry column beside it.
    private static final int PREVIEW_X = 8;
    private static final int PREVIEW_Y = 30;
    private static final int PREVIEW_W = 112;
    private static final int PREVIEW_H = 176;
    private static final int ENTRY_X = 128;
    private static final int ENTRY_W = 164;
    private static final int ENTRY_H = 20;
    private static final int ENTRY_PITCH = 30;

    /** A full turn of the portrait, in milliseconds. The wardrobe turns at the same rate. */
    private static final float SPIN_PERIOD_MS = 12000.0F;

    private final boolean configured;
    private final boolean linked;
    private final boolean formCosmetic;
    private final String baseUrl;
    /** meta[4], appended later: whether the wearable wardrobe is available on this server. */
    private final boolean wardrobe;
    /** meta[5..7], appended later: the mounts, animations and shop sections' availability. */
    private final boolean mounts;
    private final boolean animations;
    private final boolean shop;

    private float yaw = (float) Math.PI;
    private float pitch;
    private long lastFrameMs;
    private boolean dragging;

    public CosmeticsScreen(List<String> meta)
    {
        super(Component.translatable("gui.dmz_ragnarok.core.cosmetics.title"), UI_W, UI_H, null);
        this.configured = meta.size() > 0 && Boolean.parseBoolean(meta.get(0));
        this.linked = meta.size() > 1 && !meta.get(1).isEmpty();
        this.formCosmetic = meta.size() > 2 && Boolean.parseBoolean(meta.get(2));
        this.baseUrl = meta.size() > 3 ? meta.get(3) : "";
        // Read defensively past the end, as every index above already is: an older server sends four entries
        // and the wardrobe button simply does not appear, rather than the screen failing to open.
        this.wardrobe = meta.size() > 4 && Boolean.parseBoolean(meta.get(4));
        this.mounts = meta.size() > 5 && Boolean.parseBoolean(meta.get(5));
        this.animations = meta.size() > 6 && Boolean.parseBoolean(meta.get(6));
        this.shop = meta.size() > 7 && Boolean.parseBoolean(meta.get(7));
    }

    @Override
    protected void init()
    {
        super.init();
        Minecraft mc = Minecraft.getInstance();
        headerName = mc.player == null ? null : mc.player.getGameProfile().getName();

        rect(PREVIEW_X, PREVIEW_Y, PREVIEW_W, PREVIEW_H, 0xFF2A2A30);
        rect(PREVIEW_X + 1, PREVIEW_Y + 1, PREVIEW_W - 2, PREVIEW_H - 2, 0xFF121216);

        // Entries are placed from a running Y rather than from fixed rows, so hiding the wardrobe entry closes
        // the gap instead of leaving a hole in the middle of the column.
        int y = PREVIEW_Y + 8;

        if (wardrobe)
        {
            btn(ENTRY_X, y, ENTRY_W, ENTRY_H,
                    Component.translatable("gui.dmz_ragnarok.core.cosmetics.wardrobe_menu"),
                    () -> EditorScreens.reopen("wardrobe"));
            y += ENTRY_PITCH;
        }

        // Mounts, animations and the shop are their own sections now, each shown only when the server reports it
        // live here. The server re-checks the gate on the open, so a button that appears always opens something.
        if (mounts)
        {
            btn(ENTRY_X, y, ENTRY_W, ENTRY_H,
                    Component.translatable("gui.dmz_ragnarok.core.cosmetics.mounts_menu"),
                    () -> EditorScreens.reopen("cosmetic_mounts"));
            y += ENTRY_PITCH;
        }
        if (animations)
        {
            btn(ENTRY_X, y, ENTRY_W, ENTRY_H,
                    Component.translatable("gui.dmz_ragnarok.core.cosmetics.animations_menu"),
                    () -> EditorScreens.reopen("cosmetic_animations"));
            y += ENTRY_PITCH;
        }
        if (shop)
        {
            btn(ENTRY_X, y, ENTRY_W, ENTRY_H,
                    Component.translatable("gui.dmz_ragnarok.core.cosmetics.shop_menu"),
                    () -> EditorScreens.reopen("shop"));
            y += ENTRY_PITCH;
        }

        DmzTextureButton form = btn(ENTRY_X, y, ENTRY_W, ENTRY_H,
                Component.translatable("gui.dmz_ragnarok.core.cosmetics.form_menu"),
                // Ask the server to open the editor; it re-checks entitlement and sends the current override.
                () -> EditorScreens.reopen("formcosmetics"));
        form.active = formCosmetic;
        if (!formCosmetic)
            tooltip(ENTRY_X, y, ENTRY_W, ENTRY_H, tr("gui.dmz_ragnarok.core.cosmetics.form_not_entitled"));
        y += ENTRY_PITCH;

        // The label carries the state, so no separate status line is needed: unavailable when the server has no
        // Patreon backend configured, "Relink" once the player is already linked, otherwise "Link".
        Component patreonLabel = !configured
                ? Component.translatable("gui.dmz_ragnarok.core.cosmetics.patreon_unavailable")
                : Component.translatable(linked
                        ? "gui.dmz_ragnarok.core.cosmetics.patreon_relink"
                        : "gui.dmz_ragnarok.core.cosmetics.patreon_link");
        DmzTextureButton link = btn(ENTRY_X, y, ENTRY_W, ENTRY_H, patreonLabel, () ->
        {
            if (!baseUrl.isEmpty())
            {
                PatreonLinkClient.begin(baseUrl, this);
                return;
            }
            // No base URL: an older server, so it posts a link in chat instead. Go to the code screen rather
            // than closing to nothing, so the code that flow ends with still has somewhere to go.
            EditorScreens.act("cosmetics", "patreonlink");
            Minecraft.getInstance().setScreen(new PatreonCodeScreen());
        });
        link.active = configured;

        btn(UI_W / 2 - 45, footerY(), 90, footerBtnHeight(), Component.translatable("gui.dmz_ragnarok.core.btn.menu"),
                EditorScreens::openPlayerHub);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick)
    {
        super.render(g, mouseX, mouseY, partialTick);
        if (!LivePlayerPreview.available())
            return;
        // Real screen coordinates, after super.render has popped the scaled pose. Same idiom as the wardrobe.
        g.flush();
        advanceSpin();

        float infl = LivePlayerPreview.modelInflation();
        int boxH = PREVIEW_H - 8;
        int cxV = PREVIEW_X + PREVIEW_W / 2;
        int modelHV = (int) (boxH * 0.78F);
        int feetV = PREVIEW_Y + 4 + boxH / 2 + modelHV / 2;
        int scaleV = Math.max(4, (int) (modelHV / 1.9F / infl));

        int cxS = (int) Math.round(originX() + cxV * guiScale);
        int feetS = (int) Math.round(originY() + feetV * guiScale);
        int scaleS = Math.max(1, (int) Math.round(scaleV * guiScale));

        g.enableScissor((int) Math.round(originX() + (PREVIEW_X + 2) * guiScale),
                (int) Math.round(originY() + (PREVIEW_Y + 2) * guiScale),
                (int) Math.round(originX() + (PREVIEW_X + PREVIEW_W - 2) * guiScale),
                (int) Math.round(originY() + (PREVIEW_Y + PREVIEW_H - 2) * guiScale));
        LivePlayerPreview.render(g, cxS, feetS, scaleS, yaw, pitch);
        g.disableScissor();
    }

    /** Turn the portrait unless the player is turning it themselves. See the wardrobe's copy of this note. */
    private void advanceSpin()
    {
        long now = net.minecraft.Util.getMillis();
        long previous = lastFrameMs;
        lastFrameMs = now;
        if (dragging || previous == 0L)
            return;
        long dt = Math.max(0L, Math.min(250L, now - previous));
        yaw += (float) (dt / SPIN_PERIOD_MS * Math.PI * 2.0);
    }

    private boolean inPreview(double vx, double vy)
    {
        return vx >= PREVIEW_X && vx < PREVIEW_X + PREVIEW_W && vy >= PREVIEW_Y && vy < PREVIEW_Y + PREVIEW_H;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button)
    {
        if (super.mouseClicked(mx, my, button))
            return true;
        if (button == 0 && openDropdown == null && inPreview(toVirtualX(mx), toVirtualY(my)))
        {
            dragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dragX, double dragY)
    {
        if (dragging)
        {
            yaw += (float) (dragX * 0.016);
            pitch = Math.max(-1.2F, Math.min(1.2F, pitch + (float) (dragY * 0.016)));
            return true;
        }
        return super.mouseDragged(mx, my, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button)
    {
        if (dragging && button == 0)
        {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mx, my, button);
    }
}
