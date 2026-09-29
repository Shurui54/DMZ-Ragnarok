package net.shurui.shuruisutilities.protection;

/**
 * The {@code su.protection.*} permission node names. They stay in core although the Protection module (its
 * enforcement, its commands and its node registration) lives in the Ragnarok Key: core code grants and checks a few
 * of them ({@code MixinPlayerEntity}, {@code CommandBubble}, the {@code mc.commandblock} registration) and the key's
 * permission engine filters on them. Node strings are persisted in the permission files, so they never change.
 */
public final class ProtectionPerms
{
    private ProtectionPerms() {}

    public static final String BASE_PERM = "su.protection";

    public static final String PERM_PVP = BASE_PERM + ".pvp";

    public static final String PERM_SLEEP = BASE_PERM + ".sleep";
    public static final String PERM_GAMEMODE = BASE_PERM + ".gamemode";
    public static final String PERM_INVENTORY_GROUP = BASE_PERM + ".inventorygroup";

    public static final String PERM_USE = BASE_PERM + ".use";
    public static final String PERM_BREAK = BASE_PERM + ".break";
    public static final String PERM_EXPLODE = BASE_PERM + ".explode";
    // deny to make DMZ ki blasts non-destructive in an area
    public static final String PERM_KIBLAST = BASE_PERM + ".kiblast";
    public static final String PERM_PLACE = BASE_PERM + ".place";
    public static final String PERM_TRAMPLE = BASE_PERM + ".trample";
    public static final String PERM_FIRE = BASE_PERM + ".fire";
    public static final String PERM_FIRE_DESTROY = PERM_FIRE + ".destroy";
    public static final String PERM_FIRE_SPREAD = PERM_FIRE + ".spread";
    public static final String PERM_INTERACT = BASE_PERM + ".interact";
    public static final String PERM_INTERACT_ENTITY = BASE_PERM + ".interact.entity";
    public static final String PERM_DAMAGE_TO = BASE_PERM + ".damageto";
    public static final String PERM_DAMAGE_BY = BASE_PERM + ".damageby";
    public static final String PERM_INVENTORY = BASE_PERM + ".inventory";
    public static final String PERM_EXIST = BASE_PERM + ".exist";
    public static final String PERM_CRAFT = BASE_PERM + ".craft";
    public static final String PERM_EXPLOSION = BASE_PERM + ".explosion";
    public static final String PERM_NEEDSFOOD = BASE_PERM + ".needsfood";
    public static final String PERM_PRESSUREPLATE = BASE_PERM + ".pressureplate";

    public static final String PERM_MOBSPAWN = BASE_PERM + ".mobspawn";
    public static final String PERM_MOBSPAWN_NATURAL = PERM_MOBSPAWN + ".natural";
    public static final String PERM_MOBSPAWN_FORCED = PERM_MOBSPAWN + ".forced";
    /**
     * A mob a PLAYER put there on purpose: a spawn egg, a bucket, a summon item, a command.
     *
     * <p>Split out of {@link #PERM_MOBSPAWN_FORCED}, which used to cover these as well as spawners. That was the
     * cause of pets, animals, villagers and saibamen "being removed" inside a claim: an admin who denied forced
     * spawning to stop spawner farms, exactly what that permission's own description says it is for, was also
     * banning every spawn egg, bucket and summon in the same area. The mob appeared for an instant on the client
     * and was gone, which reads as something deleting it rather than as a spawn that was refused.
     *
     * <p>Registered default-ALLOW, so nothing changes for a server that has not configured it. An admin who wants
     * the old all-in-one behaviour back denies this alongside {@link #PERM_MOBSPAWN_FORCED}.
     */
    public static final String PERM_MOBSPAWN_PLACED = PERM_MOBSPAWN + ".placed";

    public static final String ZONE = BASE_PERM + ".zone";
    public static final String ZONE_KNOCKBACK = ZONE + ".knockback";
    public static final String ZONE_DAMAGE = ZONE + ".damage";
    public static final String ZONE_DAMAGE_INTERVAL = ZONE_DAMAGE + ".interval";
    public static final String ZONE_COMMAND = ZONE + ".command";
    public static final String ZONE_COMMAND_INTERVAL = ZONE_COMMAND + ".interval";
    public static final String ZONE_POTION = ZONE + ".potion";
    public static final String ZONE_POTION_INTERVAL = ZONE_POTION + ".interval";

    public static final String COMMANDBLOCK_PERM = "mc.commandblock";
}
