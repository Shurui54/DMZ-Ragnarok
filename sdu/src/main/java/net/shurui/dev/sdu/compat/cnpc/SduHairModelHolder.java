package net.shurui.dev.sdu.compat.cnpc;

/**
 * Accessor mixed into the Gecko addon's {@code EntityCustomModel} (the client render proxy) so hair
 * code/colour rides <em>the model entity that actually renders</em>. {@code EntityCustomModel.owner} is null
 * in some render paths (the editor preview), so {@code ModelDataHairMixin} copies the values off the NPC's
 * {@link SduHairHolder} display onto the model on every (re)build, and {@code SduHairLayer} reads them here.
 */
public interface SduHairModelHolder {

    String sdu$getModelHairCode();

    void sdu$setModelHairCode(String code);

    String sdu$getModelHairColor();

    void sdu$setModelHairColor(String hex);
}
