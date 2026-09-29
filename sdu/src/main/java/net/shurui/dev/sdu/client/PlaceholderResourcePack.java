package net.shurui.dev.sdu.client;

import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PathPackResources;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraftforge.event.AddPackFindersEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import net.shurui.dev.sdu.DmzNpc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Auto-loads this mod's asset-delivery resource packs. Any pack in {@code resourcepacks/} (folder or zip)
 * whose {@code pack.mcmeta} contains the marker {@value #MARKER} is force-loaded as {@code required} (always
 * on), so people can drop in and share any number. If none are found, a starter pack is generated. Each is
 * registered with the same id vanilla assigns it ({@code file/<name>}) so they load without also appearing as
 * separate toggleable entries.
 *
 * <p>Folders, one sub-folder per asset: {@code npcmodels/<name>/} (a {@code model.json} makes it an NPC
 * model), {@code hairs/<name>/}, {@code auras/<name>/}. Scanning is namespace-agnostic, so packs stack.
 */
public final class PlaceholderResourcePack {

    /** A pack opts in by putting this string anywhere in its {@code pack.mcmeta} (e.g. the description). */
    public static final String MARKER = "shurui's_dmz_resources";
    /** File/namespace base for the generated starter pack. */
    private static final String DEFAULT_NAME = "shuruis_dmz_resources";

    private PlaceholderResourcePack() {
    }

    /**
     * Register every marked pack (generating a starter if none) as a required, always-loaded client pack.
     * Call from an {@link AddPackFindersEvent} handler for {@link PackType#CLIENT_RESOURCES}.
     */
    public static void registerForcedPack(AddPackFindersEvent event) {
        Path packsDir = FMLPaths.GAMEDIR.get().resolve("resourcepacks");
        List<Path> marked = findMarkedPacks(packsDir);
        if (marked.isEmpty()) {
            Path def = packsDir.resolve(DEFAULT_NAME + ".zip");
            if (writeDefault(def)) {
                marked = List.of(def);
            }
        }
        for (Path p : marked) {
            final Path packPath = p;
            final boolean isZip = Files.isRegularFile(p) && p.getFileName().toString().endsWith(".zip");
            final String id = "file/" + p.getFileName().toString();
            event.addRepositorySource(consumer -> {
                Pack pack = Pack.readMetaAndCreate(id, Component.literal("Shurui's DMZ Resources"),
                        true, // required = always enabled, cannot be turned off
                        sid -> isZip ? new FilePackResources(sid, packPath.toFile(), false)
                                : new PathPackResources(sid, packPath, false),
                        PackType.CLIENT_RESOURCES, Pack.Position.TOP, PackSource.DEFAULT);
                if (pack != null) {
                    consumer.accept(pack);
                }
            });
            DmzNpc.LOGGER.info("[{}] Loading marked resource pack '{}'.", DmzNpc.MODID, p.getFileName());
        }
    }

    private static List<Path> findMarkedPacks(Path packsDir) {
        List<Path> out = new ArrayList<>();
        if (!Files.isDirectory(packsDir)) {
            return out;
        }
        try (var stream = Files.list(packsDir)) {
            for (Path p : stream.sorted().toList()) {
                String meta = readMcmeta(p);
                if (meta != null && meta.contains(MARKER)) {
                    out.add(p);
                }
            }
        } catch (IOException e) {
            DmzNpc.LOGGER.warn("[{}] Could not scan resourcepacks: {}", DmzNpc.MODID, e.toString());
        }
        return out;
    }

    /** Read a pack's {@code pack.mcmeta} text from a folder or a zip, or null. */
    private static String readMcmeta(Path path) {
        try {
            String name = path.getFileName().toString();
            if (Files.isRegularFile(path) && name.endsWith(".zip")) {
                try (ZipFile zf = new ZipFile(path.toFile())) {
                    ZipEntry e = zf.getEntry("pack.mcmeta");
                    if (e == null) {
                        return null;
                    }
                    try (var is = zf.getInputStream(e)) {
                        return new String(is.readAllBytes(), StandardCharsets.UTF_8);
                    }
                }
            } else if (Files.isDirectory(path)) {
                Path m = path.resolve("pack.mcmeta");
                if (Files.exists(m)) {
                    return Files.readString(m);
                }
            }
        } catch (Exception ignored) {
            // unreadable / not a pack - just skip it
        }
        return null;
    }

    private static boolean writeDefault(Path zip) {
        try {
            if (!Files.exists(zip)) {
                writeZip(zip);
                DmzNpc.LOGGER.info("[{}] No marked resource pack found - wrote starter '{}' to resourcepacks/. "
                        + "It loads automatically; add assets under assets/{}/{npcmodels,hairs,auras}/. "
                        + "Any pack whose pack.mcmeta contains \"{}\" is auto-loaded too.", DmzNpc.MODID,
                        zip.getFileName(), DEFAULT_NAME, MARKER);
            }
            return Files.exists(zip);
        } catch (IOException e) {
            DmzNpc.LOGGER.warn("[{}] Could not write starter resource pack: {}", DmzNpc.MODID, e.toString());
            return false;
        }
    }

    private static void writeZip(Path zip) throws IOException {
        Files.createDirectories(zip.getParent());
        String base = "assets/" + DEFAULT_NAME + "/";
        String model = base + "npcmodels/example_warrior/";
        // Obvious "replace me" placeholders: opaque magenta body skins, fully transparent faces.
        byte[] bodyPng = placeholderPng(64, 64, 0xFFC800C8);
        byte[] clearPng = placeholderPng(64, 64, 0x00000000);
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zip))) {
            putFile(zos, "pack.mcmeta", PACK_MCMETA);
            putFile(zos, "HOW_TO_USE.txt", HOW_TO_USE);

            // A complete working example, reusing the mod's own bundled default geo/animation/texture so it
            // renders. (id = shuruis_dmz_resources:example_warrior)
            putFile(zos, base + "npcmodels/_README.txt", NPCMODELS_README);
            putFile(zos, model + "model.json", EXAMPLE_MODEL);
            putResource(zos, model + "example_warrior.geo.json", "/assets/dmz_ragnarok/geo/npc_default.geo.json");
            putResource(zos, model + "animations/example_warrior.animation.json", "/assets/dmz_ragnarok/animations/npc_default.animation.json");
            putResource(zos, model + "textures/example_warrior.png", "/assets/dmz_ragnarok/textures/entity/npc_default.png");

            // hairs / auras - one sub-folder per asset.
            putFile(zos, base + "hairs/_README.txt", HAIRS_README);
            putDir(zos, base + "hairs/example_hair/");
            putFile(zos, base + "auras/_README.txt", AURAS_README);
            putDir(zos, base + "auras/example_aura/");

            // DMZ resolves these ONLY from its OWN namespace at fixed paths. race id = "example_race" (folder
            // under textures/entity/races/); Custom Model = "example_model" (geo file name AND texture prefix).
            String dmz = "assets/dragonminez/";
            String raceTex = dmz + "textures/entity/races/example_race/";
            putFile(zos, dmz + "geo/entity/races/_README.txt", CUSTOM_MODELS_README);
            // geo: <model>.geo.json (placeholder mesh, rig it to DMZ's player bones).
            putResource(zos, dmz + "geo/entity/races/example_model.geo.json", "/assets/dmz_ragnarok/geo/npc_default.geo.json");
            // body skins: <model>_<gender>_<bodyType>_layer1.png (male/female x body types 0,1,2).
            for (String g : new String[]{"male", "female"}) {
                for (int bt = 0; bt < 3; bt++) {
                    putBytes(zos, raceTex + "example_model_" + g + "_" + bt + "_layer1.png", bodyPng);
                }
            }
            // faces: <model>_nose_0 / _mouth_0 / _eye_0_<0..3> (transparent until painted).
            putFile(zos, raceTex + "faces/_README.txt", FACES_README);
            putBytes(zos, raceTex + "faces/example_model_nose_0.png", clearPng);
            putBytes(zos, raceTex + "faces/example_model_mouth_0.png", clearPng);
            for (int layer = 0; layer < 4; layer++) {
                putBytes(zos, raceTex + "faces/example_model_eye_0_" + layer + ".png", clearPng);
            }
        }
    }

    private static void putFile(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    private static void putBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    /** Copy a resource bundled in the mod jar into the pack; skips (with a warning) if it's not found. */
    private static void putResource(ZipOutputStream zos, String name, String classpathResource) throws IOException {
        try (var in = PlaceholderResourcePack.class.getResourceAsStream(classpathResource)) {
            if (in == null) {
                DmzNpc.LOGGER.warn("[{}] Starter pack: bundled resource '{}' missing, skipping.", DmzNpc.MODID, classpathResource);
                return;
            }
            zos.putNextEntry(new ZipEntry(name));
            in.transferTo(zos);
            zos.closeEntry();
        }
    }

    private static void putDir(ZipOutputStream zos, String name) throws IOException {
        zos.putNextEntry(new ZipEntry(name.endsWith("/") ? name : name + "/"));
        zos.closeEntry();
    }

    /** Build a solid w x h RGBA PNG of one colour (ARGB) - our "replace me" placeholder textures. */
    private static byte[] placeholderPng(int w, int h, int argb) {
        byte a = (byte) (argb >>> 24), r = (byte) (argb >>> 16), g = (byte) (argb >>> 8), b = (byte) argb;
        byte[] raw = new byte[h * (1 + w * 4)];
        int i = 0;
        for (int y = 0; y < h; y++) {
            raw[i++] = 0; // no per-scanline filter
            for (int x = 0; x < w; x++) {
                raw[i++] = r;
                raw[i++] = g;
                raw[i++] = b;
                raw[i++] = a;
            }
        }
        java.util.zip.Deflater def = new java.util.zip.Deflater();
        def.setInput(raw);
        def.finish();
        java.io.ByteArrayOutputStream idat = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        while (!def.finished()) {
            idat.write(buf, 0, def.deflate(buf));
        }
        def.end();
        java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
        try {
            png.write(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'});
            java.io.ByteArrayOutputStream ihdr = new java.io.ByteArrayOutputStream();
            writeInt(ihdr, w);
            writeInt(ihdr, h);
            ihdr.write(8);  // bit depth
            ihdr.write(6);  // colour type RGBA
            ihdr.write(0);  // compression
            ihdr.write(0);  // filter
            ihdr.write(0);  // interlace
            writeChunk(png, "IHDR", ihdr.toByteArray());
            writeChunk(png, "IDAT", idat.toByteArray());
            writeChunk(png, "IEND", new byte[0]);
        } catch (IOException e) {
            return new byte[0]; // in-memory streams don't throw; keep the compiler happy
        }
        return png.toByteArray();
    }

    private static void writeInt(java.io.OutputStream out, int v) throws IOException {
        out.write((v >>> 24) & 0xFF);
        out.write((v >>> 16) & 0xFF);
        out.write((v >>> 8) & 0xFF);
        out.write(v & 0xFF);
    }

    private static void writeChunk(java.io.ByteArrayOutputStream out, String type, byte[] data) throws IOException {
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(typeBytes);
        crc.update(data);
        out.write(typeBytes);
        out.write(data);
        writeInt(out, (int) crc.getValue());
    }

    // pack_format 15 = Minecraft 1.20 / 1.20.1. The marker string opts this pack into auto-loading.
    private static final String PACK_MCMETA = """
            {
              "pack": {
                "pack_format": 15,
                "description": "Shurui's DMZ Resources - asset delivery (marker: shurui's_dmz_resources)"
              }
            }
            """;

    private static final String EXAMPLE_MODEL = """
            {
              "display_name": "Example Warrior",
              "geo": "example_warrior.geo.json",
              "texture": "textures/example_warrior.png",
              "animation": "animations/example_warrior.animation.json",
              "scale": 1.0,
              "animations": {
                "idle": "idle",
                "walk": "walk",
                "attack": "attack",
                "hurt": "hurt",
                "death": "death"
              }
            }
            """;

    private static final String HOW_TO_USE = """
            Shurui's DMZ Resources - asset delivery pack
            ============================================

            This pack loads automatically because its pack.mcmeta contains the marker
            "shurui's_dmz_resources". ANY pack in resourcepacks/ (folder or .zip) with that marker is
            auto-loaded and forced on - so you can share and stack as many as you like. They only
            deliver assets to the mod; they never override vanilla files.

            To add content, edit this .zip (or unzip, edit, re-zip with pack.mcmeta at the root).
            Everything lives under:  assets/<pack namespace>/  (this pack uses "shuruis_dmz_resources")

            Under your namespace (assets/<pack namespace>/) - one sub-folder per asset:

            npcmodels/<name>/        a GeckoLib model for OUR NPC entities ONLY (the /rg npc editor).
                model.json           OPTIONAL catalog for an NPC model (see example_warrior/)
                <name>.geo.json      the GeckoLib geo model
                textures/<file>.png  its texture(s)
                animations/<file>.animation.json   its animation(s)

            hairs/<name>/            a custom hair (its model + animation)
            auras/<name>/            a custom aura (its aura asset)

              - The NPC-model / hair / aura id shown in those editors is the <name> folder.
              - For an NPC model, paths inside model.json are relative to that model's own folder, and
                its in-game id is <pack namespace>:<name>.

            TWO WORKED EXAMPLES ship in this pack - copy either and swap in your own files:
              * NPC model (works right now, selectable in /rg npc):
                  npcmodels/example_warrior/  (model.json + geo + animation + textures/)
              * Race / Form custom model (shows the DMZ naming convention):
                  assets/dragonminez/geo/entity/races/example_model.geo.json
                  assets/dragonminez/textures/entity/races/example_race/example_model_<gender>_<bodyType>_layer1.png
                  assets/dragonminez/textures/entity/races/example_race/faces/example_model_<part>_<index>.png
                (race id = "example_race", Custom Model name = "example_model" - the magenta squares are
                 placeholders; replace them with your art.)

            IMPORTANT - RACE / FORM custom models are DIFFERENT: DragonMineZ only loads a race/form
            "Custom Model" from ITS OWN namespace at a fixed path - NOT from npcmodels/. Put those under
            assets/dragonminez/ (see the _README in geo/entity/races/). Anything placed only in
            npcmodels/ will show the plain human model in-game.

            To make your OWN pack: give its pack.mcmeta a description containing shurui's_dmz_resources,
            put your assets under a unique namespace (except race/form models + their textures + faces,
            which MUST be under the dragonminez namespace), and drop it in resourcepacks/. Press F3+T.
            """;

    private static final String NPCMODELS_README = """
            npcmodels/ - one sub-folder per model, each holding its geo model + animation (+ textures).
            The <folder name> is the model id shown in the /rg npc editor (id = <pack namespace>:<folder>).

            WORKED EXAMPLE (complete + working): example_warrior/
                model.json                          catalog: names the geo/texture/animation + anim map
                example_warrior.geo.json            the GeckoLib mesh
                textures/example_warrior.png        its skin
                animations/example_warrior.animation.json
              Copy the folder, rename it, replace the three files, and update model.json - done.
              Paths inside model.json are RELATIVE to the model's own folder.

            NOTE: these are for OUR NPC entities only. A race/form "Custom Model" is a DMZ feature and must
            live under the dragonminez namespace instead - see assets/dragonminez/geo/entity/races/_README.txt.
            """;

    private static final String HAIRS_README = """
            hairs/ - one sub-folder per hair, holding its model + animation.
            The <folder name> is the hair id shown in the form/race editors.
            """;

    private static final String AURAS_README = """
            auras/ - one sub-folder per aura, holding its aura asset.
            The <folder name> is the aura id shown in the form/race editors.
            """;

    private static final String FACES_README = """
            FACE customization (eyes / nose / mouth) for a custom race/model.

            Folder is the RACE id; the file names are prefixed with the CUSTOM MODEL name (same rule as
            the body skins one folder up). They live under DragonMineZ's own namespace:
              assets/dragonminez/textures/entity/races/<race>/faces/<model>_<part>_<index>...png

            1. This example ships as race "example_race" using model "example_model" (see the sibling
               example_model_*_layer1.png body skins). Copy the example_race/ folder, rename it to YOUR
               race id, and rename the files' prefix to YOUR Custom Model name.
            2. File names, exactly:
                 <model>_eye_<index>_<layer>.png   layer is 0,1,2,3 (all four make up one eye option)
                 <model>_nose_<index>.png
                 <model>_mouth_<index>.png
               <index> starts at 0 and counts up (0,1,2,...) for each extra option.
            3. In /rg npc race -> Body tab, the Eyes/Nose/Mouth pickers list the <index> values you added.

            The shipped placeholders (example_model_nose_0.png, _mouth_0.png, _eye_0_0..3.png) are fully
            TRANSPARENT - that's why the example face is blank. Paint them to give it a face.
            (A race with NO Custom Model uses the race id itself as the prefix instead.)
            """;

    private static final String CUSTOM_MODELS_README = """
            RACE / FORM custom MODELS (the "Custom Model" field in /rg npc race and /rg npc form).

            DragonMineZ loads these ONLY from ITS OWN namespace at a fixed path - it does NOT read the
            pack's npcmodels/ folder for race/form models. Two separate things can go wrong:
              * geo missing  -> DMZ silently renders the plain human model (a generic human, no error).
              * geo found but skin texture missing/misnamed -> the PURPLE-AND-BLACK "missing texture".
            So a purple/black model means the .geo.json loaded fine but no matching skin PNG was found.

            1. GEO MODEL - put the GeckoLib geo here:
                 assets/dragonminez/geo/entity/races/<model>.geo.json
               <model> (lowercase) is exactly what you type/pick in the Custom Model field, and it
               appears in the editor dropdown once the file is in a loaded pack. If your race has genders,
               ship <model>_male.geo.json and <model>_female.geo.json instead (pick "<model>" - DMZ adds
               the suffix). The model must use DMZ's player bone names (root/waist/head/right_arm/...).

            2. SKIN TEXTURES - the folder is named after the RACE (raceName), and the file names are
               prefixed with the CUSTOM MODEL name (the "Custom Model" value). For a race whose id is
               <race> using Custom Model <model>:
                 assets/dragonminez/textures/entity/races/<race>/<model>_<gender>_<bodyType>_layerN.png
               where:
                 <gender>   = male or female. (Drop the "_<gender>" only if the race has "Has Gender" OFF.)
                 <bodyType> = the body-type NUMBER: 0, 1, 2. Characters can be any of these, so you MUST at
                              least ship the 0 files or you get purple/black. (defaultBodyType is only the
                              starting value.)
                 _layerN    = _layer1 is the base body (REQUIRED when "Is Layered" is ON, the default);
                              _layer2/_layer3 are optional extra tint layers coloured by Body Color 2/3.
                              If "Is Layered" is OFF, use NO _layerN suffix.

               >>> CAUSES OF PURPLE/BLACK, in order: (a) wrong prefix - it is the MODEL name, NOT "human"
                   or "bodytype"; (b) missing the _layer1 base or wrong _layerN vs "Is Layered"; (c) not
                   shipping body type 0. Check the game log: "Failed to load texture: dragonminez:.../<race>/
                   <model>_<gender>_<bt>_layer1.png" tells you the EXACT filename DMZ wants. <<<

               Minimal set for a gendered, layered race, covering body type 0:
                 <race>/<model>_male_0_layer1.png
                 <race>/<model>_female_0_layer1.png
               Add _1 / _2 (body types 1 and 2) as you need them.

               FACES (nose/mouth/eyes) go in <race>/faces/, also prefixed with the MODEL name:
                 <race>/faces/<model>_nose_<i>.png, <model>_mouth_<i>.png,
                 <race>/faces/<model>_eye_<i>_<0..3>.png   (four layers make one eye option; <i> = 0,1,...)
               A missing face texture is a purple bit on the face - ship at least the _0 files, or a fully
               transparent PNG if you want no nose/mouth.

            3. FACES (eyes/nose/mouth) for a custom model use the per-RACE faces/ folder - see
               textures/entity/races/<race>/faces/ and its _README.

            WORKED EXAMPLE shipped in this pack (race "example_race", Custom Model "example_model"):
               geo/entity/races/example_model.geo.json
               textures/entity/races/example_race/example_model_male_0_layer1.png    (+ _1, _2)
               textures/entity/races/example_race/example_model_female_0_layer1.png  (+ _1, _2)
               textures/entity/races/example_race/faces/example_model_nose_0.png     (transparent)
               textures/entity/races/example_race/faces/example_model_mouth_0.png    (transparent)
               textures/entity/races/example_race/faces/example_model_eye_0_0.png .. _eye_0_3.png
             The magenta body squares are placeholders - replace them with your skin. To USE it, make a
             race in /rg npc race whose id is "example_race" and whose Custom Model is "example_model" (the
             geo here is a placeholder mesh; swap in one rigged to DMZ's player bones).

            Tip: the quickest custom race is to copy one of DMZ's built-in models (set Custom Model to
            e.g. "saiyan" or "buffed") - those already ship their geo + textures, so nothing renders purple.
            """;

}
