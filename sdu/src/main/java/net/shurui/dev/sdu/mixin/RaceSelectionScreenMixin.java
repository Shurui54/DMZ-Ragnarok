package net.shurui.dev.sdu.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.shurui.dev.sdu.race.RaceLockClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Locale;

// Prestige-gated races (sdu half). DMZ's RaceSelectionScreen carousel shows every loaded race and lets you
// pick the centered one. SU computes which races are prestige-locked for the local player and pushes that
// into the neutral client cache RaceLockClient (SU calls apply(...) by reflection; sdu references no SU
// type). We read that cache and, when the centered race is locked:
//   selectRace (HEAD, cancellable): cancel the confirm; every confirm path (Select button, arrow keys)
//     funnels through selectRace.
//   render (TAIL): grey veil + padlock over the centered model, plus a "§cRequires Prestige N" hover tooltip.
// Locked races stay VISIBLE, just greyed and unselectable, never filtered.
//
// Only members directly on RaceSelectionScreen are shadowed/injected. GOTCHA: the old build also shadowed
// ScaledScreen superclass helpers (getUiWidth/toUiX/...); Mixin can't resolve superclass shadows against a
// targets= mixin, a hard load-time failure that took DMZ's whole client down. Gone now: the overlay centers
// on the vanilla window's GUI-scaled dims and hover-tests RAW mouse coords, no superclass member. Pixel-perfect
// alignment with DMZ's carousel scale is not attempted.
//
// DMZ official mappings, so by-name members are remap=false; the public MC render resolves under the
// class-level remap=false. Empty list / bad index / any mismatch disables the effect, never crashes the screen.
@Mixin(targets = "com.dragonminez.client.gui.character.RaceSelectionScreen", remap = false)
public abstract class RaceSelectionScreenMixin {

    @Shadow
    private int selectedRaceIndex;

    @Shadow
    private List<String> getAvailableRaces() {
        throw new AssertionError();
    }

    // lowercase id of the centered race, or null if the carousel is empty / index bad
    private String sdu$centeredRaceId() {
        try {
            List<String> races = this.getAvailableRaces();
            if (races == null || races.isEmpty()) {
                return null;
            }
            int idx = this.selectedRaceIndex;
            if (idx < 0 || idx >= races.size()) {
                return null;
            }
            String id = races.get(idx);
            return id == null ? null : id.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return null;
        }
    }

    // cache miss / any error is treated as "not locked"
    private boolean sdu$isLocked(String id) {
        if (id == null) {
            return false;
        }
        try {
            return RaceLockClient.isLocked(id);
        } catch (Throwable t) {
            return false;
        }
    }

    // fixed-size box around the horizontal center, upper-middle where the race model sits (GUI-scaled space).
    // returns {left, top, right, bottom}
    private static int[] sdu$lockBox() {
        int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int centerX = w / 2;
        int centerY = h / 2 - 20; // upper-middle, over the model
        int halfW = 50;
        int top = centerY - 70;
        int bottom = centerY + 40;
        return new int[]{centerX - halfW, top, centerX + halfW, bottom};
    }

    // blocks confirming a locked race. selectRace is the single confirm funnel, so cancelling at HEAD prevents
    // both the transition to customization and the StatsSyncC2S that would carry the locked race to the
    // server. server-side enforcement is SU's job; this is the client UX guard.
    @Inject(method = "selectRace", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void sdu$blockLockedRaceSelect(CallbackInfo ci) {
        String id = sdu$centeredRaceId();
        if (sdu$isLocked(id)) {
            Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                            net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS.get(), 0.7F));
            ci.cancel();
        }
    }

    // locked overlay for the centered race. veil + padlock on the vanilla window's GUI-scaled dims (no DMZ
    // superclass scale helpers); tooltip hover-tests RAW mouseX/mouseY against the same box, all in default
    // GuiGraphics space.
    @Inject(method = "render", at = @At("TAIL"), require = 0, remap = true)
    private void sdu$renderLockOverlay(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci) {
        try {
            String id = sdu$centeredRaceId();
            if (!sdu$isLocked(id)) {
                return;
            }

            // This overlay is the PRESTIGE lock only. A race can be in the locked set for other reasons (SU folds
            // its unlock-gated races, e.g. the ritual-gated shadow dragon, into the SAME set), and those carry no
            // prestige requirement. requiredLevel returns 0 for a missing entry, so req <= 0 means "locked, but not
            // for prestige": draw nothing here and let whoever owns that other lock draw its own overlay. Drawing a
            // padlock plus "Requires Prestige 0" for such a race was both a false claim and a second stacked padlock.
            // The selection BLOCK stays in sdu$blockLockedRaceSelect, so a locked race is still unpickable.
            int req;
            try {
                req = RaceLockClient.requiredLevel(id);
            } catch (Throwable t) {
                req = 0;
            }
            if (req <= 0) {
                return;
            }

            int[] box = sdu$lockBox();
            int left = box[0];
            int top = box[1];
            int right = box[2];
            int bottom = box[3];

            // grey veil + padlock in default GuiGraphics space, lifted above the model
            graphics.pose().pushPose();
            graphics.pose().translate(0.0D, 0.0D, 350.0D);
            graphics.fill(left, top, right, bottom, 0x99101010);
            sdu$drawPadlock(graphics, (left + right) / 2, (top + bottom) / 2);
            graphics.pose().popPose();

            // tooltip: raw-mouse bounds check against the same box; draw at raw coords
            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom) {
                graphics.renderComponentTooltip(
                        Minecraft.getInstance().font,
                        List.of(Component.literal("§cRequires Prestige " + req)),
                        mouseX, mouseY);
            }
        } catch (Throwable ignored) {
            // any DMZ-internal mismatch: skip the overlay, don't break the screen
        }
    }

    // small vector padlock at (cx, cy) from filled rects, no texture needed. light-grey body, inverted-U
    // shackle above it.
    private static void sdu$drawPadlock(GuiGraphics graphics, int cx, int cy) {
        int bodyColor = 0xFFDDDDDD;
        int shackleColor = 0xFFBBBBBB;
        // body (10x8)
        int bw = 10;
        int bh = 8;
        int bx = cx - bw / 2;
        int by = cy - 1;
        graphics.fill(bx, by, bx + bw, by + bh, bodyColor);
        // keyhole
        graphics.fill(cx - 1, by + 2, cx + 1, by + 6, 0xFF303030);
        // shackle: two verticals + top bar
        int shTop = by - 6;
        graphics.fill(cx - 4, shTop, cx - 2, by, shackleColor);
        graphics.fill(cx + 2, shTop, cx + 4, by, shackleColor);
        graphics.fill(cx - 4, shTop, cx + 4, shTop + 2, shackleColor);
    }
}
