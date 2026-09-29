package net.shurui.shuruisutilities.core.mixin.client;

import java.util.Locale;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.dragonminez.client.util.SkinGathererProvider;
import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.RaceCharacterConfig;

/**
 * Gives a custom race that renders on the vanilla player model the same customization options the stock human and
 * saiyan get, instead of one of each.
 *
 * <h2>What was actually wrong</h2>
 * Two DMZ classes disagreed about which texture set a race like {@code half_saiyan} uses, and only one of them was
 * right.
 *
 * <ul>
 *   <li>{@code DMZSkinLayer} decides what to DRAW. A race whose config carries no {@code customModel} and is not one
 *       of the six built-in races gets the face key {@code "human"}, which is the {@code humansaiyan} texture set:
 *       the same bodies, eyes, noses and mouths a saiyan has. So the renderer is perfectly happy to draw a half
 *       saiyan with eyes type 7.
 *   <li>{@code TextureCounter} decides what to OFFER, and its {@code normalizeRace} maps by name prefix:
 *       {@code frostdemon*}, {@code bioandroid*}, {@code majin*}, {@code namekian*} and {@code saiyan*} fold onto
 *       their own sets, anything else is passed through as itself. {@code half_saiyan} starts with {@code half_},
 *       not {@code saiyan}, so it was counted as its own race, went looking in
 *       {@code textures/entity/races/half_saiyan/} for a set that does not exist, and counted zero. The
 *       customization screen clamps with {@code Math.max(1, ...)}, so every list showed exactly one entry.
 * </ul>
 *
 * <p>The fix is to make the counter agree with the renderer: if a race has no {@code customModel} and is not
 * built in, it draws from the {@code humansaiyan} set, so it is counted as {@code saiyan}. That is DMZ's own rule
 * from {@code DMZSkinLayer}, not a name we hard-code, so any future vanilla-skin race is right for free. A custom
 * race that DOES declare a {@code customModel} (the shadow dragons) is untouched and keeps counting its own files.
 *
 * <h2>Where this does and does not apply</h2>
 * Client only, because this is what a screen offers: {@code TextureCounter} is {@code @OnlyIn(Dist.CLIENT)} and the
 * race configs it reads are already synced. Nothing about the saved character changes, so a character created before
 * this can simply be edited to use the options it always could have rendered.
 *
 * <p>{@code require = 0} per the standing rule: a DMZ rename degrades to the old one-option behaviour, never to a
 * broken load. The handler captures {@code normalizeRace}'s one parameter exactly (argument capture is all or
 * nothing), and the private static target is legal.
 */
@Mixin(targets = "com.dragonminez.client.util.TextureCounter", remap = false)
public abstract class MixinDmzTextureCounterRaceOptions
{
    @Inject(method = "normalizeRace", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void su$countVanillaSkinRacesAsSaiyan(String race, CallbackInfoReturnable<String> cir)
    {
        if (race == null || race.isEmpty())
        {
            return;
        }
        String lower = race.toLowerCase(Locale.ROOT);
        // Let DMZ's own six answer for themselves: their prefixes are what the rest of normalizeRace is for.
        if (SkinGathererProvider.isBuiltInRace(lower))
        {
            return;
        }
        RaceCharacterConfig config = ConfigManager.getRaceCharacter(lower);
        if (config == null)
        {
            return; // no config yet (a race folder not loaded); leave the vanilla answer alone
        }
        String customModel = config.getCustomModel();
        if (customModel == null || customModel.isEmpty())
        {
            cir.setReturnValue("saiyan");
        }
    }
}
