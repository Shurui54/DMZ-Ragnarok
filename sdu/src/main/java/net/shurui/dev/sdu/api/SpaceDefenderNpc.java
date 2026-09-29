package net.shurui.dev.sdu.api;

/**
 * Marker for the Space module's planet garrison defenders (the invisible planet-clash holder and the garrison
 * fighter), so core code can spot "a combatant anchored to a planet that a shove or an erase must not touch" without
 * naming the Space entity classes.
 *
 * <p>This lives in core (sdu), which every module can read from. The Space entity classes implement it; the combat
 * bystander test (no shove) and hakai's protected test (no erase) check the interface. When Space is absent no entity
 * implements it, so both tests simply never match, which is exactly right (there are no defenders to protect). This
 * keeps the combat / god to space edge a soft, core-mediated marker rather than a direct dependency, and lets Space
 * move to a separate jar later without touching either.
 */
public interface SpaceDefenderNpc
{
}
