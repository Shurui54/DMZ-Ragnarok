package net.shurui.shuruisutilities.compat.dmz;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;

/**
 * Guard entry point for spawning DMZ's own ki visual entities. Holds no DMZ imports; {@link DmzVisualsImpl} is the
 * sole class naming them (the optional-dependency pattern).
 *
 * <p>Prefer these over vanilla particles for anything a move draws. DMZ's ki visuals are modelled and animated as a
 * matched set, so an attack built out of them reads as a DMZ attack; one built out of {@code CLOUD} and
 * {@code DUST} does not, whatever shape the particles are arranged into. They also carry their own cast time, which
 * is where a move's charge-up wind-up comes from.
 *
 * <p>Every call returns false rather than throwing when DMZ is absent or its internals have moved, so a missing
 * visual never takes the move down with it.
 */
public final class DmzVisuals
{
    private DmzVisuals() {}

    private static boolean available()
    {
        return ModList.get().isLoaded("dragonminez");
    }

    /** A cosmetic burst with no damage and no owner. */
    public static boolean explosionVisual(ServerLevel level, Vec3 at, int colorMain, int colorBorder,
                                          int colorOutline, float size)
    {
        return available() && DmzVisualsImpl.explosionVisual(level, at, colorMain, colorBorder, colorOutline, size);
    }

    /** DMZ's hurricane, for Oceanus's Mighty Hurricane Fury. */
    public static boolean hurricane(LivingEntity owner, ServerLevel level, Vec3 at, float damage, float speed,
                                    int castTime)
    {
        return available() && DmzVisualsImpl.hurricane(owner, level, at, damage, speed, castTime);
    }

    /** One small ki blast along a direction. Used for Eis's scatter. */
    public static boolean kiBlast(LivingEntity owner, ServerLevel level, Vec3 from, Vec3 direction, float damage,
                                  float speed, int color, int colorBorder, int colorOutline, float size)
    {
        return available() && DmzVisualsImpl.kiBlast(owner, level, from, direction, damage, speed,
                color, colorBorder, colorOutline, size);
    }

    /**
     * A big translucent ball around a point, sized to the RADIUS the move actually covers: the body of every area
     * move's detonation.
     *
     * <h2>Transparency is not ours to set, and it never was</h2>
     * This used to pack an alpha into the top byte of each colour on the theory that DMZ might read it. It does not:
     * {@code ColorUtils.rgbIntToFloat} masks every channel out of the low three bytes and throws the top byte away.
     * Alpha is fixed per renderer instead - 0.45, fading out, for the burst this now draws - so a colour is only ever
     * a colour here.
     */
    public static boolean aoeBall(ServerLevel level, Vec3 at, double radius,
                                  int colorMain, int colorBorder, int colorOutline)
    {
        return available() && DmzVisualsImpl.aoeBall(level, at, radius, colorMain, colorBorder, colorOutline);
    }
}
