package net.shurui.dev.sdu.compat.cnpc;

/**
 * Accessor mixed into Custom NPCs' {@code DataDisplay} (by {@code DataDisplayHairMixin}) so an NPC can carry a
 * DMZ hair code. Living on {@code DataDisplay} means it rides Custom NPCs' own display save/sync (the channel
 * the Gecko addon uses for model data), so it persists with the clone and reaches clients without a bespoke packet.
 *
 * <p>The hair code is DMZ's shareable {@code HairManager} code string (see {@link net.shurui.dev.sdu.compat.DmzHair}).</p>
 */
public interface SduHairHolder {

    /** The stored DMZ hair code, or empty string if none. */
    String sdu$getHairCode();

    /** Set the DMZ hair code ({@code null} treated as empty). */
    void sdu$setHairCode(String code);

    /** Optional hair colour override as a hex string ({@code "#RRGGBB"}); empty = use the code's own colours. */
    String sdu$getHairColor();

    /** Set the hair colour override ({@code null} treated as empty). */
    void sdu$setHairColor(String hex);
}
