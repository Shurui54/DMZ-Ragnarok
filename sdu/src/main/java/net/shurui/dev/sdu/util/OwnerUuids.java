package net.shurui.dev.sdu.util;

import java.util.Set;
import java.util.UUID;

/**
 * The two player UUIDs that own Shurui's bespoke admin content. Currently this list only drives the
 * Shurui's Armor wearer-lock ({@code ShuruisArmorHandler}); it is NOT wired into the Hakai technique.
 * Hakai's gate is {@code hasPermissions(2)}, enforced at grant time ({@code DmzTechniques.grant}) and
 * again at cast time ({@code TechniqueDispatcherMixin} / {@code HakaiSequence}).
 *
 * @see net.shurui.dev.sdu.compat.DmzTechniques#grant
 */
public final class OwnerUuids {

    /** The only players allowed to use Shurui's bespoke admin content. */
    public static final Set<UUID> OWNERS = Set.of(
            UUID.fromString("a53f8095-df8a-4adb-9b9b-ed65670a5113"),
            UUID.fromString("3db01403-b235-491a-81f1-8e7ec8ebcdea"));

    private OwnerUuids() {
    }

    /** True if the given UUID is an owner (null-safe). */
    public static boolean contains(UUID uuid) {
        return uuid != null && OWNERS.contains(uuid);
    }
}
