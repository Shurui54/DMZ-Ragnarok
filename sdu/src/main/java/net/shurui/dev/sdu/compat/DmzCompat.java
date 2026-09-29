package net.shurui.dev.sdu.compat;

import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.fml.ModList;
import net.shurui.dev.sdu.Config;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.race.SuppressedDefaultsConfig;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reflection bridge to DMZ, which has no public API. Every access is try/catch'd and cached; if DMZ
 * is absent or renames its internals, these degrade to no-ops (multiplier 1.0) instead of crashing.
 * Stat surface is {@code com.dragonminez.common.stats.StatsProvider}/{@code StatsData}; power drives
 * the ki-damage multiplier so NPC hits track DMZ combat.
 */
public final class DmzCompat {

    private static final String STATS_PROVIDER = "com.dragonminez.common.stats.StatsProvider";

    private static boolean initialised = false;
    private static boolean available = false;
    private static Object capabilityToken; // Capability<StatsData>
    private static Method getCapability;   // ICapabilityProvider#getCapability(Capability)
    private static Method resolveMethod;   // LazyOptional#resolve()
    private static Method powerGetter;     // best-effort numeric getter on StatsData

    private DmzCompat() {
    }

    public static boolean isLoaded() {
        return ModList.get().isLoaded("dragonminez");
    }

    /**
     * Write DMZ's current config version into {@code o} in the right JSON type, read live from
     * {@code ConfigManager.CONFIG_VERSION}. Type matters: DMZ &le;2.1.0 stores it as a number ({@code 21.0},
     * double), 2.1.1+ as a semver string. Wrong type and the whole config file fails to parse, so the form/race
     * silently never loads. Falls back to "2.1.1" if DMZ is unreadable.
     */
    public static void writeConfigVersion(com.google.gson.JsonObject o) {
        Object v = null;
        try {
            v = Class.forName("com.dragonminez.common.config.ConfigManager").getField("CONFIG_VERSION").get(null);
        } catch (Throwable t) {
            DmzNpc.LOGGER.debug("[{}] Could not read DMZ CONFIG_VERSION ({}); defaulting.", DmzNpc.MODID, t.toString());
        }
        if (v instanceof Number n) {
            o.addProperty("configVersion", n);
        } else if (v != null) {
            o.addProperty("configVersion", v.toString());
        } else {
            o.addProperty("configVersion", "2.1.1");
        }
    }

    /**
     * Reload DMZ configs from disk so editor-written form/race files take effect. No-op if DMZ absent
     * or {@code ConfigManager.reload()} missing. Returns true if the reload actually ran.
     */
    public static boolean reloadConfigs() {
        if (!isLoaded()) {
            return false;
        }
        try {
            Class<?> configManager = Class.forName("com.dragonminez.common.config.ConfigManager");
            configManager.getMethod("reload").invoke(null);
            DmzNpc.LOGGER.info("[{}] Reloaded DragonMine Z configs.", DmzNpc.MODID);
            // reload regenerated defaults; re-strip suppressed ids before anyone reads them
            applySuppression();
            // reload also reloaded raw JSON flags, so re-run the formStackable repair
            repairFormStackable();
            return true;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Could not reload DMZ configs ({}); a restart/rejoin may be needed.",
                    DmzNpc.MODID, t.toString());
            return false;
        }
    }

    /**
     * Re-push DMZ's server configs to every online player, mirroring DMZ's {@code ReloadCommand} config branch.
     * Call AFTER {@link #reloadConfigs()} so saved/deleted race &amp; form files (notably {@code customModel})
     * apply live without a rejoin.
     *
     * <p>Reflective chain (all public in DMZ 2.1.2): {@code getAvailableConfigFiles() -> List<String>},
     * {@code getSpecificConfigJson(String) -> String}, {@code new SyncServerConfigS2C(path, json, reset)},
     * {@code NetworkHandler.sendToPlayer(msg, player)}.
     *
     * <p>Skip semantics copy ReloadCommand: skip {@code "general-user"} and any null/blank JSON. Per player the
     * first sent file carries {@code reset=true} (triggers the client's sync batch), the rest false. Any failure
     * logs one warn and no-ops.
     */
    public static boolean resyncConfigsToAll(net.minecraft.server.MinecraftServer server) {
        if (!isLoaded() || server == null) {
            return false;
        }
        // strip suppressed defaults before reading the JSON we push, so clients get the stripped set
        applySuppression();
        // repair formStackable on the same maps so the pushed JSON carries corrected flags
        repairFormStackable();
        try {
            Class<?> configManager = Class.forName("com.dragonminez.common.config.ConfigManager");
            Class<?> networkHandler = Class.forName("com.dragonminez.common.network.NetworkHandler");
            Class<?> syncPacket = Class.forName("com.dragonminez.common.network.S2C.SyncServerConfigS2C");

            Method getFiles = configManager.getMethod("getAvailableConfigFiles");
            Method getJson = configManager.getMethod("getSpecificConfigJson", String.class);
            java.lang.reflect.Constructor<?> packetCtor =
                    syncPacket.getConstructor(String.class, String.class, boolean.class);
            Method sendToPlayer = networkHandler.getMethod(
                    "sendToPlayer", Object.class, net.minecraft.server.level.ServerPlayer.class);

            @SuppressWarnings("unchecked")
            java.util.List<String> files = (java.util.List<String>) getFiles.invoke(null);
            if (files == null) {
                return true;
            }

            for (net.minecraft.server.level.ServerPlayer player : server.getPlayerList().getPlayers()) {
                boolean reset = true; // first non-skipped file per player begins the client batch
                for (String file : files) {
                    if (file == null || "general-user".equals(file)) {
                        continue;
                    }
                    String json = (String) getJson.invoke(null, file);
                    if (json == null || json.isBlank()) {
                        continue;
                    }
                    Object msg = packetCtor.newInstance(file, json, reset);
                    sendToPlayer.invoke(null, msg, player);
                    reset = false;
                }
            }
            return true;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Could not resync DMZ configs to players ({}); a rejoin may be needed.",
                    DmzNpc.MODID, t.toString());
            return false;
        }
    }

    // Suppression of DMZ default races/classes (see SuppressedDefaultsConfig).

    private static final String CONFIG_MANAGER = "com.dragonminez.common.config.ConfigManager";

    /**
     * Strip every suppressed default race/class id from DMZ's in-memory maps. DMZ regenerates defaults on every
     * boot/reload, so this must run after DMZ (re)loads: on boot, at the end of {@link #reloadConfigs()}, and at
     * the start of {@link #resyncConfigsToAll} (before reading the JSON we push).
     *
     * <p>Reflects into {@code ConfigManager} statics (DMZ 2.1.3): {@code LOADED_RACES} (List),
     * {@code RACE_CHARACTER}/{@code RACE_STATS}/{@code RACE_FORMS} (Maps), and the server-sync mirrors
     * {@code SERVER_SYNCED_*} (null until first sync, skipped when null/empty). Class ids live inside each race's
     * {@code RaceStatsConfig.getClasses()}. Any missing field/method logs a warn and no-ops.
     */
    public static void applySuppression() {
        if (!isLoaded()) {
            return;
        }
        Set<String> races = SuppressedDefaultsConfig.races();
        Set<String> classes = SuppressedDefaultsConfig.classes();
        if (races.isEmpty() && classes.isEmpty()) {
            return;
        }
        Class<?> cm;
        try {
            cm = Class.forName(CONFIG_MANAGER);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Suppression skipped: ConfigManager not found ({}).", DmzNpc.MODID, t.toString());
            return;
        }

        // races: remove from LOADED_RACES + the authoritative and server-synced maps
        if (!races.isEmpty()) {
            removeFromList(cm, "LOADED_RACES", races);
            for (String mapField : new String[]{
                    "RACE_CHARACTER", "RACE_STATS", "RACE_FORMS",
                    "SERVER_SYNCED_CHARACTER", "SERVER_SYNCED_STATS", "SERVER_SYNCED_FORMS"}) {
                removeKeysFromMap(cm, mapField, races);
            }
        }

        // classes: strip suppressed class ids from every race's RaceStatsConfig.getClasses()
        if (!classes.isEmpty()) {
            stripClasses(cm, "RACE_STATS", classes);
            stripClasses(cm, "SERVER_SYNCED_STATS", classes);
        }

        DmzNpc.LOGGER.info("[{}] Applied suppression: {} race(s), {} class(es).",
                DmzNpc.MODID, races.size(), classes.size());
    }

    // Legacy formStackable repair. Same lifecycle as applySuppression():
    // boot (ForgeEvents), end of reloadConfigs(), start of resyncConfigsToAll().

    // ultimate form group/self key that must never flip to stackable
    private static final String ULTIMATE_KEY = "ultimate.ultimate";

    /**
     * Reinterpret the legacy {@code formStackable=false} default as {@code true} on DMZ's own in-memory
     * {@code FormConfig.FormData}, after DMZ (re)loads its config.
     *
     * <p>In-memory, not the files: DMZ's stacking check ({@code FormModeHandler}/
     * {@code StackFormModeHandler}) reads ConfigManager's loaded objects, not sdu's editor copy. An old
     * sdu build stamped {@code "formStackable": false} into every form JSON, so many on-disk forms carry a
     * false nobody chose; DMZ's check refuses to stack if either base or stack form is non-stackable, so
     * they silently stopped stacking. We patch the flag in memory only.
     *
     * <p>Filter (per FormData that's explicitly false): flip to true UNLESS (a) it's the ultimate form
     * (group {@code ultimate}, or self key {@code ultimate.ultimate}), or (b) its {@code incompatibleWith}
     * lists anything other than {@code ultimate.ultimate} (the ubiquitous default, not real intent) => a
     * genuine incompatibility means an intentional special form, left alone.
     *
     * <p>Iterates {@code getAllForms()} (per-race base) + {@code getAllStackForms()} (race-agnostic stack
     * groups), plus the {@code SERVER_SYNCED_*} mirrors (null until first sync) so clients get the fix too.
     * Uses the public {@code setFormStackable(Boolean)} setter. Any error leaves values as-is.
     */
    public static void repairFormStackable() {
        if (!isLoaded()) {
            return;
        }
        Class<?> cm;
        try {
            cm = Class.forName(CONFIG_MANAGER);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] formStackable repair skipped: ConfigManager not found ({}).",
                    DmzNpc.MODID, t.toString());
            return;
        }

        int[] fixed = {0};
        try {
            // per-race base forms: Map<race, Map<group, FormConfig>>
            Object allForms = cm.getMethod("getAllForms").invoke(null);
            repairRaceForms(allForms, fixed);

            // race-agnostic stack groups: Map<group, FormConfig>
            Object allStack = cm.getMethod("getAllStackForms").invoke(null);
            repairGroupMap(allStack, fixed);

            // server-synced mirrors (null until first sync), same shapes
            repairRaceForms(readStatic(cm, "SERVER_SYNCED_FORMS"), fixed);
            repairGroupMap(readStatic(cm, "SERVER_SYNCED_STACK_FORMS"), fixed);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] formStackable repair failed ({}); flags left as-is.",
                    DmzNpc.MODID, t.toString());
            return;
        }
        if (fixed[0] > 0) {
            DmzNpc.LOGGER.info("[{}] Repaired legacy formStackable=false on {} DMZ form(s).",
                    DmzNpc.MODID, fixed[0]);
        }
    }

    // repair a Map<race, Map<group, FormConfig>> (getAllForms / SERVER_SYNCED_FORMS)
    private static void repairRaceForms(Object raceMapObj, int[] fixed) {
        if (!(raceMapObj instanceof Map<?, ?> byRace) || byRace.isEmpty()) {
            return;
        }
        for (Object groupMap : byRace.values()) {
            repairGroupMap(groupMap, fixed);
        }
    }

    // repair a Map<group, FormConfig> (getAllStackForms / SERVER_SYNCED_STACK_FORMS / a race's group map)
    private static void repairGroupMap(Object groupMapObj, int[] fixed) {
        if (!(groupMapObj instanceof Map<?, ?> byGroup) || byGroup.isEmpty()) {
            return;
        }
        for (Object formConfig : byGroup.values()) {
            if (formConfig == null) {
                continue;
            }
            try {
                String groupName = (String) formConfig.getClass().getMethod("getGroupName").invoke(formConfig);
                Object formsObj = formConfig.getClass().getMethod("getForms").invoke(formConfig);
                if (!(formsObj instanceof Map<?, ?> forms) || forms.isEmpty()) {
                    continue;
                }
                for (Object formData : forms.values()) {
                    if (repairOneForm(groupName, formData)) {
                        fixed[0]++;
                    }
                }
            } catch (Throwable t) {
                DmzNpc.LOGGER.warn("[{}] formStackable repair: could not process a FormConfig ({}).",
                        DmzNpc.MODID, t.toString());
                // a DMZ rename would repeat per entry; one warn then bail on this map
                return;
            }
        }
    }

    // returns true iff the form was flipped false -> true
    private static boolean repairOneForm(String groupName, Object formData) {
        if (formData == null) {
            return false;
        }
        try {
            Object cur = formData.getClass().getMethod("getFormStackable").invoke(formData);
            // only touch explicitly-false; null/true are already fine
            if (!(cur instanceof Boolean b) || b) {
                return false;
            }
            String formName = (String) formData.getClass().getMethod("getName").invoke(formData);
            if (isProtectedNonStackable(groupName, formName, formData)) {
                return false;
            }
            formData.getClass().getMethod("setFormStackable", Boolean.class).invoke(formData, Boolean.TRUE);
            return true;
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] formStackable repair: could not repair a FormData ({}).",
                    DmzNpc.MODID, t.toString());
            return false;
        }
    }

    // true = leave non-stackable. ultimate protected by group/key; any non-ultimate incompatibility
    // marks a genuine special form
    private static boolean isProtectedNonStackable(String groupName, String formName, Object formData) {
        if (groupName != null && "ultimate".equalsIgnoreCase(groupName.trim())) {
            return true;
        }
        String selfKey = (groupName == null ? "" : groupName.trim()) + "."
                + (formName == null ? "" : formName.trim());
        if (ULTIMATE_KEY.equalsIgnoreCase(selfKey)) {
            return true;
        }
        try {
            Object incompatObj = formData.getClass().getMethod("getIncompatibleWith").invoke(formData);
            if (incompatObj instanceof List<?> incompat) {
                for (Object entry : incompat) {
                    if (entry == null) {
                        continue;
                    }
                    if (!ULTIMATE_KEY.equalsIgnoreCase(entry.toString().trim())) {
                        return true; // real non-ultimate incompatibility => intentional special form
                    }
                }
            }
        } catch (Throwable ignored) {
            // can't read the list => don't protect; the flip is the intended fix
        }
        return false;
    }

    // read a private static field, tolerating rename/absence (logs + returns null)
    private static Object readStatic(Class<?> owner, String fieldName) {
        try {
            Field f = owner.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(null);
        } catch (Throwable t) {
            DmzNpc.LOGGER.warn("[{}] Suppression: could not read {}.{} ({}).",
                    DmzNpc.MODID, owner.getSimpleName(), fieldName, t.toString());
            return null;
        }
    }

    private static void removeFromList(Class<?> owner, String fieldName, Set<String> ids) {
        Object v = readStatic(owner, fieldName);
        if (v instanceof List<?> list) {
            try {
                list.removeAll(ids);
            } catch (Throwable t) {
                DmzNpc.LOGGER.warn("[{}] Suppression: could not strip from list {} ({}).",
                        DmzNpc.MODID, fieldName, t.toString());
            }
        }
    }

    private static void removeKeysFromMap(Class<?> owner, String fieldName, Set<String> keys) {
        Object v = readStatic(owner, fieldName);
        if (v instanceof Map<?, ?> map && !map.isEmpty()) {
            try {
                map.keySet().removeAll(keys);
            } catch (Throwable t) {
                DmzNpc.LOGGER.warn("[{}] Suppression: could not strip keys from map {} ({}).",
                        DmzNpc.MODID, fieldName, t.toString());
            }
        }
    }

    // remove suppressed class ids from each race stats entry's classes map
    private static void stripClasses(Class<?> owner, String fieldName, Set<String> classIds) {
        Object v = readStatic(owner, fieldName);
        if (!(v instanceof Map<?, ?> statsByRace) || statsByRace.isEmpty()) {
            return;
        }
        for (Object stats : statsByRace.values()) {
            if (stats == null) {
                continue;
            }
            try {
                Object classesObj = stats.getClass().getMethod("getClasses").invoke(stats);
                if (classesObj instanceof Map<?, ?> classes && !classes.isEmpty()) {
                    classes.keySet().removeAll(classIds);
                }
            } catch (Throwable t) {
                DmzNpc.LOGGER.warn("[{}] Suppression: could not strip classes from a RaceStatsConfig ({}).",
                        DmzNpc.MODID, t.toString());
                // a DMZ rename of getClasses() would repeat this; one warn then stop
                return;
            }
        }
    }

    // Editor UI/packet entry points: mutate persistent config, then apply live
    // (reload regenerates defaults -> re-strip -> resync pushes the stripped set to clients).

    /** Suppress a default race server-wide and apply live. True if config changed. */
    public static boolean suppressRace(net.minecraft.server.MinecraftServer server, String id) {
        boolean changed = SuppressedDefaultsConfig.suppressRace(id);
        applyLive(server);
        return changed;
    }

    /** Un-suppress a default race server-wide and apply live. True if config changed. */
    public static boolean unsuppressRace(net.minecraft.server.MinecraftServer server, String id) {
        boolean changed = SuppressedDefaultsConfig.unsuppressRace(id);
        applyLive(server);
        return changed;
    }

    /** Suppress a default class server-wide and apply live. True if config changed. */
    public static boolean suppressClass(net.minecraft.server.MinecraftServer server, String id) {
        boolean changed = SuppressedDefaultsConfig.suppressClass(id);
        applyLive(server);
        return changed;
    }

    /** Un-suppress a default class server-wide and apply live. True if config changed. */
    public static boolean unsuppressClass(net.minecraft.server.MinecraftServer server, String id) {
        boolean changed = SuppressedDefaultsConfig.unsuppressClass(id);
        applyLive(server);
        return changed;
    }

    /**
     * Apply a suppression change without a restart: reload DMZ (regenerates defaults, folds in
     * applySuppression), then resync to all players (re-applies + pushes the stripped set). Un-suppress
     * works too: reload restores the default and, with the id gone from the set, it stays.
     */
    private static void applyLive(net.minecraft.server.MinecraftServer server) {
        reloadConfigs();          // ends with applySuppression()
        applySuppression();       // in case DMZ isn't loaded / reload no-oped
        resyncConfigsToAll(server); // begins with applySuppression(), then pushes to clients
    }

    // >= 1.0 multiplier from the entity's DMZ power (1.0 when unavailable/disabled); scales ki damage
    public static float powerMultiplier(LivingEntity entity) {
        if (!Config.enableDmzIntegration || entity == null) {
            return 1.0f;
        }
        ensureInit();
        if (!available) {
            return 1.0f;
        }
        try {
            Object lazy = getCapability.invoke(entity, capabilityToken);
            Object stats = resolveMethod.invoke(lazy);
            if (stats instanceof java.util.Optional<?> opt) {
                stats = opt.orElse(null);
            }
            if (stats == null || powerGetter == null) {
                return 1.0f;
            }
            Object value = powerGetter.invoke(stats);
            if (value instanceof Number n) {
                double power = n.doubleValue();
                // bounded so it never explodes: +1x per ~5000 power, capped at 5x
                return (float) Math.min(5.0, 1.0 + Math.max(0.0, power) / 5000.0);
            }
        } catch (Throwable ignored) {
            // DMZ internals changed or entity has no stats; stay neutral
        }
        return 1.0f;
    }

    private static synchronized void ensureInit() {
        if (initialised) {
            return;
        }
        initialised = true;
        if (!isLoaded()) {
            return;
        }
        try {
            Class<?> provider = Class.forName(STATS_PROVIDER);
            // the Capability<StatsData> token is a static Capability field
            for (var field : provider.getDeclaredFields()) {
                if (field.getType().getName().equals("net.minecraftforge.common.capabilities.Capability")) {
                    field.setAccessible(true);
                    capabilityToken = field.get(null);
                    break;
                }
            }
            if (capabilityToken == null) {
                return;
            }
            Class<?> capProvider = Class.forName("net.minecraftforge.common.capabilities.ICapabilityProvider");
            Class<?> capClass = Class.forName("net.minecraftforge.common.capabilities.Capability");
            getCapability = capProvider.getMethod("getCapability", capClass);
            resolveMethod = Class.forName("net.minecraftforge.common.util.LazyOptional").getMethod("resolve");

            // find a numeric power-like getter on StatsData for scaling
            Class<?> statsData = Class.forName("com.dragonminez.common.stats.StatsData");
            for (String name : new String[]{"getPowerLevel", "getRelease", "getReleasePercentage", "getMaxHealth", "getStrength"}) {
                try {
                    Method m = statsData.getMethod(name);
                    if (Number.class.isAssignableFrom(wrap(m.getReturnType()))) {
                        powerGetter = m;
                        break;
                    }
                } catch (NoSuchMethodException ignored) {
                    // try next
                }
            }
            available = true;
            DmzNpc.LOGGER.info("[{}] DMZ integration linked (stats capability found; power getter: {})",
                    DmzNpc.MODID, powerGetter != null ? powerGetter.getName() : "none");
        } catch (Throwable t) {
            available = false;
            DmzNpc.LOGGER.info("[{}] DMZ present but stats API not linked ({}); using neutral scaling.",
                    DmzNpc.MODID, t.toString());
        }
    }

    private static Class<?> wrap(Class<?> c) {
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == float.class) return Float.class;
        if (c == double.class) return Double.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        return c;
    }
}
