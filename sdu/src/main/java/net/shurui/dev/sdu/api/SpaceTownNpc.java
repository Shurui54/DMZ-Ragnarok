package net.shurui.dev.sdu.api;

/**
 * Marker for the Space module's passive planet-town service NPCs (the town citizen and the saiyan trader), so core
 * combat code can spot "a standing NPC that exists to be talked to, not fought" without naming the Space entity
 * classes.
 *
 * <p>This lives in core (sdu), which every module can read from. The Space entity classes implement it; the combat
 * bystander test checks the interface. When Space is absent no entity implements it, so the test simply never
 * matches, which is exactly right (there are no town NPCs to protect). This keeps the combat to space edge a soft,
 * core-mediated marker rather than a direct dependency, and lets Space move to a separate jar later without touching
 * the combat layer.
 */
public interface SpaceTownNpc
{
}
