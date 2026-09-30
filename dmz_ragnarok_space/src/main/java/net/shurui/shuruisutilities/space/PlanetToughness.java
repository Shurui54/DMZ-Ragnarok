package net.shurui.shuruisutilities.space;

import net.minecraft.server.MinecraftServer;

import net.shurui.shuruisutilities.guilds.GuildManager;
import net.shurui.shuruisutilities.guilds.model.Guild;

/**
 * A planet's TOUGHNESS: the ki damage the planet's answering beam clashes the incoming giant ball with. DMZ turns this
 * into the defender's clash weight ({@code ClashParticipant.statPower = max(1.0, beam.getKiDamage())}), which drives
 * both auto-press accuracy and the tug of war, so a tougher planet is genuinely harder to bust.
 *
 * <p>Two sources: a GUILD-OWNED planet uses the owning guild's strength (flat base + per-member + battle-power/divisor,
 * all config knobs on {@link PlanetBusterModule}); an UNOWNED / wild planet uses a configurable flat toughness.
 *
 * <p>The offline-guild floor (CRITICAL): {@link Guild#memberPower} refreshes only while a member is ONLINE, so an
 * all-offline guild reports {@link Guild#battlePower()} == 0 and its planet would read as almost free to bust. A
 * guild-owned planet's toughness is clamped up to a configurable MINIMUM FLOOR.
 */
public final class PlanetToughness
{
    private PlanetToughness()
    {
    }

    /** Never throws, never below 1.0, so it can go straight to {@code setupKiWave} as the damage argument. */
    public static double resolve(MinecraftServer server, String planetId)
    {
        if (server == null || planetId == null)
        {
            return Math.max(1.0, PlanetBusterModule.clashWildToughness());
        }

        // A SYSTEM SUN is the hardest target: busting it takes out its whole system, so it resists far harder than a wild
        // planet. Flat and high, on the same ki-damage scale, so only a strong developed blast can open and win its clash.
        if (GeneratedSystems.isSystemStar(planetId))
        {
            return Math.max(1.0, PlanetBusterModule.clashStarToughness());
        }

        String owningGuildId = GeneratedPlanetClaims.get(server).owner(planetId);
        if (owningGuildId == null)
        {
            // Wild planet: once populated, its VISIBLE GARRISON's toughness is authoritative and persists in
            // PlanetGarrisonData whether or not a defender is loaded. Fall back to the flat config value only for a
            // planet with no garrison record (never visited, or defenders disabled), and only if it still applies.
            PlanetGarrisonData garrison = PlanetGarrisonData.get(server);
            if (garrison.isPopulated(planetId))
            {
                return Math.max(1.0, garrison.toughness(planetId));
            }
            if (PlanetSpawnModule.wildFlatToughnessWhenDisabled())
            {
                return Math.max(1.0, PlanetBusterModule.clashWildToughness());
            }
            return 1.0;
        }

        Guild guild = GuildManager.byId(owningGuildId);
        if (guild == null)
        {
            // ownership points at a guild that no longer exists: treat as wild, not free.
            return Math.max(1.0, PlanetBusterModule.clashWildToughness());
        }

        double base = PlanetBusterModule.clashGuildToughnessBase();
        double perMember = PlanetBusterModule.clashGuildToughnessPerMember() * guild.memberCount();
        double divisor = PlanetBusterModule.clashGuildBattlePowerDivisor();
        double fromPower = divisor > 0.0 ? guild.battlePower() / divisor : 0.0;
        double toughness = base + perMember + fromPower;

        // floor guards the all-offline case (battlePower() == 0), which would otherwise be trivially bustable.
        toughness = Math.max(toughness, PlanetBusterModule.clashGuildToughnessFloor());
        return Math.max(1.0, toughness);
    }
}
