package net.shurui.shuruisutilities.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.mojang.blaze3d.systems.RenderSystem;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.ConfirmLinkScreen;
import net.minecraft.client.gui.screens.OptionsScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.gui.screens.worldselection.SelectWorldScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import net.shurui.shuruisutilities.client.KineticBanner;
import net.minecraft.sounds.SoundEvents;

/**
 * Shurui's DMZ Ragnarok custom main menu. Replaces the vanilla title screen on the client only (installed by
 * {@link net.shurui.shuruisutilities.client.MainMenuClientEvents}). Every element's centre position AND its
 * displayed size are stored as fractions of the artist's 1920x1080 mockup, measured off that mockup, so the
 * on-screen composition reproduces the mockup exactly at any resolution or GUI scale.
 *
 * <p>Sizing note: the sliced PNGs are tight, high-resolution crops whose native pixel dimensions are larger than
 * the size the artist actually displays them at (the logo especially: it appears at ~0.83x its native size). So
 * size must NOT be derived from native pixel size; a tight crop would then inflate the layout and, as observed,
 * push the logo's bottom over the PLAY button. Instead each element carries the displayed width/height it occupies
 * in the mockup, expressed as a fraction of the 1920x1080 reference canvas. At render time those fractions are
 * multiplied by the reference canvas and by one shared composition factor tied to the window, so the whole
 * composition scales together and a tight-crop asset can never inflate the layout again. The artwork on disk is
 * never resized.</p>
 */
public class RagnarokMainMenuScreen extends Screen {

    private static final String DIR = "textures/gui/mainmenu/";

    private static ResourceLocation tex(String name) {
        return new ResourceLocation("dmz_ragnarok", DIR + name + ".png");
    }

    // The reference canvas the layout fractions were measured on: the artist's 1920x1080 (16:9) mockup, which is
    // also the authored size of background.png.
    private static final int REF_W = 1920;
    private static final int REF_H = 1080;
    private static final ResourceLocation BACKGROUND = tex("background");

    // Logo: non-interactive. Centre and displayed size are fractions of the reference canvas measured off the
    // mockup, where the logo is displayed at ~0.83x its 910x548 native size (753x453 painted). The art is the
    // two-line DMZ RAGNAROK mark; its native height changed from the older three-line mark's 557, so LOGO_HF is
    // derived from LOGO_WF and the new native aspect (910/548) rather than kept at the old measured value, which
    // would now stretch the mark vertically. Centre and width are unchanged, so the logo occupies the same band.
    private static final ResourceLocation LOGO = tex("logo");
    private static final int LOGO_TEX_W = 910;
    private static final int LOGO_TEX_H = 548;
    private static final float LOGO_CX = 0.5000F;
    private static final float LOGO_CY = 0.2745F;
    private static final float LOGO_WF = 0.3920F;
    private static final float LOGO_HF = 0.4197F;

    private static final String URL_WEBSITE = "https://shuruidev.win";
    private static final String URL_PATREON = "https://www.patreon.com/c/ShuruiDev";
    private static final String URL_DISCORD = "https://discord.gg/K7vFwWkKrF";
    /** Kinetic Hosting affiliate link. Ours, and the reason the banner below is clickable. */
    private static final String URL_KINETIC = "https://billing.kinetichosting.com/aff.php?aff=1545";


    private final List<Element> elements = new ArrayList<>();
    // Shared composition factor applied to every element (and the logo) this frame. Recomputed on init/resize.
    private float scale = 1.0F;

    public RagnarokMainMenuScreen() {
        super(Component.literal("Shurui's DMZ Ragnarok"));
    }

    @Override
    protected void init() {
        elements.clear();
        Minecraft mc = this.minecraft;

        // Args: centre fraction (fx, fy), displayed size fraction of the reference canvas (wFrac, hFrac), normal
        // texture native px, hover texture native px, the two textures, and the action.
        // PLAY opens the multiplayer server list directly (explicit requirement), not the singleplayer/play screen.
        elements.add(new Element(0.5000F, 0.5647F, 0.1682F, 0.0794F, 324, 86, 324, 86,
                tex("play"), tex("play_hover"),
                () -> mc.setScreen(new JoinMultiplayerScreen(this))));
        // SETTINGS opens the vanilla options screen, returning here on close.
        elements.add(new Element(0.5000F, 0.6542F, 0.2013F, 0.0565F, 393, 62, 393, 62,
                tex("settings"), tex("settings_hover"),
                () -> mc.setScreen(new OptionsScreen(this, mc.options))));
        // EXIT quits the game exactly like vanilla's Quit Game button.
        elements.add(new Element(0.5000F, 0.7323F, 0.0938F, 0.0552F, 181, 60, 181, 60,
                tex("exit"), tex("exit_hover"),
                mc::stop));

        // Link icons. On-screen order left to right is website, patreon, discord. The hover art is 6 px smaller
        // than the normal art (the normal state carries a wider outer glow); the hover displayed size is the normal
        // displayed size times the hover/normal native ratio, so the icon shrinks in place instead of jumping.
        elements.add(new Element(0.4253F, 0.8468F, 0.0557F, 0.0991F, 107, 107, 101, 101,
                tex("icon_website"), tex("icon_website_hover"),
                () -> ConfirmLinkScreen.confirmLinkNow(URL_WEBSITE, this, false)));
        elements.add(new Element(0.5013F, 0.8468F, 0.0557F, 0.0991F, 107, 107, 101, 101,
                tex("icon_patreon"), tex("icon_patreon_hover"),
                () -> ConfirmLinkScreen.confirmLinkNow(URL_PATREON, this, false)));
        elements.add(new Element(0.5773F, 0.8468F, 0.0557F, 0.0991F, 107, 107, 101, 101,
                tex("icon_discord"), tex("icon_discord_hover"),
                () -> ConfirmLinkScreen.confirmLinkNow(URL_DISCORD, this, false)));

    }

    // Single shared factor tied to the window: the min of the width/height ratios against the 1920x1080 reference.
    // width/height here are Minecraft's LOGICAL gui coordinates, already divided by the gui scale, so on a small
    // window at a high gui scale they can drop into the low hundreds and the factor must be free to follow them down.
    //
    // Why the raw min guarantees the whole composition fits at ANY aspect ratio, with no clamp to help it:
    // every position is a fraction of the live window while every size is a fraction of the reference times this
    // factor. When the window is wider than 16:9 the factor equals height/1080, so the vertical stack scales exactly
    // with the window height (fits identically to the mockup) and the extra width only adds horizontal room; when
    // the window is taller than 16:9 the factor equals width/1920, so the horizontal spread scales exactly with the
    // window width (fits) and the extra height only adds vertical room. Because the mockup fractions themselves fit
    // inside the reference, the scaled composition therefore fits inside the window at every aspect ratio. Any floor
    // above the true min would size elements larger than that dimension allows and reintroduce the overflow/overlap.
    //
    // No upper clamp: scaling up is still the exact-proportional min factor, so it keeps the mockup's proportions on
    // a large window and, by the same argument, can never overflow. The 0.05 floor is only a degenerate guard for a
    // zero or near-zero window size reported transiently during init; it can never bind at a real playable size.
    private float computeScale() {
        float s = Math.min((float) this.width / REF_W, (float) this.height / REF_H);
        return Math.max(s, 0.05F);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        this.scale = computeScale();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        renderCoverBackground(g);
        renderLogo(g);
        for (Element e : elements) {
            e.render(g, mouseX, mouseY, this.width, this.height, this.scale);
        }

        // Affiliate banner, top right of this menu, matching the vanilla title screen. (The world and server
        // lists put it bottom right instead, decided in KineticBannerClientEvents, because top-right there hid
        // the first list row's player count and status.)
        KineticBanner.render(g, this.width, this.height, mouseX, mouseY, KineticBanner.Anchor.TOP_RIGHT);

        RenderSystem.disableBlend();
        // no super.render: the vanilla title widgets and the dirt/blur background are intentionally not drawn.
    }


    // COVER fit: scale the 16:9 background by the LARGER of the two window ratios so it fills the window entirely
    // while keeping its aspect ratio, then centre it so the overflow is cropped evenly. Never stretched, never
    // letterboxed.
    private void renderCoverBackground(GuiGraphics g) {
        float cover = Math.max((float) this.width / REF_W, (float) this.height / REF_H);
        int drawW = Math.round(REF_W * cover);
        int drawH = Math.round(REF_H * cover);
        int x = (this.width - drawW) / 2;
        int y = (this.height - drawH) / 2;
        g.blit(BACKGROUND, x, y, drawW, drawH, 0.0F, 0.0F, REF_W, REF_H, REF_W, REF_H);
    }

    private void renderLogo(GuiGraphics g) {
        int w = Math.round(LOGO_WF * REF_W * this.scale);
        int h = Math.round(LOGO_HF * REF_H * this.scale);
        int cx = Math.round(LOGO_CX * this.width);
        int cy = Math.round(LOGO_CY * this.height);
        g.blit(LOGO, cx - w / 2, cy - h / 2, w, h, 0.0F, 0.0F, LOGO_TEX_W, LOGO_TEX_H, LOGO_TEX_W, LOGO_TEX_H);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            for (Element e : elements) {
                if (e.contains(mouseX, mouseY, this.width, this.height, this.scale)) {
                    this.minecraft.getSoundManager()
                            .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
                    e.action.run();
                    return true;
                }
            }
        }
        // Right-click anywhere on the menu is an undisplayed shortcut to the singleplayer world select. It is
        // handled here regardless of what element sits under the cursor, because every element action above is
        // bound to left-click only, so a right-click can never trigger PLAY, SETTINGS, EXIT or a link icon.
        if (button == 1) {
            this.minecraft.getSoundManager()
                    .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
            this.minecraft.setScreen(new SelectWorldScreen(this));
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * One interactive element of the menu. Positioned by the fraction of the window its centre sits at; sized by
     * the fraction of the reference canvas it occupies in the mockup, times the shared composition factor. The
     * texture's native pixel size is used only as the blit source rectangle, never to derive on-screen size. Hit
     * testing always uses the NORMAL (larger) size so the smaller hover art never opens a dead gap under the cursor.
     */
    private static final class Element {
        private final float fx, fy;
        private final float wFrac, hFrac;
        // hover displayed size relative to normal, taken from the hover/normal native ratio (icons shrink; text
        // buttons keep 1.0 because their hover art is the same size).
        private final float hoverScale;
        private final int normalTexW, normalTexH, hoverTexW, hoverTexH;
        private final ResourceLocation normal, hover;
        private final Runnable action;

        private Element(float fx, float fy, float wFrac, float hFrac,
                        int normalTexW, int normalTexH, int hoverTexW, int hoverTexH,
                        ResourceLocation normal, ResourceLocation hover, Runnable action) {
            this.fx = fx;
            this.fy = fy;
            this.wFrac = wFrac;
            this.hFrac = hFrac;
            this.normalTexW = normalTexW;
            this.normalTexH = normalTexH;
            this.hoverTexW = hoverTexW;
            this.hoverTexH = hoverTexH;
            this.hoverScale = (float) hoverTexW / normalTexW;
            this.normal = normal;
            this.hover = hover;
            this.action = action;
        }

        private int centreX(int winW) {
            return Math.round(fx * winW);
        }

        private int centreY(int winH) {
            return Math.round(fy * winH);
        }

        private int normalW(float scale) {
            return Math.round(wFrac * REF_W * scale);
        }

        private int normalH(float scale) {
            return Math.round(hFrac * REF_H * scale);
        }

        private boolean contains(double mx, double my, int winW, int winH, float scale) {
            int w = normalW(scale);
            int h = normalH(scale);
            int left = centreX(winW) - w / 2;
            int top = centreY(winH) - h / 2;
            return mx >= left && mx < left + w && my >= top && my < top + h;
        }

        private void render(GuiGraphics g, int mouseX, int mouseY, int winW, int winH, float scale) {
            boolean hovered = contains(mouseX, mouseY, winW, winH, scale);
            ResourceLocation rl = hovered ? hover : normal;
            int texW = hovered ? hoverTexW : normalTexW;
            int texH = hovered ? hoverTexH : normalTexH;
            float sizeScale = hovered ? scale * hoverScale : scale;
            int w = Math.round(wFrac * REF_W * sizeScale);
            int h = Math.round(hFrac * REF_H * sizeScale);
            int cx = centreX(winW);
            int cy = centreY(winH);
            g.blit(rl, cx - w / 2, cy - h / 2, w, h, 0.0F, 0.0F, texW, texH, texW, texH);
        }
    }
}
