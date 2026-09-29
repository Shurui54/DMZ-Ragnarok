# DMZ Ragnarok

**Custom races, forms, dragon balls, space travel and combat for [DragonMine Z](https://dragonminez.com/), all built from in-game editors.**

DMZ Ragnarok is an addon for DragonMine Z on Minecraft 1.20.1 (Forge). It adds in-game editors for races, forms, NPCs, sagas and wishes, three extra dragon ball sets with their own dragons, a movement and clash combat layer, and four optional modules: Dungeons, Raids, Tournaments and Space.

- **Download:** [CurseForge](https://www.curseforge.com/minecraft/mc-mods/dmz-ragnarok) · [Modrinth](https://modrinth.com/mod/dmz-ragnarok)
- **Discord (support, updates, bug reports):** [discord.gg/K7vFwWkKrF](https://discord.gg/K7vFwWkKrF)
- **Website:** [shuruidev.win](https://shuruidev.win)

## What is in this repository

| Directory | Gradle project | Output |
|---|---|---|
| `sdu`, `shuruisutilities` | source sets of the core | the editors, NPCs, dragon ball sets, combat layer and shared systems |
| `dmz_ragnarok_core` | `:dmz_ragnarok_core` | the core jar, `dmz_ragnarok-<version>.jar` |
| `shuruis_dmz_dungeons` | `:dmz_ragnarok_dungeons` | the Dungeons module |
| `shuruis_raid_bosses` | `:dmz_ragnarok_raids` | the Raids module |
| `shuruis_dmz_tournaments` | `:dmz_ragnarok_tournaments` | the Tournaments module |
| `dmz_ragnarok_space` | `:dmz_ragnarok_space` | the Space module |
| `dmz_ragnarok` | `:dmz_ragnarok` | the all-in-one jar (core plus all four modules) |

One build produces both install shapes: the core plus any module jars, or the single all-in-one jar. Never install the all-in-one jar beside a module jar (Forge reports a duplicate mod). Everything registers in the `dmz_ragnarok` namespace, so worlds move between the two shapes unchanged.

## Building

Requirements: Java 17 and a network connection for the first Gradle run.

1. Put these jars in `libs/` (they are not committed; download them from their own pages):
   - `dragonminez-2.1.3.jar` ([DragonMine Z](https://www.curseforge.com/minecraft/mc-mods/dragonminez))
   - `worldedit-mod-7.2.15.jar` ([WorldEdit](https://www.curseforge.com/minecraft/mc-mods/worldedit))
   - `TConstruct-1.20.1-3.11.2.166.jar` and `Mantle-1.20.1-1.11.104.jar` ([Tinkers' Construct](https://www.curseforge.com/minecraft/mc-mods/tinkers-construct), [Mantle](https://www.curseforge.com/minecraft/mc-mods/mantle))
   - `resourcefullib-forge-1.20.1-2.1.29.jar` ([Resourceful Lib](https://www.curseforge.com/minecraft/mc-mods/resourceful-lib))
   - `ironfurnaces-1.20.1-4.1.8.jar` ([Iron Furnaces](https://www.curseforge.com/minecraft/mc-mods/iron-furnaces))
2. Run `./gradlew build`.
3. The jars land in each project's `build/libs/`. The core jar and the all-in-one jar share a file name; they sit in different folders.

For a dev client or server, `./gradlew runClient` / `./gradlew runServer`. `-PmodSet=fat|core|dungeons|raids|tournaments|space|all` picks what loads (default `fat`, the all-in-one jar; `all` is the modular set).

The build runs two consistency checks from `tools/`, so Python 3 must be on the path.

## Contributing

Bug reports and suggestions go through the [Discord](https://discord.gg/K7vFwWkKrF). Pull requests are welcome; please keep to Forge 1.20.1 idioms, keep each module independent of the others (cross-module calls go through the hooks in `sdu.api`), and launch-test any mixin change, since a green build does not prove a mixin applies.

## Licence

DMZ Ragnarok is licensed under the [GNU General Public License v3.0](LICENSE), the same licence as DragonMine Z. You may use, modify and redistribute it under the terms of the GPL; keep the licence and the [NOTICE](NOTICE), and credit DMZ Ragnarok and Ragnarok Studios LLC.

All original code and assets are the property of **Ragnarok Studios LLC**. Content that belongs to other projects (DragonMine Z, Minecraft, and the mods it integrates with) remains the property of its owners and under their licences.

## Credits

Built by Shurui. Thanks to **Kiba**, **CalamityKage**, **Rradly** and **WolfyKip** for their work on DMZ Ragnarok.

If you want to support development, [Patreon](https://www.patreon.com/cw/ShuruiDev) is the place. Entirely optional.
