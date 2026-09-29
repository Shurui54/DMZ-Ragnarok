package net.shurui.dev.sdu.client;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;

/**
 * Client-only (per-player) configuration for sdu's HUD extras. Registered as a {@code CLIENT} config so
 * these toggles live in the client's config file and never sync from or apply on a dedicated server.
 *
 * <p>This class deliberately references no {@code net.minecraft.client} type, so it is safe to touch from
 * the common mod-init path when registering the spec; the values it gates are only read by client-side
 * overlay code.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class ClientConfig {

    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    private static final ForgeConfigSpec.BooleanValue OVER_SHOULDER_CROSSHAIR = BUILDER
            .comment("If true, draw an aiming crosshair while DragonMineZ's over-the-shoulder third person",
                    "camera is active (vanilla only draws its crosshair in first person). The reticle tracks",
                    "where the player's look ray actually lands, not screen centre.")
            .translation("config.dmz_ragnarok.npc.overShoulderCrosshair")
            .define("overShoulderCrosshair", true);

    private static final ForgeConfigSpec.BooleanValue FIRST_PERSON_CROSSHAIR = BUILDER
            .comment("If true, replace vanilla's plain white first person crosshair with sdu's custom crescent",
                    "reticle. This suppresses the vanilla crosshair and draws ours at screen centre. Set false to",
                    "keep the vanilla crosshair in first person.")
            .translation("config.dmz_ragnarok.npc.firstPersonCrosshair")
            .define("firstPersonCrosshair", true);

    private static final ForgeConfigSpec.BooleanValue RESKIN_CONTAINERS = BUILDER
            .comment("If true, restyle Minecraft's VANILLA container GUIs (player inventory, hotbar, chests and",
                    "every other vanilla container) with sdu's slot art via a built-in resource pack. Minecraft",
                    "cannot hot-swap a built-in pack cleanly, so a CHANGE to this value only takes effect after",
                    "you reload resources (press F3+T) or restart the game.",
                    "Off by default: the shipped vanilla textures are placeholders generated from vanilla art,",
                    "and purpose drawn container assets are being made. Turn this on once those land.",
                    "The Curios sidebar is a SEPARATE switch (reskinCurios), because its art is finished while",
                    "these are not.")
            .translation("config.dmz_ragnarok.npc.reskinContainers")
            .define("reskinContainers", false);

    private static final ForgeConfigSpec.BooleanValue RESKIN_CURIOS = BUILDER
            .comment("If true, restyle the Curios sidebar and its slot art with sdu's own, via the same built-in",
                    "resource pack. Split from reskinContainers deliberately: the Curios art is drawn for us and",
                    "ready, while the vanilla container art is still placeholder, so one switch could not serve",
                    "both without shipping placeholders to everyone. Does nothing when Curios is not installed.",
                    "Like reskinContainers, a CHANGE only takes effect after a resource reload (F3+T) or restart.")
            .translation("config.dmz_ragnarok.npc.reskinCurios")
            .define("reskinCurios", true);

    private static final ForgeConfigSpec.BooleanValue GUI_SOUNDS = BUILDER
            .comment("If true, sdu's own menus play DragonMineZ's native UI sounds (open, button click, confirm)",
                    "so they feel consistent with DMZ's screens. Set false for silent sdu menus.")
            .translation("config.dmz_ragnarok.npc.guiSounds")
            .define("guiSounds", true);

    private static final ForgeConfigSpec.BooleanValue ALWAYS_SHOW_EXTRA_FORMS_TAB = BUILDER
            .comment("If true, always keep the Extra Forms slot on the transformation wheel, even when your race",
                    "has no form types to put in it. Normally that slot hides itself when it would be empty,",
                    "which is why it comes and goes between races. Turning this on keeps the wheel's layout",
                    "identical for everyone, at the cost of an empty ring when you open it with nothing to show.")
            .translation("config.dmz_ragnarok.npc.alwaysShowExtraFormsTab")
            .define("alwaysShowExtraFormsTab", false);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    public static boolean overShoulderCrosshair = true;
    public static boolean firstPersonCrosshair = true;
    public static boolean reskinContainers = false;
    public static boolean reskinCurios = true;
    public static boolean guiSounds = true;
    public static boolean alwaysShowExtraFormsTab = false;

    private ClientConfig() {
    }

    @SubscribeEvent
    static void onLoad(final ModConfigEvent event) {
        if (event.getConfig().getSpec() == SPEC) {
            overShoulderCrosshair = OVER_SHOULDER_CROSSHAIR.get();
            firstPersonCrosshair = FIRST_PERSON_CROSSHAIR.get();
            reskinContainers = RESKIN_CONTAINERS.get();
            reskinCurios = RESKIN_CURIOS.get();
            guiSounds = GUI_SOUNDS.get();
            alwaysShowExtraFormsTab = ALWAYS_SHOW_EXTRA_FORMS_TAB.get();
        }
    }
}
