package net.shurui.dev.shuruis_raid_bosses.client.gui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.shurui.dev.shuruis_raid_bosses.item.ZStat;
import net.shurui.dev.shuruis_raid_bosses.network.RaidNet;
import net.shurui.dev.shuruis_raid_bosses.network.ZSoulInvestC2S;

import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * The Z-Soul "add" button, which takes over a DMZ stat's add-button slot once that stat is capped (see the
 * client mixin). It draws DMZ's OWN stat add-button sprite unmodified, so it reads as the same button rather
 * than as a foreign widget. It used to multiply the sprite blue to set the Z-Soul button apart from DMZ's own
 * add button, but the server resource pack now ships a redrawn {@code characterbuttons.png} that carries the
 * intended look, so a blue multiply on top would double-colour art that is already correct. Pressing it asks
 * the server to buy beyond-cap points for
 * {@link #stat} using DMZ's standard TP cost, in the same multiplier DMZ's own button currently uses
 * ({@link #multiplier}).
 *
 * <p>Its POSITION is not owned by this class. DMZ slides its stats panel and re-sets the X of every stat button
 * each frame through {@code updatePanelWidgetOffsets}; the mixin hooks that and moves these buttons with them,
 * or they would sit still while the rest of the panel animated.</p>
 */
public class ZSoulAddButton extends Button {

    /**
     * DMZ's character-screen button sheet. Its stat "+" button is a 10x10 sprite: the normal frame at (0,0)
     * is a pale plate with a BLACK "+", the hover frame at (0,10) is the same plate with a WHITE "+", so
     * DMZ's hover cue is the glyph washing out into the plate. Both frames are drawn 10x10 at the widget
     * origin inside a 14x11 widget, which is why our own size matches DMZ's exactly.
     */
    private static final ResourceLocation DMZ_BUTTONS =
            new ResourceLocation("dragonminez", "textures/gui/buttons/characterbuttons.png");
    private static final int SPRITE_U = 0;
    private static final int SPRITE_V_NORMAL = 0;
    private static final int SPRITE_V_HOVER = 10;
    private static final int SPRITE_SIZE = 10;

    /** Fallback colours, used only if DMZ's sprite sheet is missing (a pink checkerboard would be worse). */
    private static final int FALLBACK_BORDER = 0xFF0A2A5A;
    private static final int FALLBACK_FILL = 0xFF2F6FE0;
    private static final int FALLBACK_FILL_HOVER = 0xFF5193FF;

    private final ZStat stat;
    private final IntSupplier multiplier;
    private final Supplier<Component> tooltipSupplier;

    public ZSoulAddButton(int x, int y, int w, int h, ZStat stat, IntSupplier multiplier) {
        this(x, y, w, h, stat, multiplier, null);
    }

    public ZSoulAddButton(int x, int y, int w, int h, ZStat stat, IntSupplier multiplier,
                          Supplier<Component> tooltipSupplier) {
        super(x, y, w, h, Component.literal("+"), b -> { }, DEFAULT_NARRATION);
        this.stat = stat;
        this.multiplier = multiplier;
        this.tooltipSupplier = tooltipSupplier;
    }

    @Override
    public void onPress() {
        RaidNet.sendToServer(new ZSoulInvestC2S(stat, Math.max(1, multiplier.getAsInt())));
        Minecraft.getInstance().getSoundManager()
                .play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Keep the hover tooltip (the real beyond-cap TP cost) current; DMZ's own cost text reads 0 at the
        // cap because its cost math is gated on the total-stat cap, so this shows the correct Z-Soul cost instead.
        if (tooltipSupplier != null) {
            Component t = tooltipSupplier.get();
            setTooltip(t == null ? null : Tooltip.create(t));
        }
        int x = getX();
        int y = getY();
        boolean hover = isHovered();

        if (!dmzSpriteAvailable()) {
            g.fill(x, y, x + width, y + height, FALLBACK_BORDER);
            g.fill(x + 1, y + 1, x + width - 1, y + height - 1, hover ? FALLBACK_FILL_HOVER : FALLBACK_FILL);
            Font font = Minecraft.getInstance().font;
            String plus = "+";
            g.drawString(font, plus, x + (width - font.width(plus)) / 2 + 1, y + (height - 8) / 2, 0xFFFFFFFF, false);
            return;
        }

        // Same sheet, same frames, same 10x10 draw at the widget origin as DMZ's own stat button. The pack's
        // redrawn characterbuttons.png already carries the Z-Soul look, so we draw it straight with no tint.
        g.blit(DMZ_BUTTONS, x, y, SPRITE_U, hover ? SPRITE_V_HOVER : SPRITE_V_NORMAL, SPRITE_SIZE, SPRITE_SIZE);
    }

    /**
     * Whether DMZ's button sheet is actually loaded. `dragonminez` is a mandatory dependency, so this is only
     * a guard against DMZ moving the file in a future version: presence of the mod is not a guarantee that a
     * given asset path still exists, and a missing texture blits as a pink checkerboard. Deliberately NOT
     * cached, so a resource reload that brings the sheet back is picked up.
     */
    private static boolean dmzSpriteAvailable() {
        try {
            return Minecraft.getInstance().getResourceManager().getResource(DMZ_BUTTONS).isPresent();
        } catch (Throwable t) {
            return false;
        }
    }
}
