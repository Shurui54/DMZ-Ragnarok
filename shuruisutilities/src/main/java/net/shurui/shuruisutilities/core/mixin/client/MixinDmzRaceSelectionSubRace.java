package net.shurui.shuruisutilities.core.mixin.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

import com.dragonminez.client.gui.character.CharacterCustomizationScreen;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.RaceCharacterConfig;
import com.dragonminez.common.hair.HairManager;
import com.dragonminez.common.network.C2S.StatsSyncC2S;
import com.dragonminez.common.network.NetworkHandler;
import com.dragonminez.common.stats.character.Character;

import net.shurui.shuruisutilities.corrupted.RaceUnlocks;
import net.shurui.shuruisutilities.corrupted.client.RaceUnlockClient;
import net.shurui.shuruisutilities.prestige.client.RaceLockLocalClient;
import net.shurui.shuruisutilities.subrace.SubRaces;
import net.shurui.shuruisutilities.subrace.client.SubRaceClient;
import net.shurui.shuruisutilities.subrace.client.SubRaceSelectionScreen;

/**
 * CLIENT-side sub-race hook on DragonMineZ's race carousel. Two jobs:
 *
 * <ol>
 *   <li><b>Filter the carousel</b> ({@code getAvailableRaces} at RETURN): drop every registered sub-race
 *       unconditionally (sub-races are only ever reachable through the sub-race screen, including the shadow dragon
 *       {@code _Nstar} rungs, whose progressive reveal is intentional), and drop any OTHER unlock-gated race the local
 *       player has NOT earned (per the fail-closed {@link RaceUnlockClient} cache). The base {@code shadow_dragon} is the
 *       one exception: it is LEFT in the carousel even when locked and shown greyed with a padlock (see
 *       {@link #su$renderShadowDragonLock}) plus a themed tooltip, so the race is visible-but-locked rather than hidden.
 *       Prestige-locked races are also LEFT in place: sdu already greys those with a padlock, and that behaviour stays.</li>
 *   <li><b>Open the sub-race screen</b> ({@code selectRace} at HEAD): when the chosen parent has two or more visible
 *       options (itself plus one or more unlocked sub-races), open {@link SubRaceSelectionScreen} and cancel DMZ's
 *       confirm. Otherwise do nothing and let DMZ proceed to customization.</li>
 * </ol>
 *
 * <p><b>Co-existence with sdu.</b> sdu injects the SAME {@code selectRace} HEAD as a cancellable callback for its
 * prestige lock. Mixin runs all HEAD callbacks and the order between two mods is undefined, so this callback first
 * bails on {@code ci.isCancelled()} (respecting an sdu cancel), AND independently refuses to open the sub-race screen
 * for a prestige-locked race, so a locked race can never reach the sub-race screen regardless of injector order. The
 * server-side {@code MixinDmzCreateCharacterRaceGate} is the real enforcement; this is UX only.
 *
 * <p><b>Commit path (no re-entry).</b> The old build carried the chosen sub-race back into DMZ's flow by setting a
 * one-shot flag, forcing the carousel index to a synthetic single-element list, and re-invoking {@code selectRace} so
 * DMZ's own body would commit it. That FAILED and always committed {@code human}: the synthetic list did not take
 * effect during the re-entrant call, so DMZ's {@code selectRace} read the NORMAL sorted race list and ran
 * {@code list.get(0)}, and index 0 of that list is DMZ's first {@code DEFAULT_RACES} entry, {@code human} (and
 * {@code Character.setRace} coerces a null/absent value to {@code human} too). The re-entry was fragile for a second
 * reason: by the time the sub-race screen calls back, the {@code RaceSelectionScreen} is no longer the active screen,
 * and DMZ's {@code selectRace} finishes by scheduling a tick-driven close transition to the customization screen, but a
 * non-active screen never ticks, so that transition would never commit either.
 *
 * <p>This build commits DIRECTLY instead, reproducing the tail of DMZ's {@code selectRace} with no dependence on the
 * carousel list or the transition system: {@link Character#setRace(String)} to the chosen id, then DMZ's own
 * {@code applyRaceDefaults} behaviour (reimplemented in {@link #su$applyRaceDefaults(String)} because DMZ's is private)
 * so the customization screen opens with the correct default colours/hair, then a {@link StatsSyncC2S} carrying the
 * chosen race, then {@code mc.setScreen(new CharacterCustomizationScreen(raceScreen, character))} directly. The
 * {@code character} the {@code RaceSelectionScreen} holds is the LOCAL PLAYER's capability character (DMZ builds the
 * screen with {@code StatsData.getCharacter()}), so mutating it is exactly what DMZ's own confirm does.
 *
 * <p><b>Shadow discipline.</b> Only members declared on {@code RaceSelectionScreen} itself are shadowed
 * ({@code selectedRaceIndex}, {@code character}, {@code getAvailableRaces}). Nothing on the {@code ScaledScreen}
 * superclass ({@code getUiWidth}, {@code toUiX}, {@code beginUiScale}, {@code tr}, ...) is shadowed: doing so against a
 * {@code targets=} mixin is a documented hard load-time failure. The sub-race screen EXTENDS {@code ScaledScreen} as
 * ordinary Java (DMZ is a compile dependency), which is safe and unrelated to that failure.
 *
 * <p>Conventions: {@code remap = false} (DMZ official-mapped by-name members), {@code require = 0} (a DMZ rename
 * degrades to vanilla behaviour), and every body wrapped in {@code try/catch(Throwable)} so a fault falls through
 * rather than breaking the screen.
 */
@Mixin(targets = "com.dragonminez.client.gui.character.RaceSelectionScreen", remap = false)
public abstract class MixinDmzRaceSelectionSubRace
{
    @Shadow
    private int selectedRaceIndex;

    @Shadow
    private Character character;

    @Shadow
    private List<String> getAvailableRaces()
    {
        throw new AssertionError();
    }

    /**
     * Lang key for the shadow_dragon lock hint, derived by {@code MessageKeys.keyFor} from the exact English template
     * "Defile the dragonballs to awaken this form of PURE MALICE!" (CRC32 suffix 6e800314). Hard-coded here rather than
     * recomputed so the render tooltip resolves the identical entry the server-side commit gate emits; it lives in both
     * en_us.json and es_es.json.
     */
    @Unique
    private static final String SHADOW_DRAGON_LOCK_KEY =
            "message.dmz_ragnarok.core.defile_the_dragonballs_to_awaken_this_form_of_pu_6e800314";

    /**
     * Filter the carousel list: drop every sub-race and every unearned gated race. Prestige-locked races stay (sdu
     * greys them). No re-entry state is involved any more; the sub-race choice is committed directly.
     */
    @Inject(method = "getAvailableRaces", at = @At("RETURN"), cancellable = true, require = 0, remap = false)
    private void su$filterRaces(CallbackInfoReturnable<List<String>> cir)
    {
        try
        {
            List<String> original = cir.getReturnValue();
            if (original == null || original.isEmpty())
                return;
            List<String> filtered = new ArrayList<>(original.size());
            for (String raw : original)
            {
                if (raw == null)
                    continue;
                String id = raw.toLowerCase(Locale.ROOT);
                // Sub-races never appear in the top-level carousel; they are reached only via the sub-race screen. This
                // includes the shadow_dragon_Nstar rungs, which stay hidden until earned and are revealed one at a time
                // in the sub-race screen; that progressive reveal is intentional and is NOT changed here.
                if (SubRaces.isSubRace(id))
                    continue;
                // The base shadow_dragon is deliberately LEFT in the carousel even when locked: it now shows as a
                // greyed, unselectable padlock (see su$renderShadowDragonLock) with a themed tooltip, so the player
                // knows the race exists and what to do to earn it. su$maybeOpenSubRaceScreen refuses to confirm it while
                // locked, and the server gate is the real enforcement. Every OTHER unlock-gated main race stays HIDDEN
                // when unearned (fail-closed cache: no sync yet => hidden), so no other secret race is exposed early.
                if (!RaceUnlocks.SHADOW_DRAGON_RACE.equals(id)
                        && RaceUnlocks.isRaceUnlockGated(id) && !RaceUnlockClient.isUnlocked(id))
                    continue;
                filtered.add(raw);
            }
            // Keep selectedRaceIndex in range after removing entries, or DMZ's list.get(index) would go out of bounds.
            if (this.selectedRaceIndex >= filtered.size())
                this.selectedRaceIndex = filtered.isEmpty() ? 0 : filtered.size() - 1;
            if (this.selectedRaceIndex < 0)
                this.selectedRaceIndex = 0;
            cir.setReturnValue(filtered);
        }
        catch (Throwable ignored)
        {
            // On any fault leave DMZ's original list untouched (no setReturnValue), so the screen still works.
        }
    }

    /**
     * Intercept a race confirm. Honour an sdu cancel first, then independently refuse a prestige-locked race, then open
     * the sub-race screen when there are two or more visible options.
     */
    @Inject(method = "selectRace", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void su$maybeOpenSubRaceScreen(CallbackInfo ci)
    {
        try
        {
            // sdu (or any other mod) may have cancelled this HEAD already (e.g. its prestige lock). Respect that and do
            // nothing: never open the sub-race screen for a race another injector blocked.
            if (ci.isCancelled())
                return;

            String centered = su$centeredRaceId();
            if (centered == null)
                return;

            // A locked base shadow_dragon is visible in the carousel but must not be selectable: cancel the confirm
            // (with the same bass cue sdu uses for a prestige lock) so it can never be committed. The server gate is the
            // real enforcement; this is the client-side guard that keeps the padlocked race unpickable.
            if (su$isShadowDragonLocked(centered))
            {
                Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_BASS.get(), 0.7F));
                ci.cancel();
                return;
            }

            // Independent prestige check: even if sdu's injector ran AFTER us (so ci is not yet cancelled), never open
            // the sub-race screen for a prestige-locked race. Leave the confirm alone so sdu's own cancel + padlock UX
            // handles it; the server gate is the real enforcement.
            if (RaceLockLocalClient.isLocked(centered))
                return;

            // Count-based open condition: open only when there are 2+ visible options (parent + one or more unlocked
            // sub-races). A parent with no sub-races, or sub-races but none unlocked, gives a one-option list and must
            // fall straight through to DMZ's customization flow with no sub-race step.
            if (!SubRaceClient.shouldOpenScreen(centered))
                return;

            List<String> options = SubRaceClient.visibleOptions(centered);
            Minecraft mc = Minecraft.getInstance();
            Screen raceScreen = mc.screen;
            SubRaceSelectionScreen screen = new SubRaceSelectionScreen(
                    raceScreen, this.character, centered, options, chosen -> su$commitSubRace(raceScreen, chosen));
            mc.setScreen(screen);
            // Cancel DMZ's confirm: the sub-race screen now owns the decision. It commits directly on Next.
            ci.cancel();
        }
        catch (Throwable ignored)
        {
            // On any fault, do not cancel: fall through to DMZ's normal confirm.
        }
    }

    /**
     * Locked overlay for the base {@code shadow_dragon} when it is the centered race and the local player has not earned
     * it. Follows sdu's established prestige-lock visual pattern byte for byte (translucent grey veil + vector padlock,
     * drawn in raw {@code GuiGraphics} space, tooltip hover-tested against the same raw-space box), but is driven by SU's
     * own {@link RaceUnlockClient} rather than sdu's prestige cache and shows the themed PURE MALICE hint instead of a
     * prestige requirement. sdu is an optional dependency, so its overlay cannot be reused for this unlock-gated race;
     * this is a minimal reimplementation of the same look, entirely inside SU, referencing no sdu type.
     *
     * <p>{@code remap = true}: the public MC {@code render(GuiGraphics,int,int,float)} resolves against the Mojmapped
     * signature (the class-level {@code remap = false} only covers DMZ's own by-name members), matching how sdu injects
     * the same method. {@code require = 0} and a full {@code try/catch(Throwable)} keep a DMZ-internal mismatch from
     * breaking the screen.
     */
    @Inject(method = "render", at = @At("TAIL"), require = 0, remap = true)
    private void su$renderShadowDragonLock(GuiGraphics graphics, int mouseX, int mouseY, float partialTick, CallbackInfo ci)
    {
        try
        {
            if (!su$isShadowDragonLocked(su$centeredRaceId()))
                return;

            int[] box = su$lockBox();
            int left = box[0];
            int top = box[1];
            int right = box[2];
            int bottom = box[3];

            // Grey veil + padlock over the centered model, lifted above it so it is not painted over.
            graphics.pose().pushPose();
            graphics.pose().translate(0.0D, 0.0D, 350.0D);
            graphics.fill(left, top, right, bottom, 0x99101010);
            su$drawPadlock(graphics, (left + right) / 2, (top + bottom) / 2);
            graphics.pose().popPose();

            // Themed hint on hover. Resolves the SAME lang key the server-side commit gate emits (see
            // MixinDmzCreateCharacterRaceGate and the message.shuruisutilities.defile_* entry in the lang files), so a
            // Spanish client reads the Spanish taunt here too; the English text is the fallback when the key is absent.
            // Styled red to match sdu's prestige tooltip look.
            if (mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom)
            {
                Component hint = Component.translatableWithFallback(SHADOW_DRAGON_LOCK_KEY,
                        "Defile the dragonballs to awaken this form of PURE MALICE!").withStyle(ChatFormatting.RED);
                graphics.renderComponentTooltip(Minecraft.getInstance().font, List.of(hint), mouseX, mouseY);
            }
        }
        catch (Throwable ignored)
        {
            // Any DMZ-internal mismatch: skip the overlay, never break the screen.
        }
    }

    /**
     * True when {@code id} is the base {@code shadow_dragon} and the local player has NOT earned it (fail-closed
     * {@link RaceUnlockClient}: no sync yet reads as locked). This is the single predicate the carousel lock overlay and
     * the {@code selectRace} refusal share, so the padlock and the unpickable state can never disagree.
     */
    @Unique
    private boolean su$isShadowDragonLocked(String id)
    {
        return RaceUnlocks.SHADOW_DRAGON_RACE.equals(id) && !RaceUnlockClient.isUnlocked(id);
    }

    /**
     * Fixed-size box around the horizontal centre, upper-middle where the race model sits (GUI-scaled space), returned
     * as {@code {left, top, right, bottom}}. Same geometry sdu uses so the padlock lands over the model without needing
     * DMZ's private {@code ScaledScreen} scale helpers (which cannot be shadowed against a {@code targets=} mixin).
     */
    @Unique
    private int[] su$lockBox()
    {
        int w = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int h = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int centerX = w / 2;
        int centerY = h / 2 - 20; // upper-middle, over the model
        int halfW = 50;
        int top = centerY - 70;
        int bottom = centerY + 40;
        return new int[] { centerX - halfW, top, centerX + halfW, bottom };
    }

    /**
     * Small vector padlock at {@code (cx, cy)} from filled rects (no texture): light-grey body with a keyhole and an
     * inverted-U shackle above it. Same shape sdu draws, kept here so the two locked-race styles match.
     */
    @Unique
    private void su$drawPadlock(GuiGraphics graphics, int cx, int cy)
    {
        int bodyColor = 0xFFDDDDDD;
        int shackleColor = 0xFFBBBBBB;
        int bw = 10;
        int bh = 8;
        int bx = cx - bw / 2;
        int by = cy - 1;
        graphics.fill(bx, by, bx + bw, by + bh, bodyColor);
        graphics.fill(cx - 1, by + 2, cx + 1, by + 6, 0xFF303030);
        int shTop = by - 6;
        graphics.fill(cx - 4, shTop, cx - 2, by, shackleColor);
        graphics.fill(cx + 2, shTop, cx + 4, by, shackleColor);
        graphics.fill(cx - 4, shTop, cx + 4, shTop + 2, shackleColor);
    }

    /**
     * Confirm callback from the sub-race screen. Commits the chosen id directly, reproducing the tail of DMZ's
     * {@code selectRace} (race + defaults + stats sync + transition to customization) with no re-entry and no
     * dependence on the carousel list. See the class note for why the old re-entry path committed {@code human}.
     *
     * @param raceScreen the {@code RaceSelectionScreen} to hand to the customization screen as its "previous" screen
     * @param chosenId   the chosen race id (the parent itself, or one of its unlocked sub-races)
     */
    @Unique
    private void su$commitSubRace(Screen raceScreen, String chosenId)
    {
        try
        {
            if (chosenId == null || this.character == null)
                return;
            String id = chosenId.toLowerCase(Locale.ROOT);
            this.character.setRace(id);
            su$applyRaceDefaults(id);
            NetworkHandler.sendToServer(new StatsSyncC2S(this.character));
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new CharacterCustomizationScreen(raceScreen, this.character));
        }
        catch (Throwable ignored)
        {
            // If the commit fails, leave the player on the sub-race screen rather than in a half-committed state.
        }
    }

    /**
     * SU reimplementation of DMZ's private {@code applyRaceDefaults}: apply the race's configured default colours, body
     * type, hair and face parts to {@code this.character}, matching DMZ 2.1.3's behaviour byte for byte so the
     * customization screen opens exactly as it would from a normal race confirm. Kept private in DMZ, so it cannot be
     * called; copied here.
     */
    @Unique
    private void su$applyRaceDefaults(String race)
    {
        this.character.setRace(race);
        RaceCharacterConfig config = ConfigManager.getRaceCharacter(race);
        if (config == null)
            return;
        this.character.setBodyColor(config.getDefaultBodyColor());
        this.character.setBodyColor2(config.getDefaultBodyColor2());
        this.character.setBodyColor3(config.getDefaultBodyColor3());
        this.character.setHairColor(config.getDefaultHairColor());
        this.character.setEye1Color(config.getDefaultEye1Color());
        this.character.setEye2Color(config.getDefaultEye2Color());
        this.character.setAuraColor(config.getDefaultAuraColor());
        this.character.setBodyType(config.getDefaultBodyType());
        this.character.setHairId(config.getDefaultHairType());
        if (HairManager.canUseHair(this.character))
        {
            this.character.setActiveHeadBone("hair");
            this.character.setRenderHairBase(true);
        }
        else if (config.getHeadBones() != null && config.getHeadBones().length > 0)
        {
            String firstExtraBone = "";
            if (this.character.areExtraHeadBonesEnabled())
            {
                for (String bone : config.getHeadBones())
                {
                    if (bone == null || bone.isEmpty() || bone.equals("hair"))
                        continue;
                    firstExtraBone = bone;
                    break;
                }
            }
            this.character.setActiveHeadBone(firstExtraBone);
        }
        else
        {
            this.character.setActiveHeadBone("");
        }
        this.character.setEyesType(config.getDefaultEyesType());
        this.character.setNoseType(config.getDefaultNoseType());
        this.character.setMouthType(config.getDefaultMouthType());
        this.character.setTattooType(config.getDefaultTattooType());
    }

    // The lowercase id of the centered carousel race, or null when the carousel is empty or the index is out of range.
    @Unique
    private String su$centeredRaceId()
    {
        try
        {
            List<String> races = this.getAvailableRaces();
            if (races == null || races.isEmpty())
                return null;
            int idx = this.selectedRaceIndex;
            if (idx < 0 || idx >= races.size())
                return null;
            String id = races.get(idx);
            return id == null ? null : id.toLowerCase(Locale.ROOT);
        }
        catch (Throwable t)
        {
            return null;
        }
    }
}
