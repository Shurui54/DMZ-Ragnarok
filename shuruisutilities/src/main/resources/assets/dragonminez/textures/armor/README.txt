Why the Ragnarok Gi armour texture is duplicated into DMZ's namespace
====================================================================

These two files are byte-identical copies of
    assets/dmz_ragnarok/textures/armor/ragnarok_gi_layer1.png
    assets/dmz_ragnarok/textures/armor/ragnarok_gi_layer2.png
and exist ONLY so the FIRST PERSON arm stops rendering pink. Do not edit them
directly: change the dmz_ragnarok copies and re-copy, or the two will drift and
the arm will stop matching the body.

The bug is in DMZ, not here. DMZ's first person hand renderer,
com.dragonminez.client.render.DMZRenderHand.renderDbzArmor, starts with a
hardcoded namespace literal "dragonminez" and only replaces it inside the
"instanceof DbzArmorItem" branch, which reads the item's getModId(). Our Gi
chestplate is a DbzArmorCapeItem, which is NOT a DbzArmorItem: it extends
ArmorItem directly and implements DbzArmorTextured, and that interface declares
only getItemId(), with no getModId(). So the first person path has no way to
learn our mod id and asks for

    dragonminez:textures/armor/ragnarok_gi_layer1.png

which does not exist, and a missing texture draws as the magenta placeholder
over the whole arm.

Third person is unaffected and always was: DMZPlayerArmorLayer goes through the
item's own getArmorTexture, which calls ArmorTextureResolver with the correct
"dmz_ragnarok" modId. That asymmetry is why the black chest fix in 1.1.292
(RagnarokGiArmoredFlag + MixinDmzPlayerArmorLayer) did not touch this: it only
made the third person layer RUN for shape changed forms, and that layer's path
was never wrong.

This shim is the low risk half of the fix. The correct fix is a client mixin on
DMZRenderHand.renderDbzArmor that supplies the right namespace for cape items,
mirroring how MixinDmzPlayerArmorLayer already patches the third person layer.
That was not done here because a mixin into a DMZ render method is an apply
phase crash risk if the signature moves, while a resource copy cannot fail at
runtime. If that mixin is ever written, DELETE these copies in the same commit.

Note this affects any addon whose chest piece is a DbzArmorCapeItem outside the
dragonminez namespace, so if another Gi style set is added it needs the same
treatment.
