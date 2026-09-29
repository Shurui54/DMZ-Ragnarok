package net.shurui.shuruisutilities.client;

import org.lwjgl.glfw.GLFW;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.hub.PacketOpenEditor;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.settings.KeyConflictContext;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

// forge-bus handler that polls the SU keybinds each tick (no screen open) and fires their packets. mappings are
// registered on the mod bus in SUClientMenus.
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SUKeybinds
{
    private SUKeybinds() {}

    // unbound by default. used to default to G, which collides with DMZ's action/ki-fire key (pressing G opened
    // this menu and swallowed the ki blast), so ship it unbound to avoid stealing anyone's key.
    public static final KeyMapping OPEN_MENU = new KeyMapping(
            "key.dmz_ragnarok.core.menu", KeyConflictContext.IN_GAME, com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_UNKNOWN, "key.categories.dmz_ragnarok");

    // toggles the NPC-region overlay while Xaero's World Map is open. handled from a ScreenEvent (a keybind poll
    // never fires while a screen is open). defaults to O; only acts on the world map so it can't clash in-game.
    public static final KeyMapping TOGGLE_REGION_OVERLAY = new KeyMapping(
            "key.dmz_ragnarok.core.toggle_region_overlay", KeyConflictContext.GUI,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_O, "key.categories.dmz_ragnarok");

    // opens the character-slot picker. unbound by default.
    public static final KeyMapping OPEN_CHARACTERS = new KeyMapping(
            "key.dmz_ragnarok.core.characters", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.dmz_ragnarok");

    // opens the drag-to-move screen for the NPC-region HUD. unbound by default.
    public static final KeyMapping MOVE_REGION_HUD = new KeyMapping(
            "key.dmz_ragnarok.core.move_region_hud", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.dmz_ragnarok");

    // deploy/recall the curios-slotted hoverbike. unbound by default. all logic is server-side; this just fires
    // the empty toggle packet.
    public static final KeyMapping TOGGLE_HOVERBIKE = new KeyMapping(
            "key.dmz_ragnarok.core.hoverbike", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.dmz_ragnarok");

    // opens DMZ's own space-pod planet chooser anywhere (no pod, dimension or space needed) so a player can set or
    // change the planet they are tracking on foot. Also fires a course-query packet so the current course is named
    // in chat when the menu opens. unbound by default: DMZ already binds H to its own spacepod_menu, so shipping
    // this unbound keeps it off every DMZ and vanilla key until the player chooses one.
    public static final KeyMapping OPEN_PLANET_SELECT = new KeyMapping(
            "key.dmz_ragnarok.core.planet_select", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.dmz_ragnarok");

    // stops tracking the current planet, clearing the compass pip through the same server path as an arrival clear.
    // unbound by default.
    public static final KeyMapping CLEAR_PLANET_COURSE = new KeyMapping(
            "key.dmz_ragnarok.core.clear_course", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.dmz_ragnarok");

    // toggles the FLOATING planet-info overlay on and off (a persistent client preference in PlanetInfoConfig). While
    // on, the panel appears by itself whenever the player aims at a planet in space and hides when they look away; there
    // is no screen to open any more. Defaults to P ("P for Planet"), the ONE space keybind we ship bound: the feature is
    // invisible when unbound (nothing to press, no menu entry to hint at it), so it needs a real default to be
    // discoverable. P is free everywhere it could clash: vanilla binds nothing to P, DMZ's KeyBinds uses none of P (its
    // keys are V/C/G/H/X/Z/F/R and modifier combos), and no other SU keybind uses it (SU only otherwise binds O, on the
    // world map). If a player's own resource/mod pack does steal P, the /planet look command is a rebind-free fallback
    // that prints the same information to chat, so this key is never the only way to read a planet.
    public static final KeyMapping OPEN_PLANET_INFO = new KeyMapping(
            "key.dmz_ragnarok.core.planet_info", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_P, "key.categories.dmz_ragnarok");

    // opens the equipped dragon ball bag's inventory. all logic is server-side; this just fires the empty open
    // packet and the server validates + opens the menu. Defaults to B ("B for Bag"): B is free everywhere it could
    // clash. Vanilla 1.20.1 binds nothing to B, DMZ's KeyBinds use V/C/G/H/X/Z/F/R (never B), and no other SU
    // keybind uses B (SU only otherwise binds O on the world map and P for planet info). Shipping it bound, not
    // unbound, is deliberate: an unbound key was a real bug earlier in this project, and the bag is meant to be
    // opened often, so it needs a discoverable default.
    public static final KeyMapping OPEN_DRAGONBALL_BAG = new KeyMapping(
            "key.dmz_ragnarok.core.dragonball_bag", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_B, "key.categories.dmz_ragnarok");

    // opens the equipped senzu bean bag's inventory to fill it. all logic is server-side; this just fires the empty
    // open packet and the server validates + opens the menu. Defaults to N: N is free everywhere it could clash.
    // Vanilla 1.20.1 binds nothing to N (its Social-Interactions default is P, not N), DMZ's KeyBinds use
    // V/C/G/H/X/Z/F/R (never N), and no other SU keybind uses N (SU otherwise binds O, P and B). Shipping it bound,
    // not unbound, is deliberate: an unbound key was a real bug earlier in this project.
    public static final KeyMapping OPEN_SENZU_BAG = new KeyMapping(
            "key.dmz_ragnarok.core.senzu_bag", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N, "key.categories.dmz_ragnarok");

    // pulls a single bean out of the equipped bag straight into the inventory, without opening anything: the
    // mid-fight "hand me a bean" key. all logic is server-side; this just fires the empty pull packet. Defaults to M,
    // sitting right next to the N bag key so the related pair is ergonomically grouped. M is free everywhere it could
    // clash: vanilla 1.20.1 binds nothing to M, DMZ uses V/C/G/H/X/Z/F/R (never M), and no other SU keybind uses M.
    public static final KeyMapping TAKE_SENZU_BEAN = new KeyMapping(
            "key.dmz_ragnarok.core.senzu_bean", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_M, "key.categories.dmz_ragnarok");

    // Dash. Double tapped rather than pressed once, so this can sit on a key the player already uses without firing
    // every time they move. See DashInput for the gesture window.
    public static final KeyMapping DASH = new KeyMapping(
            "key.dmz_ragnarok.core.dash", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_R, "key.categories.dmz_ragnarok");

    // Ragnarok role ability. One key for every role: the SERVER decides what it does from the role the presser
    // actually holds (God of Destruction raises the destruction aura, Angel enters the parry state), so this sends
    // an empty request and never names an ability.
    public static final KeyMapping ROLE_ABILITY = new KeyMapping(
            "key.dmz_ragnarok.core.role_ability", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_V, "key.categories.dmz_ragnarok");

    // opens the daily/weekly/monthly task board. unbound by default, like every other menu key here: shipping a
    // default would steal a key somebody has already bound to something they care about.
    public static final KeyMapping OPEN_TASKS = new KeyMapping(
            "key.dmz_ragnarok.core.tasks", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "key.categories.dmz_ragnarok");

    // --- racing (own category) ---
    // Rebindable ARROW-key steering for a race. They default to the arrow keys and drive the kart physics exactly like
    // A/D/W/S, so a racer can steer with either hand: Left/Right steer, Up accelerate, Down brake. RaceInput ORs these
    // with the movement keys, and only acts while the player rides a race bike, so the arrows do nothing otherwise.
    // Registered in the core client and NEVER removed from the options, so a keyless client still shows them (they are
    // simply inert without a race). Own category "DMZ Ragnarok Racing".
    private static final String RACE_CATEGORY = "key.categories.dmz_ragnarok.racing";

    public static final KeyMapping RACE_STEER_LEFT = new KeyMapping(
            "key.dmz_ragnarok.racing.steer_left", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_LEFT, RACE_CATEGORY);

    public static final KeyMapping RACE_STEER_RIGHT = new KeyMapping(
            "key.dmz_ragnarok.racing.steer_right", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT, RACE_CATEGORY);

    public static final KeyMapping RACE_ACCELERATE = new KeyMapping(
            "key.dmz_ragnarok.racing.accelerate", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UP, RACE_CATEGORY);

    public static final KeyMapping RACE_BRAKE = new KeyMapping(
            "key.dmz_ragnarok.racing.brake", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_DOWN, RACE_CATEGORY);

    // Toggles the race minimap panel (top-right of the HUD). The minimap is ON by default, so this ships UNBOUND (like
    // every other menu key here) to avoid stealing a key; a player who wants to hide it can bind this. Only meaningful
    // while racing; inert otherwise.
    public static final KeyMapping RACE_MINIMAP = new KeyMapping(
            "key.dmz_ragnarok.racing.minimap", KeyConflictContext.IN_GAME,
            com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, RACE_CATEGORY);

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event)
    {
        if (event.phase != TickEvent.Phase.END)
            return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null)
            return;
        // The role abilities are a Ragnarok Key feature (roles): against a server without it the key is inert. The
        // presses are still drained so they do not fire late if the feature arrives.
        boolean roles = net.shurui.dev.sdu.api.ClientGate.feature("roles");
        while (ROLE_ABILITY.consumeClick())
            if (roles)
                NetworkUtils.INSTANCE.sendToServer(
                        new net.shurui.shuruisutilities.energy.PacketAbilityActivate());
        while (OPEN_MENU.consumeClick())
            NetworkUtils.INSTANCE.sendToServer(new PacketOpenEditor("menu"));
        // The task board, the region HUD mover and the dragon ball bag are private: their keys are hidden from Controls
        // (PrivateKeybinds) and inert unless the server reported the key. Presses are still drained, like the roles.
        boolean key = net.shurui.dev.sdu.api.ClientGate.key();
        while (OPEN_TASKS.consumeClick())
            if (key)
                NetworkUtils.INSTANCE.sendToServer(new PacketOpenEditor("taskboard"));
        while (OPEN_CHARACTERS.consumeClick())
            NetworkUtils.INSTANCE.sendToServer(
                    new net.shurui.shuruisutilities.character.PacketCharacterAction("open", 0, ""));
        while (MOVE_REGION_HUD.consumeClick())
            if (key)
                mc.setScreen(new net.shurui.shuruisutilities.client.hud.HudMoveScreen());
        while (TOGGLE_HOVERBIKE.consumeClick())
            NetworkUtils.INSTANCE.sendToServer(new net.shurui.shuruisutilities.hoverbike.PacketHoverbikeToggle());
        while (OPEN_PLANET_SELECT.consumeClick())
        {
            // ask the server to name the current course (shown in chat), then open DMZ's own chooser. DMZ's
            // SpacePodScreen has a no-arg constructor and never reads a pod or the player's vehicle, so opening it on
            // foot is safe; its travel packet is turned into a compass course (or refused, for a non-body pick on
            // foot) by MixinDmzTravelToPlanet -> PlanetCourse, so no teleport or pod can result from opening it here.
            // route through the core SpaceClientHook so this handler never names the Space client classes; a no-op when
            // Space is absent. DMZ is mandatory, so its own chooser still opens either way.
            net.shurui.dev.sdu.api.SpaceClientHook.courseKey(false);
            mc.setScreen(new com.dragonminez.client.gui.SpacePodScreen());
        }
        while (CLEAR_PLANET_COURSE.consumeClick())
            net.shurui.dev.sdu.api.SpaceClientHook.courseKey(true);
        while (OPEN_PLANET_INFO.consumeClick())
            // flip the persistent overlay preference through the hook; the overlay does the look-at detection itself.
            net.shurui.dev.sdu.api.SpaceClientHook.togglePlanetInfo();
        while (OPEN_DRAGONBALL_BAG.consumeClick())
            // ask the server to open our equipped bag; it validates and opens the menu (or messages if none equipped).
            if (key)
                NetworkUtils.INSTANCE.sendToServer(
                        new net.shurui.shuruisutilities.dragonballbag.PacketOpenDragonBallBag());
        while (OPEN_SENZU_BAG.consumeClick())
            // ask the server to open our equipped bean bag; it validates and opens the menu (or messages if none).
            NetworkUtils.INSTANCE.sendToServer(
                    new net.shurui.shuruisutilities.senzu.bag.PacketOpenSenzuBag());
        while (TAKE_SENZU_BEAN.consumeClick())
            // ask the server to pull one bean from our equipped bean bag into the inventory; server is authoritative.
            NetworkUtils.INSTANCE.sendToServer(
                    new net.shurui.shuruisutilities.senzu.bag.PacketTakeSenzuBean());
        while (RACE_MINIMAP.consumeClick())
            // purely client-side: flip the race minimap panel on / off. Racing is a key feature (racing).
            if (net.shurui.dev.sdu.api.ClientGate.feature(net.shurui.shuruisutilities.api.key.RaceHooks.FEATURE_ID))
                net.shurui.shuruisutilities.client.hud.RaceHudOverlay.toggleMinimap();
    }
}
