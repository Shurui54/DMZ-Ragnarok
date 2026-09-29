package net.shurui.shuruisutilities.disguise.client;

import com.dragonminez.common.hair.CustomHair;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.character.Character;

import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import net.shurui.shuruisutilities.disguise.DisguiseView;

/**
 * The in-world DISGUISE body: paints a disguised player with the TARGET's DragonMineZ appearance, called from
 * {@code DmzPlayerRenderDisguiseMixin} at the HEAD ({@link #begin}) and every RETURN ({@link #end}) of
 * {@code DMZPlayerRenderer.render}. It lives here rather than in the mixin so the stash type is an ordinary class,
 * never a nested class of a mixin.
 *
 * <h2>Why substitute the Character.</h2>
 * DragonMineZ replaces the player with a GeckoLib model whose layers (race parts, skin, hair, eyes) paint DMZ race
 * textures tinted by the player's own {@code Character} appearance fields, and {@code DMZPlayerModel.getModelResource}
 * even picks the GEO from the race and gender. So to make one player look like another we swap the appearance fields
 * the layers read, for the length of that one render, then put them back. This is exactly appearance: it does not
 * touch stats, ki or saved character data. The staff member's own form (and stack form) is blanked for the frame so
 * no transformation overlay gives them away. The Minecraft skin, which some DMZ bodies use, is swapped separately
 * ({@code MixinAbstractClientPlayerDisguiseSkin}).
 *
 * <h2>Safety.</h2>
 * The originals are stashed and restored around DMZ's single per-player render entry. If a render ever throws between
 * HEAD and RETURN the RETURN restore is skipped, so the next HEAD restores any leaked stash before creating a new
 * one; the field values only ever "leak" onto a player who is themselves disguised and about to be re-substituted, so
 * the failure mode is self-correcting rather than corrupting a normal player. Every DMZ read is guarded; a failure
 * degrades to the player's own body and never throws to the render thread.
 */
@OnlyIn(Dist.CLIENT)
public final class DisguiseRenderSwap
{
    private DisguiseRenderSwap() {}


    private static final class Stash
    {
        Character ch;
        String race;
        int bodyType;
        int eyesType;
        String b1;
        String b2;
        String b3;
        String hair;
        String e1;
        String e2;
        String activeForm;
        String activeFormGroup;
        float[] rb1;
        float[] rb2;
        float[] rb3;
        float[] rhair;
        float[] re1;
        float[] re2;
        String gender;
        int hairId;
        CustomHair hairBase;
        int nose;
        int mouth;
        int tattoo;
        boolean tail;
        String stackForm;
        String stackFormGroup;
    }

    private static final ThreadLocal<Stash> SU_STASH = new ThreadLocal<>();

    /** HEAD of the render: substitute the target's appearance when {@code player} is disguised. */
    public static void begin(AbstractClientPlayer player)
    {
        // Defensive: restore any stash that a previous throwing render left behind before we build a new one.
        restoreLeaked();
        try
        {
            if (player == null)
                return;
            DisguiseView v = DisguiseClientCache.get(player.getUUID());
            if (v == null || !v.hasDmz)
                return;
            Character ch = characterOf(player);
            if (ch == null)
                return;

            Stash s = new Stash();
            s.ch = ch;
            s.race = ch.getRace();
            s.bodyType = ch.getBodyType();
            s.eyesType = ch.getEyesType();
            s.b1 = ch.getBodyColor();
            s.b2 = ch.getBodyColor2();
            s.b3 = ch.getBodyColor3();
            s.hair = ch.getHairColor();
            s.e1 = ch.getEye1Color();
            s.e2 = ch.getEye2Color();
            s.activeForm = ch.getActiveForm();
            s.activeFormGroup = ch.getActiveFormGroup();
            s.rb1 = ch.getRgbBodyColor();
            s.rb2 = ch.getRgbBodyColor2();
            s.rb3 = ch.getRgbBodyColor3();
            s.rhair = ch.getRgbHairColor();
            s.re1 = ch.getRgbEye1Color();
            s.re2 = ch.getRgbEye2Color();
            s.gender = ch.getGender();
            s.hairId = ch.getHairId();
            s.hairBase = ch.getHairBase();
            s.nose = ch.getNoseType();
            s.mouth = ch.getMouthType();
            s.tattoo = ch.getTattooType();
            s.tail = ch.isHasSaiyanTail();
            s.stackForm = ch.getActiveStackForm();
            s.stackFormGroup = ch.getActiveStackFormGroup();
            SU_STASH.set(s);

            ch.setRace(v.race == null ? "" : v.race);
            // After setRace, which may force the gender for a race that has none.
            if (v.gender != null && !v.gender.isEmpty())
                ch.setGender(v.gender);
            ch.setBodyType(v.bodyType);
            ch.setEyesType(v.eyesType);
            ch.setNoseType(v.noseType);
            ch.setMouthType(v.mouthType);
            ch.setTattooType(v.tattooType);
            ch.setHasSaiyanTail(v.saiyanTail);
            ch.setHairId(v.hairId);
            CustomHair hair = hairOf(v);
            if (hair != null)
                ch.setHairBase(hair);
            // Render the disguise in the target's BASE appearance: drop the staff's own active form so no SSJ/etc.
            // overlay leaks through. Render-only; the real form state is stashed and restored, never touched on the
            // server.
            ch.setActiveForm("");
            ch.setActiveFormGroup("");
            ch.setActiveStackForm("");
            ch.setActiveStackFormGroup("");
            applyColor(ch, 1, v.bodyColor1);
            applyColor(ch, 2, v.bodyColor2);
            applyColor(ch, 3, v.bodyColor3);
            applyColor(ch, 4, v.hairColor);
            applyColor(ch, 5, v.eye1Color);
            applyColor(ch, 6, v.eye2Color);
        }
        catch (Throwable ignored)
        {
            restoreLeaked();
        }
    }

    /** Every RETURN of the render: put the player's own appearance back. */
    public static void end()
    {
        restoreLeaked();
    }

    private static void restoreLeaked()
    {
        Stash s = SU_STASH.get();
        if (s == null)
            return;
        SU_STASH.remove();
        try
        {
            Character ch = s.ch;
            ch.setRace(s.race);
            ch.setGender(s.gender);
            ch.setHairId(s.hairId);
            ch.setHairBase(s.hairBase);
            ch.setNoseType(s.nose);
            ch.setMouthType(s.mouth);
            ch.setTattooType(s.tattoo);
            ch.setHasSaiyanTail(s.tail);
            ch.setActiveStackForm(s.stackForm);
            ch.setActiveStackFormGroup(s.stackFormGroup);
            ch.setBodyType(s.bodyType);
            ch.setEyesType(s.eyesType);
            ch.setBodyColor(s.b1);
            ch.setBodyColor2(s.b2);
            ch.setBodyColor3(s.b3);
            ch.setHairColor(s.hair);
            ch.setEye1Color(s.e1);
            ch.setEye2Color(s.e2);
            ch.setActiveForm(s.activeForm);
            ch.setActiveFormGroup(s.activeFormGroup);
            ch.setRgbBodyColor(s.rb1);
            ch.setRgbBodyColor2(s.rb2);
            ch.setRgbBodyColor3(s.rb3);
            ch.setRgbHairColor(s.rhair);
            ch.setRgbEye1Color(s.re1);
            ch.setRgbEye2Color(s.re2);
        }
        catch (Throwable ignored)
        {
            // A restore failure cannot be helped further; the next disguise render re-substitutes anyway.
        }
    }

    /** Set both the hex string and the cached rgb triplet, since DMZ reads whichever a given layer prefers. */
    private static void applyColor(Character ch, int slot, int rgb)
    {
        String hex = String.format("%06X", rgb & 0xFFFFFF);
        float[] f = new float[] {
                ((rgb >> 16) & 0xFF) / 255.0f,
                ((rgb >> 8) & 0xFF) / 255.0f,
                (rgb & 0xFF) / 255.0f
        };
        switch (slot)
        {
            case 1 -> { ch.setBodyColor(hex); ch.setRgbBodyColor(f); }
            case 2 -> { ch.setBodyColor2(hex); ch.setRgbBodyColor2(f); }
            case 3 -> { ch.setBodyColor3(hex); ch.setRgbBodyColor3(f); }
            case 4 -> { ch.setHairColor(hex); ch.setRgbHairColor(f); }
            case 5 -> { ch.setEye1Color(hex); ch.setRgbEye1Color(f); }
            case 6 -> { ch.setEye2Color(hex); ch.setRgbEye2Color(f); }
            default -> { }
        }
    }

    /** The target's base hair style, decoded once per view with DMZ's own codec; null keeps the player's own hair. */
    private static CustomHair hairOf(DisguiseView v)
    {
        if (v.hairCache instanceof CustomHair cached)
            return cached;
        if (v.hairBase == null || v.hairBase.isEmpty())
            return null;
        CustomHair hair = new CustomHair();
        hair.load(v.hairBase);
        v.hairCache = hair;
        return hair;
    }

    private static Character characterOf(AbstractClientPlayer player)
    {
        StatsData data = player.getCapability(StatsCapability.INSTANCE).resolve().orElse(null);
        return data == null ? null : data.getCharacter();
    }
}
