package net.shurui.shuruisutilities.cosmetics.form;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsCapability;
import com.dragonminez.common.stats.StatsData;
import com.dragonminez.common.stats.StatsProvider;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import net.shurui.shuruisutilities.commons.network.NetworkUtils;
import net.shurui.shuruisutilities.cosmetics.form.network.PacketFormCosmeticSync;
import net.shurui.shuruisutilities.patreon.PatreonAPI;

/**
 * Server-side authority for the single form cosmetic. Decides entitlement (through {@link PatreonAPI}, never a
 * client claim), validates the chosen form against the player's own race, stores the override in
 * {@link FormCosmeticData}, and syncs it to clients so everyone renders the same styling.
 *
 * <p>Lapse policy: a stored override is kept forever (so it returns if the player resubscribes), but it is only
 * ever put on the wire while the player is currently entitled. So a lapsed supporter can no longer edit (save is
 * refused) AND their styling stops rendering for everyone until they resubscribe. That keeps a perk from lingering
 * after support ends while never destroying the player's saved choices.
 */
public final class FormCosmeticManager
{
    private FormCosmeticManager() {}

    /** Whether the player currently qualifies for the form cosmetic (Patreon reward, grace-aware). */
    public static boolean entitled(ServerPlayer player)
    {
        return player != null && PatreonAPI.hasReward(player.getUUID(), PatreonAPI.REWARD_FORM_COSMETIC);
    }

    /** This player's stored override (a copy), or an empty untargeted one if none. */
    public static FormCosmetic current(ServerPlayer player)
    {
        MinecraftServer server = player.getServer();
        if (server == null)
            return new FormCosmetic();
        FormCosmetic c = FormCosmeticData.get(server).get(player.getUUID());
        return c == null ? new FormCosmetic() : c.copy();
    }

    /** The player's DMZ race name (lower case), or "" if they have no character. */
    public static String raceOf(ServerPlayer player)
    {
        StatsData data = StatsProvider.get(StatsCapability.INSTANCE, player).resolve().orElse(null);
        if (data == null || data.getCharacter() == null)
            return "";
        String race = data.getCharacter().getRaceName();
        return race == null ? "" : race.toLowerCase();
    }

    /**
     * The forms the player may style, as rows {@code [groupKey, formName, label]}. This is every form defined for
     * their race; the picker offers one of them as the single styled form.
     */
    public static List<List<String>> availableForms(ServerPlayer player)
    {
        List<List<String>> rows = new ArrayList<>();
        String race = raceOf(player);
        if (race.isEmpty())
            return rows;
        Map<String, FormConfig> raceForms = ConfigManager.getAllFormsForRace(race);
        if (raceForms == null)
            return rows;
        for (Map.Entry<String, FormConfig> ge : raceForms.entrySet())
        {
            String groupKey = ge.getKey();
            FormConfig group = ge.getValue();
            if (group == null || group.getForms() == null)
                continue;
            for (FormConfig.FormData fd : group.getForms().values())
            {
                if (fd == null || fd.getName() == null || fd.getName().isBlank())
                    continue;
                String name = fd.getName();
                rows.add(List.of(groupKey, name, group.getGroupName() + " / " + name));
            }
        }
        // Stack forms are targetable too: they are race-independent, and the render hook already routes them
        // through the same override (MixinDmzCharacterFormCosmetic covers getActiveStackFormData), so leaving them
        // out of this list was the only reason they could not be styled.
        Map<String, FormConfig> stack = ConfigManager.getAllStackForms();
        if (stack != null)
        {
            for (Map.Entry<String, FormConfig> ge : stack.entrySet())
            {
                FormConfig group = ge.getValue();
                if (group == null || group.getForms() == null)
                    continue;
                for (FormConfig.FormData fd : group.getForms().values())
                {
                    if (fd == null || fd.getName() == null || fd.getName().isBlank())
                        continue;
                    String name = fd.getName();
                    rows.add(List.of(ge.getKey(), name, group.getGroupName() + " / " + name));
                }
            }
        }
        return rows;
    }

    /** True when (group, form) is a real form for this player's race. Prevents styling a form they cannot have. */
    public static boolean formExists(ServerPlayer player, String group, String form)
    {
        if (group == null || form == null || group.isBlank() || form.isBlank())
            return false;
        if (ConfigManager.getForm(raceOf(player), group, form) != null)
            return true;
        // stack forms are race-independent, so they are checked against the stack table rather than the race's
        return ConfigManager.getStackForm(group, form) != null;
    }

    /**
     * Save this player's override. Re-checks entitlement server-side and validates the target form, so a client
     * that spoofs the action gains nothing. Sanitizes every field, stores it, and broadcasts to all clients.
     * Returns false (and stores nothing) when the player is not entitled or the target form is invalid.
     */
    public static boolean save(ServerPlayer player, FormCosmetic incoming)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null || incoming == null)
            return false;
        if (!entitled(player))
            return false;
        FormCosmetic clean = incoming.copy().sanitize();
        if (!clean.hasTarget() || !formExists(player, clean.group, clean.form))
            return false;
        // Id-string channels are authoritative HERE, not on the client and not in sanitize(): an id DMZ cannot
        // resolve breaks the model, so anything not in DMZ's own value set is dropped to "keep the form's value"
        // rather than stored. canonical() also normalises the spelling to however DMZ writes it.
        clean.hairType = FormAppearanceOptions.canonical(FormAppearanceOptions.Kind.HAIR_TYPE, clean.hairType);
        clean.hairCode = FormAppearanceOptions.canonical(FormAppearanceOptions.Kind.HAIR_CODE, clean.hairCode);
        // MODEL is race-scoped: a custom model is a whole body, so it is limited to the forms this player's own
        // race has. The rest stay pooled across races, which is what makes cross-race styling possible.
        clean.customModel = FormAppearanceOptions.canonicalForRace(
                FormAppearanceOptions.Kind.MODEL, raceOf(player), clean.customModel);
        clean.extraFormLayer =
                FormAppearanceOptions.canonical(FormAppearanceOptions.Kind.EXTRA_LAYER, clean.extraFormLayer);
        clean.auraType = FormAppearanceOptions.canonical(FormAppearanceOptions.Kind.AURA_TYPE, clean.auraType);
        FormCosmeticData.get(server).set(player.getUUID(), clean);
        broadcast(server, PacketFormCosmeticSync.single(player.getUUID(), clean));
        return true;
    }

    /** Clear this player's override and tell all clients to drop it. Allowed regardless of entitlement. */
    public static void clear(ServerPlayer player)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null)
            return;
        FormCosmeticData.get(server).clear(player.getUUID());
        broadcast(server, PacketFormCosmeticSync.remove(player.getUUID()));
    }

    /**
     * On login: send the joining player the full table of currently-entitled players' overrides, and tell everyone
     * about the joiner (their override if entitled, otherwise an explicit remove so no stale styling lingers).
     */
    public static void onLogin(ServerPlayer player)
    {
        MinecraftServer server = player == null ? null : player.getServer();
        if (server == null)
            return;
        FormCosmeticData data = FormCosmeticData.get(server);

        // full table to the joiner: only players who are currently entitled AND online (so we can check their reward)
        Map<UUID, FormCosmetic> all = data.all();
        Map<UUID, FormCosmetic> visible = new java.util.HashMap<>();
        for (Map.Entry<UUID, FormCosmetic> e : all.entrySet())
        {
            ServerPlayer owner = server.getPlayerList().getPlayer(e.getKey());
            if (owner != null && entitled(owner))
                visible.put(e.getKey(), e.getValue());
        }
        NetworkUtils.sendTo(PacketFormCosmeticSync.fullTable(visible), player);

        // tell everyone about the joiner
        FormCosmetic mine = data.get(player.getUUID());
        if (mine != null && entitled(player))
            broadcast(server, PacketFormCosmeticSync.single(player.getUUID(), mine));
        else
            broadcast(server, PacketFormCosmeticSync.remove(player.getUUID()));
    }

    private static void broadcast(MinecraftServer server, PacketFormCosmeticSync packet)
    {
        for (ServerPlayer p : server.getPlayerList().getPlayers())
            NetworkUtils.sendTo(packet, p);
    }
}
