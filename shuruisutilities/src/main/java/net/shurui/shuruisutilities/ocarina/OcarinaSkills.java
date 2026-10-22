package net.shurui.shuruisutilities.ocarina;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.dragonminez.common.config.SkillsConfig;

import net.shurui.shuruisutilities.api.key.OcarinaHooks;

/**
 * Puts the ocarina songs into DragonMineZ's own skill system, so they are bought and levelled exactly like a ki
 * attack: each song is an entry in {@code SkillsConfig.skills} (its TP price per level) and Tapion is an entry in
 * {@code skillOfferings}, which is the list DMZ's master screen sells and {@code UpdateSkillC2S} checks a purchase
 * against (a master who offers the skill must be within 10 blocks).
 *
 * <p>Applied after every DMZ config load (a mixin on {@code ConfigManager}) and again whenever the key's config is
 * baked. Only in memory: the entries are added after DMZ has saved skills.json, so the owner's file never carries
 * them. Keyless they are taken out again, so the songs and Tapion's list are absent, never just unbuyable. Clients
 * get them through DMZ's normal config sync (the server's skills.json is sent as JSON).
 */
public final class OcarinaSkills
{
    private OcarinaSkills() {}

    /** Tapion's npc id: his QuestNPCEntity id, his master name and his skillOfferings key. */
    public static final String TAPION = "tapion";

    /** Apply (keyed) or strip (keyless) the songs and Tapion's offering on the live config. Never throws. */
    public static synchronized void apply(SkillsConfig cfg)
    {
        if (cfg == null)
            return;
        try
        {
            Map<String, SkillsConfig.SkillCosts> skills = cfg.getSkills();
            Map<String, List<String>> offerings = cfg.getSkillOfferings();
            if (!OcarinaHooks.available())
            {
                for (OcarinaSong s : OcarinaSong.values())
                    skills.remove(s.skillId);
                offerings.remove(TAPION);
                return;
            }
            List<String> sells = new ArrayList<>();
            for (OcarinaSong s : OcarinaSong.values())
            {
                List<Integer> costs = new ArrayList<>(OcarinaTuning.song(s).costs());
                if (costs.isEmpty())
                    costs.add(1000);
                skills.put(s.skillId, new SkillsConfig.SkillCosts(costs, new ArrayList<>()));
                sells.add(s.skillId);
            }
            for (String extra : OcarinaTuning.tapionExtraSkills())
            {
                String id = extra == null ? "" : extra.trim().toLowerCase(java.util.Locale.ROOT);
                // Only skills DMZ really has (a typo would be a dead row in the shop).
                if (!id.isEmpty() && !sells.contains(id) && (skills.containsKey(id) || has(cfg.getKiSkills(), id)
                        || has(cfg.getStrikeSkills(), id) || has(cfg.getEvasionSkills(), id)))
                    sells.add(id);
            }
            offerings.put(TAPION, sells);
        }
        catch (Throwable ignored)
        {
            // A DMZ build with a different config shape: the songs are simply not for sale.
        }
    }

    private static boolean has(List<String> list, String id)
    {
        return list != null && list.contains(id);
    }

    /** Re-apply on the live server config and make DMZ rebuild what it sends to clients. */
    public static void reapply()
    {
        try
        {
            Class<?> cm = Class.forName("com.dragonminez.common.config.ConfigManager");
            java.lang.reflect.Field f = cm.getDeclaredField("skillsConfig");
            f.setAccessible(true);
            Object live = f.get(null);
            if (live instanceof SkillsConfig cfg)
                apply(cfg);
            java.lang.reflect.Field cache = cm.getDeclaredField("cachedSyncPayloads");
            cache.setAccessible(true);
            cache.set(null, null);
        }
        catch (Throwable ignored)
        {
        }
    }
}
