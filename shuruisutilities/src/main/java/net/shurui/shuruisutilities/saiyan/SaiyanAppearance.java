package net.shurui.shuruisutilities.saiyan;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;

/**
 * Shared appearance contract for every SU planet saiyan rendered as a DragonMineZ CUSTOM CHARACTER (a DMZ race skin),
 * regardless of what entity chassis carries it. The whole client render stack (model, body/face/tail layer, hair layer,
 * renderer) reads a saiyan's look through THIS interface, never through a concrete entity class, so the same stack draws
 * the hostile garrison saiyan ({@code PlanetSaiyanGarrisonEntity}, a {@code DBSagasEntity}), the passive town citizen
 * ({@code PlanetSaiyanCitizenEntity}, a {@code PathfinderMob}) and the town trader ({@code SaiyanTraderEntity}, a
 * {@code Villager}) with one implementation.
 *
 * <p>Colours are packed 0xRRGGBB ints (the client converts them to DMZ's float RGB). The appearance ROLL logic lives
 * here once, in {@link #roll}, so every chassis produces the same distribution of faces without duplicating it; each
 * entity only copies the rolled {@link Roll} onto its own {@link net.minecraft.network.syncher.SynchedEntityData}
 * accessors (which must be defined per class because {@code defineId} keys on the concrete class).
 */
public interface SaiyanAppearance
{
    boolean isMale();

    int getBodyType();

    int getHairId();

    int getEyesType();

    int getNoseType();

    int getMouthType();

    int getSkinColor();

    int getTailColor();

    int getHairColor();

    int getEye1Color();

    int getEye2Color();

    /** The named saiyan this entity is, or null if it is a generic custom-character saiyan. */
    NamedSaiyan getNamed();

    /** Whether this saiyan is one of the rare named NPCs (which the client renders with a real player's skin). */
    default boolean isNamed()
    {
        return getNamed() != null;
    }

    // Scouter colour ids, mapped to DragonMineZ's four scouter textures. NONE (0) means bare: the client scouter layer
    // draws nothing for it, which is what the generated-planet garrison saiyan gets since it never rolls one. The four
    // colours line up with DMZ's red/blue/green/purple scouter items and their textures/entity/races/*_scouter.png files.
    int SCOUTER_NONE = 0;
    int SCOUTER_RED = 1;
    int SCOUTER_BLUE = 2;
    int SCOUTER_GREEN = 3;
    int SCOUTER_PURPLE = 4;

    /**
     * The scouter colour id this saiyan wears, or {@link #SCOUTER_NONE} for none. The town citizen and trader override it
     * with a synced field rolled at spawn. The {@code PlanetSaiyanGarrisonEntity} also carries a synced field now, but
     * rolls it ONLY on its finalizeSpawn (spawner / egg / region) path: on the generated-planet garrison path its
     * {@code randomize} deliberately leaves it {@link #SCOUTER_NONE}, so a wild-planet garrison still shows no scouter.
     */
    default int getScouterColor()
    {
        return SCOUTER_NONE;
    }

    /** Roll one of the four scouter colours (never NONE): every town saiyan wears a scouter, colour picked at random. */
    static int rollScouterColor(RandomSource random)
    {
        return SCOUTER_RED + random.nextInt(4);
    }

    /** A forced gender for a named NPC. Every named row pins one, so gender is data here, never a special case in roll. */
    enum Gender
    {
        MALE,
        FEMALE
    }

    /**
     * The three stronger, named saiyans that ride the garrison spawn pool and wear a real player's skin. This enum is the
     * ONE place their whole look is configured: gender, hair (a DragonMineZ HAIR CODE string), hair colour, tail colour
     * and pinned armor set. Supplying a new value later is editing one row here, not touching {@link #roll} or the spawn
     * code. A null {@code armorSetPrefix} means "roll a random set like everyone else".
     *
     * <p>The hair field is a DMZ hair code string (what DMZ's own editor exports, e.g. a {@code "DMZ1:..."} or
     * {@code "DMZF1:..."} blob), NOT a 1..27 preset number. Leave it EMPTY ({@code ""}) until a real code is supplied: an
     * empty code falls back cleanly to the current preset look (see {@link #NAMED_FALLBACK_HAIR_PRESET}), so nothing looks
     * broken in the meantime. To give a named saiyan its real hair later, edit ONLY that row's code string, for example
     * change {@code ""} to {@code "DMZ1:4ZDLxq..."}; no other change is needed anywhere.
     */
    enum NamedSaiyan
    {
        // username,           gender,        hairCode (DMZ hair code; "" = fall back to preset), hairColour, tailColour, armor set prefix (null = random)
        GUILTY_REX("GuiltyRex", Gender.MALE, "", "#FFFFFF", "#FFFFFF", null),
        RAINBOW_DEMON("RainbowDemon776", Gender.MALE, "", "#25C425", "#25C425", null),
        FENRIS("Fenris_RE", Gender.FEMALE, "", "#FFFFFF", "#FFFFFF", "gine_armor");

        private final String username;
        private final Gender gender;
        private final String hairCode;
        private final String hairColorHex;
        private final String tailColorHex;
        private final String armorSetPrefix;

        NamedSaiyan(String username, Gender gender, String hairCode, String hairColorHex, String tailColorHex,
                    String armorSetPrefix)
        {
            this.username = username;
            this.gender = gender;
            this.hairCode = hairCode;
            this.hairColorHex = hairColorHex;
            this.tailColorHex = tailColorHex;
            this.armorSetPrefix = armorSetPrefix;
        }

        /** The Minecraft account name whose skin this NPC wears AND whose text sits above its head. */
        public String username()
        {
            return this.username;
        }

        /** The gender this NPC always spawns as. */
        public Gender gender()
        {
            return this.gender;
        }

        /**
         * This NPC's DragonMineZ hair code string, or empty when none is supplied yet. When empty the hair layer draws
         * the {@link #NAMED_FALLBACK_HAIR_PRESET} preset instead, so the NPC never looks broken; when set, the layer
         * parses this code and falls back to that same preset only if the code will not parse.
         */
        public String hairCode()
        {
            return this.hairCode;
        }

        /** This NPC's hair colour, as a #RRGGBB hex. */
        public String hairColorHex()
        {
            return this.hairColorHex;
        }

        /** This NPC's tail colour, as a #RRGGBB hex. */
        public String tailColorHex()
        {
            return this.tailColorHex;
        }

        /** The DragonMineZ armor set prefix pinned to this NPC (e.g. {@code gine_armor}), or null to roll a random set. */
        public String armorSetPrefix()
        {
            return this.armorSetPrefix;
        }

        /** Map a synced 1-based ordinal to a named saiyan, or null for a generic (0) or out-of-range value. */
        public static NamedSaiyan byId(int id)
        {
            NamedSaiyan[] all = values();
            return id >= 1 && id <= all.length ? all[id - 1] : null;
        }
    }

    // saiyan tail default brown, exactly DMZ's SkinGathererProvider.DEFAULT_TAIL_COLOR (#572117).
    int DEFAULT_TAIL_COLOR = 0x572117;

    // per-spawn chance (in percent) for a roll to become EACH named saiyan; the rest are generic. Kept low so a named
    // face stays a rare sight rather than the norm.
    int NAMED_CHANCE_PERCENT_EACH = 5;

    // per-spawn chance (in percent) for a MALE saiyan to be bald (hairId 0, which the hair layer skips). Females are
    // NEVER bald and always roll a real preset; only males reach this, and only when they are not a named NPC (the named
    // saiyans always keep their hair). Bald is produced ONLY here: DragonMineZ ships 27 non-empty hair presets (ids
    // 1..27), so a normal roll never yields bald on its own.
    int MALE_BALD_CHANCE_PERCENT = 30;

    // sentinel hair id meaning "bald": the hair layer draws nothing for it. Deliberately outside the 1..27 preset range.
    int HAIR_ID_BALD = 0;

    // the preset id a named saiyan's hair falls back to when its configured hair code is empty (none supplied yet) or will
    // not parse. This is exactly the preset the three named NPCs used before hair codes existed, so an empty code leaves
    // their look unchanged. Named saiyans sync THIS as their hairId; their real hair, when a code is set, comes from the
    // code in the hair layer, not from this id.
    int NAMED_FALLBACK_HAIR_PRESET = 1;

    // a palette of plausible saiyan skin tones; the bodytype texture is tinted by this as an RGB multiply. The DMZ body
    // texture is itself near white (its dominant opaque pixel is about 250,227,217), so the tint value is very close to
    // the final body colour: a pale tint on a pale texture washes out to almost white in bright light. This range is
    // therefore skewed deliberately darker, keeping only two genuinely pale tones for variety and adding mid and deep
    // browns so the population reads clearly as skin rather than washing out.
    int[] SKIN_TONES = {0xEDC0A0, 0xD9A878, 0xC68642, 0xB07A48, 0x9C6B3F, 0x8D5524, 0x6F4526, 0x543018};
    // a small palette of iris colours for the eyes. DMZ paints the two eye layers (face texture _1 and _2) as the LEFT
    // and RIGHT iris respectively, each tinted by the character's eye1Color / eye2Color; DMZ's own saiyan/human race config
    // ships those two defaults equal (#222629), so a normal saiyan has matching eyes. We roll ONE tone from this palette and
    // apply it to both, so the population never comes out with heterochromia.
    int[] IRIS_TONES = {0x3A2A1A, 0x5B3A1E, 0x2B2B2B, 0x36618E, 0x3E7D3E, 0x6B4423};

    /**
     * One rolled saiyan look, produced by {@link #roll} and applied onto an entity's synced data. A plain value record
     * so the RANDOM DECISIONS live in exactly one place and every chassis copies the same result.
     */
    record Roll(boolean male, int bodyType, int hairId, int eyesType, int noseType, int mouthType, int skinColor,
                int tailColor, int hairColor, int eye1Color, int eye2Color, int named)
    {
    }

    /**
     * Roll a whole saiyan appearance once, on the server, at spawn. Roughly 50/50 male/female, body type 1 or 2 (never
     * 0, which would be a vanilla player skin), black hair and brown tail with randomised hair style and face. When
     * {@code allowNamed} is true a small chance turns the roll into one of the three named NPCs (who override hair/tail
     * colour to their spec and are flagged so the client uses their real player skin); when false the roll is always a
     * generic custom-character saiyan (used by the trader, who must not wear a real player's skin). Deterministic given
     * the {@link RandomSource}.
     */
    static Roll roll(RandomSource random, boolean allowNamed)
    {
        int named = 0;
        if (allowNamed)
        {
            int namedRoll = random.nextInt(100);
            for (int i = 0; i < NamedSaiyan.values().length; i++)
            {
                if (namedRoll < NAMED_CHANCE_PERCENT_EACH * (i + 1))
                {
                    named = i + 1;
                    break;
                }
            }
        }

        int eyes = random.nextInt(13);
        int nose = random.nextInt(6);
        int mouth = random.nextInt(9);
        int skinColor = SKIN_TONES[random.nextInt(SKIN_TONES.length)];
        // one iris tone for BOTH eyes: layer _1 is the left iris and layer _2 the right, and DMZ's default keeps eye1 and
        // eye2 equal, so a matching pair is the correct saiyan look. Rolling a single value keeps the two eyes the same.
        int eye1 = IRIS_TONES[random.nextInt(IRIS_TONES.length)];
        int eye2 = eye1;

        NamedSaiyan namedSaiyan = NamedSaiyan.byId(named);
        boolean male;
        int bodyType;
        int hairColor;
        int tailColor;
        if (namedSaiyan != null)
        {
            // named NPCs take their WHOLE look from their one config row: their pinned gender (which drives the body geo,
            // so a female named uses the female body) and their hair/tail colour. They render a real player skin, so this
            // is the fallback look used only until (or unless) that skin resolves.
            male = namedSaiyan.gender() == Gender.MALE;
            bodyType = 1 + random.nextInt(2);
            hairColor = hexToInt(namedSaiyan.hairColorHex());
            tailColor = hexToInt(namedSaiyan.tailColorHex());
        }
        else
        {
            male = random.nextBoolean();
            bodyType = 1 + random.nextInt(2);
            hairColor = 0x000000;
            tailColor = DEFAULT_TAIL_COLOR;
        }

        // hair id LAST. A named NPC uses its configured preset id; otherwise baldness keys off the gender decided above: a
        // generic male has a MALE_BALD_CHANCE_PERCENT chance to be bald, and everyone else always rolls a real preset
        // (1..27). This is the only place bald is produced, so a female is never bald.
        int hairId;
        if (namedSaiyan != null)
        {
            // named NPCs sync the shared fallback preset as their hairId; when their row carries a real hair code the hair
            // layer uses the code instead, and only drops back to this preset if the code will not parse.
            hairId = NAMED_FALLBACK_HAIR_PRESET;
        }
        else if (male && random.nextInt(100) < MALE_BALD_CHANCE_PERCENT)
        {
            hairId = HAIR_ID_BALD;
        }
        else
        {
            hairId = 1 + random.nextInt(27);
        }
        return new Roll(male, bodyType, hairId, eyes, nose, mouth, skinColor, tailColor, hairColor, eye1, eye2, named);
    }

    /**
     * Put a named NPC's account name above its head (always visible), and ONLY a named NPC's: a generic citizen, trader
     * or garrison saiyan is left with no custom name, so the town is not a wall of floating text. Server-side; the custom
     * name syncs and persists on its own. Call once at spawn after the appearance is rolled. A no-op for a generic saiyan.
     */
    static void applyNameTag(Mob mob, SaiyanAppearance appearance)
    {
        NamedSaiyan named = appearance.getNamed();
        if (named != null)
        {
            // yellow so a named saiyan reads as special and stands out in a crowd of unnamed ones. A styled Component, not
            // a section-sign code in the string, so it colours correctly everywhere and never leaves a literal code visible
            // somewhere that does not parse legacy formatting.
            mob.setCustomName(Component.literal(named.username()).withStyle(ChatFormatting.YELLOW));
            mob.setCustomNameVisible(true);
        }
    }

    static int hexToInt(String hex)
    {
        try
        {
            String h = hex.startsWith("#") ? hex.substring(1) : hex;
            return (int) (Long.parseLong(h, 16) & 0xFFFFFF);
        }
        catch (NumberFormatException ex)
        {
            return 0xFFFFFF;
        }
    }
}
