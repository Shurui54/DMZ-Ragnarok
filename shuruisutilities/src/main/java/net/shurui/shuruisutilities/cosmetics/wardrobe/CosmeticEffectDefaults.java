package net.shurui.shuruisutilities.cosmetics.wardrobe;

import java.util.ArrayList;
import java.util.List;

/**
 * The eighteen shipped MAGIC effects and the six pools they are arranged into.
 *
 * <p>SEED CONTENT, not a registry. {@link CosmeticCatalog#load()} puts these into a catalogue that has never
 * held an effect, once, and from that moment they are ordinary admin-owned records: editable, deletable,
 * stamped and synced like anything an admin authored by hand. Nothing here is consulted again afterwards, and
 * changing a number in this file does NOT change a live server's copy. That is the point: the feature ships
 * with content instead of an empty editor, and an admin's edit is never overwritten by a jar update.
 *
 * <p>Deleting all of them sticks. See {@link CosmeticCatalog}'s seeding note: the guard is the tombstones, not
 * the map, so a deliberate wipe is not undone by the next restart.
 *
 * <h2>What the numbers mean, and which of them are real</h2>
 * Every rate is authored at {@link CosmeticEffect#density} 1.0, so an operator thins the whole set with one
 * dial. {@code lifetimeTicks} is DESCRIPTIVE: no vanilla particle takes a lifetime through the spawn call, and
 * for the dust types the lifetime is welded to the scale inside the particle class. {@code colorPrimary} and
 * {@code colorSecondary} are only READ for {@code dust} and {@code dust_color_transition}; on the rest they are
 * the editor's swatch, because vanilla flame is always orange and vanilla portal is always purple.
 *
 * <p>The {@code note} params are authoring notes kept on purpose. They are the reason a rate is what it is
 * (why {@code soul} is one in six rather than one in two, why {@code electric_spark} needs a dust behind it to
 * form a line at all), and an admin retuning one of these in the editor needs that sentence more than the
 * bytes cost on the wire.
 *
 * <p>Six pools of three, weighted 60 / 30 / 10 over a subtle, a standard and a showpiece effect. The weights
 * are relative, so they do not have to sum to 100; they are written that way so the published odds screen reads
 * as percentages with no arithmetic.
 */
public final class CosmeticEffectDefaults
{
    private CosmeticEffectDefaults()
    {
    }

    /** Fresh instances every call. The catalogue takes ownership of what it is handed. */
    public static List<CosmeticEffect> effects()
    {
        List<CosmeticEffect> out = new ArrayList<>();
        out.add(fx("ember_drift", "&6Ember Drift", "subtle",
                "minecraft:small_flame", "#FF9A2E", "#3A3A3A",
                1.0F, 1.0F, 28, "minecraft:block.fire.ambient", 0.18F, 1.0F)
                .p("particle2", "minecraft:smoke")
                .p("particle2Every", "8")
                .p("motionX", "0.0")
                .p("motionY", "0.006")
                .p("motionZ", "0.0")
                .p("spread", "0.05")
                .p("halo.rate", "4.0")
                .p("halo.radius", "0.28")
                .p("halo.orbitTicks", "80")
                .p("halo.yOffset", "0.10")
                .p("trail.spacing", "0.75")
                .p("trail.jitter", "0.12")
                .p("trail.yOffset", "1.00")
                .p("sound.everyTicks", "60")
                .p("budget.liveEstimate", "4.4")
                .p("crowd.haloRate", "3.0")
                .p("crowd.spacing", "1.00")
                .done());

        out.add(fx("frostline", "&bFrostline", "subtle",
                "minecraft:snowflake", "#EBF6FF", "#BFE9FF",
                1.0F, 1.0F, 52, "minecraft:block.snow.step", 0.12F, 1.2F)
                .p("particle2", "minecraft:dust")
                .p("particle2Every", "3")
                .p("color2Scale", "0.6")
                .p("motionX", "0.0")
                .p("motionY", "0.0")
                .p("motionZ", "0.0")
                .p("spread", "0.10")
                .p("halo.rate", "5.0")
                .p("halo.radius", "0.30")
                .p("halo.orbitTicks", "0")
                .p("halo.yOffset", "0.35")
                .p("trail.spacing", "0.60")
                .p("trail.jitter", "0.10")
                .p("trail.yOffset", "1.20")
                .p("sound.everyTicks", "90")
                .p("budget.liveEstimate", "10.0")
                .p("note", "snowflake has gravity 0.225 and friction 1.0, it FALLS. halo.orbitTicks is 0 on "
                        + "purpose.")
                .p("crowd.haloRate", "3.5")
                .p("crowd.spacing", "0.85")
                .done());

        out.add(fx("quiet_static", "&dQuiet Static", "subtle",
                "minecraft:electric_spark", "#FFE6FF", "#EAD9FF",
                1.0F, 1.0F, 3, "minecraft:block.sculk.charge", 0.1F, 1.5F)
                .p("particle2", "minecraft:dust")
                .p("particle2Every", "3")
                .p("color2Scale", "0.55")
                .p("motionX", "0.35")
                .p("motionY", "0.10")
                .p("motionZ", "0.35")
                .p("spread", "0.08")
                .p("halo.rate", "6.0")
                .p("halo.radius", "0.22")
                .p("halo.orbitTicks", "45")
                .p("halo.yOffset", "0.05")
                .p("trail.spacing", "0.45")
                .p("trail.jitter", "0.14")
                .p("trail.yOffset", "1.05")
                .p("sound.everyTicks", "100")
                .p("budget.liveEstimate", "1.4")
                .p("note", "electric_spark lives 2 to 4 ticks and never forms a line alone. The 1-in-3 dust IS the "
                        + "trail.")
                .p("crowd.haloRate", "4.0")
                .p("crowd.spacing", "0.60")
                .done());

        out.add(fx("petalfall", "&dPetalfall", "subtle",
                "minecraft:cherry_leaves", "#F7BFD6", "#F7BFD6",
                1.0F, 1.0F, 300, "", 0.0F, 1.0F)
                .p("motionX", "0.0")
                .p("motionY", "0.0")
                .p("motionZ", "0.0")
                .p("spread", "0.18")
                .p("halo.rate", "2.0")
                .p("halo.radius", "0.34")
                .p("halo.orbitTicks", "120")
                .p("halo.yOffset", "0.20")
                .p("trail.spacing", "1.40")
                .p("trail.jitter", "0.30")
                .p("trail.yOffset", "1.30")
                .p("sound.everyTicks", "0")
                .p("budget.liveEstimate", "30.0")
                .p("note", "300-tick fixed lifetime, 15 seconds. Rates are low ON PURPOSE. Do not raise them.")
                .p("crowd.haloRate", "1.0")
                .p("crowd.spacing", "2.60")
                .done());

        out.add(fx("lanternlight", "&eLanternlight", "subtle",
                "minecraft:dust", "#FFCC66", "#FFF3D6",
                1.0F, 1.2F, 29, "minecraft:block.candle.ambient", 0.15F, 1.0F)
                .p("particle2", "minecraft:end_rod")
                .p("particle2Every", "10")
                .p("motionX", "0.0")
                .p("motionY", "0.004")
                .p("motionZ", "0.0")
                .p("spread", "0.08")
                .p("halo.rate", "6.0")
                .p("halo.radius", "0.26")
                .p("halo.orbitTicks", "90")
                .p("halo.yOffset", "0.12")
                .p("trail.spacing", "0.90")
                .p("trail.jitter", "0.16")
                .p("trail.yOffset", "1.10")
                .p("sound.everyTicks", "70")
                .p("budget.liveEstimate", "8.7")
                .p("note", "dust renders at 48 to 100 percent of colorPrimary. #FFCC66 is authored bright for "
                        + "that.")
                .p("crowd.haloRate", "4.0")
                .p("crowd.spacing", "1.20")
                .done());

        out.add(fx("chorus", "&aChorus", "subtle",
                "minecraft:note", "#FFFFFF", "#FFFFFF",
                1.0F, 1.0F, 6, "minecraft:block.note_block.chime", 0.25F, 1.0F)
                .p("motionX", "hue")
                .p("motionY", "0.0")
                .p("motionZ", "0.0")
                .p("hue.sweepTicks", "120")
                .p("spread", "0.10")
                .p("halo.rate", "5.0")
                .p("halo.radius", "0.24")
                .p("halo.orbitTicks", "60")
                .p("halo.yOffset", "0.25")
                .p("trail.spacing", "0.50")
                .p("trail.jitter", "0.18")
                .p("trail.yOffset", "1.15")
                .p("sound.everyTicks", "24")
                .p("sound.pitchSteps", "0.94,1.06,1.19,1.33,1.50")
                .p("budget.liveEstimate", "1.5")
                .p("note", "NOTE takes a HUE in the first double, not RGB. 6-tick lifetime: a dotted line, never "
                        + "continuous.")
                .p("crowd.haloRate", "4.0")
                .p("crowd.spacing", "0.70")
                .p("crowd.soundEveryTicks", "60")
                .done());

        out.add(fx("emberstorm", "&cEmberstorm", "standard",
                "minecraft:flame", "#FF7A18", "#3A3A3A",
                1.0F, 1.0F, 28, "minecraft:block.fire.ambient", 0.3F, 0.9F)
                .p("particle2", "minecraft:small_flame")
                .p("particle2Every", "2")
                .p("particle3", "minecraft:smoke")
                .p("particle3Every", "10")
                .p("motionX", "0.0")
                .p("motionY", "0.022")
                .p("motionZ", "0.0")
                .p("spread", "0.11")
                .p("halo.rate", "10.0")
                .p("halo.radius", "0.30")
                .p("halo.orbitTicks", "60")
                .p("halo.yOffset", "0.14")
                .p("trail.spacing", "0.50")
                .p("trail.jitter", "0.20")
                .p("trail.yOffset", "1.05")
                .p("sound.everyTicks", "40")
                .p("budget.liveEstimate", "11.0")
                .p("crowd.haloRate", "6.0")
                .p("crowd.spacing", "0.80")
                .p("crowd.particle2Every", "4")
                .done());

        out.add(fx("soulfire_wake", "&bSoulfire Wake", "standard",
                "minecraft:soul_fire_flame", "#3FD9E8", "#7FF0FF",
                1.0F, 1.0F, 28, "minecraft:block.soul_sand.step", 0.2F, 0.7F)
                .p("particle2", "minecraft:soul")
                .p("particle2Every", "6")
                .p("motionX", "0.0")
                .p("motionY", "0.018")
                .p("motionZ", "0.0")
                .p("spread", "0.10")
                .p("halo.rate", "9.0")
                .p("halo.radius", "0.30")
                .p("halo.orbitTicks", "70")
                .p("halo.yOffset", "0.14")
                .p("trail.spacing", "0.50")
                .p("trail.jitter", "0.18")
                .p("trail.yOffset", "1.05")
                .p("sound.everyTicks", "50")
                .p("budget.liveEstimate", "10.0")
                .p("note", "soul calls scale(1.5) on itself, hence 1 in 6 rather than 1 in 2. Matched pair with "
                        + "emberstorm.")
                .p("crowd.haloRate", "5.5")
                .p("crowd.spacing", "0.80")
                .p("crowd.particle2Every", "10")
                .done());

        out.add(fx("runebound", "&9Runebound", "standard",
                "minecraft:enchant", "#E6ECFF", "#CFE3FF",
                1.0F, 0.7F, 35, "minecraft:block.amethyst_block.chime", 0.2F, 1.3F)
                .p("particle2", "minecraft:dust")
                .p("particle2Every", "5")
                .p("color2Scale", "0.7")
                .p("motionMode", "converge")
                .p("convergeRadius", "0.90")
                .p("spread", "0.0")
                .p("halo.rate", "8.0")
                .p("halo.radius", "0.0")
                .p("halo.orbitTicks", "0")
                .p("halo.yOffset", "0.15")
                .p("trail.spacing", "0.70")
                .p("trail.jitter", "0.10")
                .p("trail.yOffset", "1.15")
                .p("sound.everyTicks", "90")
                .p("budget.liveEstimate", "14.0")
                .p("note", "enchant reads its three doubles as an INWARD OFFSET, not a velocity. It flies into the "
                        + "anchor.")
                .p("crowd.haloRate", "5.0")
                .p("crowd.spacing", "1.00")
                .o("body", "convergeRadius", "0.25")
                .o("mount", "convergeRadius", "0.25")
                .done());

        out.add(fx("tidecaller", "&3Tidecaller", "standard",
                "minecraft:dust_color_transition", "#1E4FD8", "#7FE9FF",
                1.0F, 1.1F, 26, "minecraft:block.conduit.ambient", 0.12F, 1.1F)
                .p("particle2", "minecraft:glow")
                .p("particle2Every", "6")
                .p("motionX", "0.0")
                .p("motionY", "0.003")
                .p("motionZ", "0.0")
                .p("spread", "0.12")
                .p("halo.rate", "7.0")
                .p("halo.radius", "0.32")
                .p("halo.orbitTicks", "100")
                .p("halo.yOffset", "0.10")
                .p("trail.spacing", "0.65")
                .p("trail.jitter", "0.20")
                .p("trail.yOffset", "1.05")
                .p("sound.everyTicks", "100")
                .p("budget.liveEstimate", "9.0")
                .p("note", "NO water particles. bubble, underwater, nautilus and dolphin all delete themselves out "
                        + "of water.")
                .p("crowd.haloRate", "4.5")
                .p("crowd.spacing", "0.90")
                .done());

        out.add(fx("verdant_bloom", "&aVerdant Bloom", "standard",
                "minecraft:dust", "#6FE85A", "#B8FFA8",
                1.0F, 0.9F, 21, "minecraft:block.chorus_flower.grow", 0.14F, 1.4F)
                .p("particle2", "minecraft:happy_villager")
                .p("particle2Every", "5")
                .p("motionX", "0.0")
                .p("motionY", "0.002")
                .p("motionZ", "0.0")
                .p("spread", "0.13")
                .p("halo.rate", "7.0")
                .p("halo.radius", "0.30")
                .p("halo.orbitTicks", "110")
                .p("halo.yOffset", "0.08")
                .p("trail.spacing", "0.70")
                .p("trail.jitter", "0.22")
                .p("trail.yOffset", "1.00")
                .p("sound.everyTicks", "110")
                .p("budget.liveEstimate", "9.4")
                .p("note", "spore_blossom_air deliberately excluded: 500 to 1000 tick lifetime.")
                .p("crowd.haloRate", "4.5")
                .p("crowd.spacing", "0.95")
                .done());

        out.add(fx("wraithlight", "&5Wraithlight", "standard",
                "minecraft:dust", "#8B6BE8", "#5FE0D0",
                1.0F, 0.85F, 20, "minecraft:block.sculk.charge", 0.15F, 0.8F)
                .p("particle2", "minecraft:sculk_soul")
                .p("particle2Every", "6")
                .p("motionX", "0.0")
                .p("motionY", "0.010")
                .p("motionZ", "0.0")
                .p("spread", "0.09")
                .p("halo.rate", "7.0")
                .p("halo.radius", "0.27")
                .p("halo.orbitTicks", "85")
                .p("halo.yOffset", "0.18")
                .p("trail.spacing", "0.65")
                .p("trail.jitter", "0.16")
                .p("trail.yOffset", "1.20")
                .p("sound.everyTicks", "80")
                .p("budget.liveEstimate", "10.0")
                .p("crowd.haloRate", "4.5")
                .p("crowd.spacing", "0.90")
                .done());

        out.add(fx("starfall", "&eStarfall", "showpiece",
                "minecraft:end_rod", "#FFF6DC", "#FFF0B3",
                1.0F, 0.7F, 66, "minecraft:block.amethyst_block.chime", 0.25F, 1.6F)
                .p("particle2", "minecraft:dust")
                .p("particle2Every", "2")
                .p("color2Scale", "0.7")
                .p("particle3", "minecraft:firework")
                .p("particle3Every", "6")
                .p("motionX", "0.006")
                .p("motionY", "0.012")
                .p("motionZ", "0.006")
                .p("spread", "0.14")
                .p("halo.rate", "8.0")
                .p("halo.radius", "0.45")
                .p("halo.orbitTicks", "100")
                .p("halo.yOffset", "0.22")
                .p("trail.spacing", "0.55")
                .p("trail.jitter", "0.24")
                .p("trail.yOffset", "1.10")
                .p("sound.everyTicks", "45")
                .p("budget.liveEstimate", "17.0")
                .p("note", "firework cannot be coloured through addParticle, the fireworks ENTITY calls setColor "
                        + "directly.")
                .p("crowd.haloRate", "4.0")
                .p("crowd.spacing", "0.90")
                .p("crowd.particle3Every", "12")
                .done());

        out.add(fx("riftwalk", "&5Riftwalk", "showpiece",
                "minecraft:portal", "#B14DFF", "#3B1470",
                1.0F, 1.0F, 45, "minecraft:block.portal.ambient", 0.12F, 0.8F)
                .p("particle2", "minecraft:reverse_portal")
                .p("particle2Every", "4")
                .p("particle3", "minecraft:dust_color_transition")
                .p("particle3Every", "3")
                .p("motionX", "0.35")
                .p("motionY", "0.0")
                .p("motionZ", "0.35")
                .p("spread", "0.14")
                .p("halo.rate", "9.0")
                .p("halo.radius", "0.36")
                .p("halo.orbitTicks", "-75")
                .p("halo.yOffset", "0.10")
                .p("trail.spacing", "0.50")
                .p("trail.jitter", "0.26")
                .p("trail.yOffset", "1.05")
                .p("sound.everyTicks", "80")
                .p("budget.liveEstimate", "22.0")
                .p("note", "portal is ALWAYS purple (rCol = f*0.9, gCol = 0, bCol = f) and drifts BACK toward its "
                        + "spawn point.")
                .p("crowd.haloRate", "5.0")
                .p("crowd.spacing", "0.90")
                .p("crowd.particle2Every", "8")
                .done());

        out.add(fx("sovereign", "&6Sovereign", "showpiece",
                "minecraft:totem_of_undying", "#FFD24A", "#9CE86B",
                1.0F, 1.6F, 66, "minecraft:block.beacon.ambient", 0.2F, 1.0F)
                .p("particle2", "minecraft:dust")
                .p("particle2Every", "2")
                .p("color2Scale", "1.6")
                .p("particle3", "minecraft:enchant")
                .p("particle3Every", "8")
                .p("particle3ConvergeRadius", "0.60")
                .p("motionX", "0.0")
                .p("motionY", "0.014")
                .p("motionZ", "0.0")
                .p("spread", "0.12")
                .p("halo.rate", "12.0")
                .p("halo.radius", "0.35")
                .p("halo.orbitTicks", "70")
                .p("halo.ring2Radius", "0.55")
                .p("halo.ring2OrbitTicks", "-90")
                .p("halo.yOffset", "0.18")
                .p("trail.spacing", "0.45")
                .p("trail.jitter", "0.30")
                .p("trail.yOffset", "1.15")
                .p("sound.everyTicks", "100")
                .p("sound.onEquip", "minecraft:block.bell.resonate")
                .p("sound.onEquipVolume", "0.30")
                .p("budget.liveEstimate", "35.0")
                .p("note", "HIGHEST COST IN THE CATALOGUE. The crowd variant is mandatory, not optional.")
                .p("crowd.haloRate", "6.0")
                .p("crowd.spacing", "0.85")
                .p("crowd.ring2", "off")
                .p("crowd.particle3Every", "0")
                .done());

        out.add(fx("dragons_wake", "&dDragon's Wake", "showpiece",
                "minecraft:dragon_breath", "#FF4FD8", "#C81FA0",
                1.0F, 1.0F, 50, "minecraft:block.respawn_anchor.ambient", 0.15F, 0.9F)
                .p("particle2", "minecraft:dust")
                .p("particle2Every", "4")
                .p("color2Scale", "1.0")
                .p("motionX", "0.0")
                .p("motionY", "0.0")
                .p("motionZ", "0.0")
                .p("spread", "0.12")
                .p("halo.rate", "5.0")
                .p("halo.radius", "0.30")
                .p("halo.orbitTicks", "90")
                .p("halo.yOffset", "0.10")
                .p("trail.spacing", "0.70")
                .p("trail.jitter", "0.20")
                .p("trail.yOffset", "0.45")
                .p("sound.everyTicks", "90")
                .p("budget.liveEstimate", "12.5")
                .p("note", "dragon_breath has PHYSICS and spreads on the floor. Great trail, needs lift on a hat.")
                .p("crowd.haloRate", "3.0")
                .p("crowd.spacing", "1.30")
                .o("head", "motionY", "0.030")
                .o("back", "motionY", "0.030")
                .o("ki_weapon", "motionY", "0.030")
                .done());

        out.add(fx("eclipse", "&8Eclipse", "showpiece",
                "minecraft:dust", "#5B3BA8", "#D6B8FF",
                1.0F, 2.2F, 53, "minecraft:ambient.cave", 0.1F, 0.6F)
                .p("particle2", "minecraft:dust")
                .p("particle2Every", "3")
                .p("color2Scale", "0.5")
                .p("particle3", "minecraft:squid_ink")
                .p("particle3Every", "5")
                .p("motionX", "0.0")
                .p("motionY", "-0.002")
                .p("motionZ", "0.0")
                .p("spread", "0.16")
                .p("halo.rate", "6.0")
                .p("halo.radius", "0.38")
                .p("halo.orbitTicks", "130")
                .p("halo.yOffset", "0.12")
                .p("trail.spacing", "0.70")
                .p("trail.jitter", "0.28")
                .p("trail.yOffset", "1.10")
                .p("sound.everyTicks", "200")
                .p("budget.liveEstimate", "16.0")
                .p("note", "Dark effect: reads against sky, not against stone or at night. Crowd lever is SCALE, "
                        + "not rate.")
                .p("crowd.scale", "1.6")
                .p("crowd.haloRate", "4.0")
                .p("crowd.spacing", "1.00")
                .p("crowd.particle3Every", "0")
                .done());

        out.add(fx("heat_mirage", "&cHeat Mirage", "showpiece",
                "minecraft:dust_color_transition", "#FF9A00", "#FF2B2B",
                1.0F, 1.4F, 33, "minecraft:entity.blaze.ambient", 0.1F, 1.4F)
                .p("particle2", "minecraft:large_smoke")
                .p("particle2Every", "8")
                .p("motionX", "0.0")
                .p("motionY", "0.016")
                .p("motionZ", "0.0")
                .p("spread", "0.15")
                .p("halo.rate", "6.0")
                .p("halo.radius", "0.33")
                .p("halo.orbitTicks", "55")
                .p("halo.yOffset", "0.16")
                .p("trail.spacing", "0.85")
                .p("trail.jitter", "0.24")
                .p("trail.yOffset", "1.05")
                .p("sound.everyTicks", "120")
                .p("budget.liveEstimate", "10.5")
                .p("note", "large_smoke is a 2.5x quad AND a 2.5x lifetime, up to 100 ticks. Fill rate, not count.")
                .p("crowd.scale", "1.1")
                .p("crowd.spacing", "1.10")
                .p("crowd.particle2Every", "16")
                .done());

        return out;
    }

    /** Fresh instances every call. */
    public static List<CosmeticEffectPool> pools()
    {
        List<CosmeticEffectPool> out = new ArrayList<>();
        // The starter / always-on crate. All fire, all warm, nothing needs explaining.
        out.add(pool("pool_hearthlight", "&6Hearthlight",
                "ember_drift", 60,
                "emberstorm", 30,
                "heat_mirage", 10));

        // The standard mid-tier crate. Broadest appeal, no slot overrides in it. Build this second.
        out.add(pool("pool_tidal", "&3Tidal",
                "lanternlight", 60,
                "tidecaller", 30,
                "starfall", 10));

        // The premium / event crate. Holds sovereign, the loudest effect in the catalogue.
        out.add(pool("pool_arcane", "&9Arcane",
                "quiet_static", 60,
                "runebound", 30,
                "sovereign", 10));

        // A winter window crate. Pairs against Hearthlight so players have a warm set and a cold set.
        out.add(pool("pool_coldlight", "&bColdlight",
                "frostline", 60,
                "soulfire_wake", 30,
                "riftwalk", 10));

        // A spring window crate. The showpiece pools on the ground, which suits an outdoor event.
        out.add(pool("pool_wildgrowth", "&aWildgrowth",
                "petalfall", 60,
                "verdant_bloom", 30,
                "dragons_wake", 10));

        // A Halloween window crate. Note eclipse reads poorly at night, which fights the theme.
        out.add(pool("pool_hollow", "&5Hollow",
                "chorus", 60,
                "wraithlight", 30,
                "eclipse", 10));

        return out;
    }

    /**
     * One effect, as its identity fields. The {@code .p(...)} and {@code .o(...)} calls chained onto the result
     * are its {@link CosmeticEffect#params} and its per-slot overrides, written through the tiny builder below
     * rather than as map literals so that a mistyped pair is a compile error instead of a silently odd-length
     * array, and so the table above reads in the same order as the authored catalogue.
     */
    private static Builder fx(String id, String displayName, String rarityLabel, String particle,
            String colorPrimary, String colorSecondary, float density, float scale, int lifetimeTicks,
            String sound, float soundVolume, float soundPitch)
    {
        CosmeticEffect e = new CosmeticEffect(id);
        e.displayName = displayName;
        e.rarityLabel = rarityLabel;
        e.particle = particle;
        e.colorPrimary = colorPrimary;
        e.colorSecondary = colorSecondary;
        e.density = density;
        e.scale = scale;
        e.lifetimeTicks = lifetimeTicks;
        e.sound = sound;
        e.soundVolume = soundVolume;
        e.soundPitch = soundPitch;
        return new Builder(e);
    }

    /** A pool, as an id, a display name and then effect id / weight pairs in authored order. */
    private static CosmeticEffectPool pool(String id, String displayName, Object... entries)
    {
        CosmeticEffectPool p = new CosmeticEffectPool(id);
        p.displayName = displayName;
        for (int i = 0; i + 1 < entries.length; i += 2)
            p.put((String) entries[i], (Integer) entries[i + 1]);
        return p.normalise();
    }

    /** Small builder so the table above reads as data. Nothing outside this class uses it. */
    private static final class Builder
    {
        private final CosmeticEffect effect;

        private Builder(CosmeticEffect effect)
        {
            this.effect = effect;
        }

        private Builder p(String key, String value)
        {
            effect.setParam(key, value);
            return this;
        }

        private Builder o(String slotKey, String key, String value)
        {
            effect.setSlotOverride(slotKey, key, value);
            return this;
        }

        private CosmeticEffect done()
        {
            return effect.normalise();
        }
    }
}
