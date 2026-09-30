package net.shurui.shuruisutilities.clone;

import java.util.UUID;

/**
 * The player-copy render contract shared by every entity drawn as "a copy of a player": the DragonMineZ race body
 * appearance (repainted over the owner's vanilla skin by {@link net.shurui.shuruisutilities.client.clone.MiniCloneRacePartsLayer})
 * plus the owner's name/uuid (used to resolve the real Minecraft skin) and the render scale.
 *
 * <p>It exists so ONE renderer ({@link net.shurui.shuruisutilities.client.clone.MiniClonePlayerRenderer}) and one race
 * layer serve BOTH the core mini clone ({@link MiniCloneEntity}, 60% scale, three variants) and the Space module's
 * full-size owner-avatar planet defender, without either copying the render code. Everything here is READ on the client
 * render thread from the entity's synced fields; nothing here classloads DragonMineZ.
 */
public interface CloneAppearance
{
    /** The DMZ race id ({@code human}, {@code namekian}, a custom id like {@code shadow_dragon}, ...). */
    String getRaceName();

    /** The DMZ body type, which picks the {@code bodytype_<N>_layerK} texture set. */
    int getBodyType();

    /** Packed 0xRRGGBB primary body colour; multiplied over the race's first body layer. */
    int getBodyColor1();

    /** Packed 0xRRGGBB secondary body colour; multiplied over the race's second body layer. */
    int getBodyColor2();

    /** Packed 0xRRGGBB tertiary body colour; multiplied over the race's third body layer. */
    int getBodyColor3();

    /** Packed 0xRRGGBB hair colour; DMZ multiplies it over the race's fourth body layer. */
    int getHairColor();

    /** The owner's UUID, used by the client to resolve the real player skin. May be null. */
    UUID getOwnerProfileId();

    /** The owner's name, used by the client to resolve the real player skin. */
    String getOwnerName();

    /** The render scale (1.0 is a full-size player copy; the mini clone is 0.6). */
    float getCloneScale();

    /**
     * Whether this entity is drawn as a player copy at all (the race layer and the player-skin resolve run only then).
     * The mini clone answers true only for its PLAYER_COPY variant; a full-size avatar is always a player copy.
     */
    boolean isPlayerCopyLook();
}
