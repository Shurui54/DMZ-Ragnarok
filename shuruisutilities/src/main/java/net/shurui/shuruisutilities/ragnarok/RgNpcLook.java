package net.shurui.shuruisutilities.ragnarok;

import net.minecraft.world.entity.Entity;

/**
 * Give a freshly spawned entity one of the ragnarok NPC looks, if it can wear one.
 *
 * <p>All 386 ragnarok NPCs, the ninjin characters among them, share ONE registered entity type
 * ({@code dmz_ragnarok:rgnpc}): which character an instance is comes from a field on the entity, not from a type
 * of its own. Any editor that picks a host or a boss from the entity-type registry therefore reaches exactly one
 * of them and gets whichever model is the default, which is what made the whole cast look unavailable in those
 * pickers. Handing the chosen model id here afterwards is what turns that single type back into 386 choices.
 *
 * <p>Deliberately forgiving in both directions. A blank id means "leave the entity as it is", so an entity type
 * that is not an rgnpc, or a definition saved before a model could be chosen, is untouched. An id that no longer
 * names an installed model is dropped rather than applied, because {@link RgNpcModels#resolveId} already knows
 * how to map a pre-rename id onto its current one and anything it cannot map would only produce a missing model.
 */
public final class RgNpcLook
{
    private RgNpcLook() {}

    /**
     * Apply {@code modelId} to {@code entity} when it is an rgnpc and the id names a real model.
     *
     * @return true when a model was actually applied
     */
    public static boolean apply(Entity entity, String modelId)
    {
        if (entity == null || modelId == null || modelId.isBlank())
            return false;
        try
        {
            String resolved = RgNpcModels.resolveId(RgNpcModels.sanitize(modelId));
            if (resolved == null)
                return false;
            // BOTH ragnarok entities wear a character, and which one a config names is the difference between a
            // statue and an opponent. The fighter is what every picker produces now; the display NPC is still
            // handled because older configs name it and must keep their character.
            if (entity instanceof RgNpcFighterEntity fighter)
            {
                fighter.setModelId(resolved);
                return true;
            }
            if (!(entity instanceof RgNpcEntity npc))
                return false;
            npc.setModelId(resolved);
            // The texture follows the model unless somebody has deliberately chosen otherwise. Setting it here
            // matters because a model's default skin is not implied by the model: leaving the previous texture on
            // a newly chosen model is how you get one character wearing another's face.
            npc.setTextureId(RgNpcModels.defaultTexture(resolved));
            return true;
        }
        catch (Throwable t)
        {
            // A look is cosmetic; failing to set one must never stop the host or boss spawning.
            return false;
        }
    }
}
