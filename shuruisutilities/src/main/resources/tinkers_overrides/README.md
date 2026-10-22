# Tinkers' Construct overrides

Not a folder Minecraft finds on its own. `TinkersOverridePack` hands it to the game as a datapack at
`Pack.Position.TOP`, which is the only way a mod's copy of another mod's file reliably wins: every mod's `data/` is
folded into ONE pack, and inside it the tie is broken by mod load order, which for `dmz_ragnarok` against
`tconstruct` goes the wrong way.

Nothing here is commented inline on purpose. Mantle's loaders are strict about unknown keys, so a stray `_comment`
in a modifier file is a load error rather than a note.

Tinkers is an OPTIONAL dependency (`mandatory = false`, `ordering = "AFTER"` in mods.toml). With no `tconstruct`
installed, `TinkersOverridePack` returns early and none of this is added, and the two compat mixins never resolve a
target. Nothing in this folder is read on a server without Tinkers.

| File | Why |
|---|---|
| `tinkering/modifiers/melee_protection.json` | Drops the `tconstruct:protection` module. Tinkers takes its percentage off the final damage number, outside vanilla's armour maths and so outside every change DMZ makes to it: cobalt armour was cutting melee damage no matter what the two fighters' stats said. The use-speed attribute is kept so the trait still does something. |
| `tinkering/modifiers/enderclearance.json` | Every leveling value zeroed. It teleports your attacker off you, which on a melee server is a dodge nobody can play around. The module is kept rather than deleted so armour already carrying it still loads; `teleport_chances: 0` means the teleport helper never gets an attempt, from any of the four hooks. |
| `recipes/.../upgrade/necrotic.json` | Switched off with `forge:false`. Necrotic (Life Steal) is made inert by `MixinTcNecrotic`; this is so nobody pays an upgrade slot and a ghast tear for a dead modifier. |
| `recipes/.../ability/spitting.json` | Switched off with `forge:false`, for the same reason against `MixinTcSpitting`. |

Both SALVAGE recipes are deliberately untouched: a tool that already carries necrotic or spitting can still have it
stripped and the slot recovered.
