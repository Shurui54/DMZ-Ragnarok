package net.shurui.shuruisutilities.permissions.forge;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.permission.handler.IPermissionHandler;
import net.minecraftforge.server.permission.nodes.PermissionDynamicContext;
import net.minecraftforge.server.permission.nodes.PermissionNode;
import net.minecraftforge.server.permission.nodes.PermissionTypes;

import net.shurui.shuruisutilities.api.APIRegistry;
import net.shurui.shuruisutilities.api.UserIdent;
import net.shurui.shuruisutilities.core.ShuruisUtilities;
import net.shurui.shuruisutilities.util.output.logger.LoggingHandler;

// Forge IPermissionHandler bridging Forge's PermissionAPI onto SU's own permission system.
// some mods (CustomNPCs is the motivating case) gate features via PermissionAPI.getPermission() instead of
// consulting SU directly. with Forge's default handler active those queries fall through to each node's
// default resolver, which is op-only for admin nodes, so an SU grant to a non-op group never reaches them.
// this handler forwards BOOLEAN queries to checkUserPermission(userIdent, node) so an SU grant is honored.
// for any non-boolean node, or when SU has no player context, it returns the node's own default resolver
// value so we never break other mods' int/string/component nodes or their intended defaults.
// registered via PermissionGatherEvent.Handler (see PermissionsBootstrap). only SELECTED at runtime when
// forge-server.toml [server] permissionHandler is set to this handler's IDENTIFIER; registering just makes
// it available for that config to pick.
public final class SuForgePermissionHandler implements IPermissionHandler
{
    // forge-server.toml [server] permissionHandler must equal this to activate SU's bridge. Pinned to the LITERAL
    // "shuruisutilities" (not ShuruisUtilities.MODID): this id is stored verbatim in the server's forge-server.toml, so
    // letting it follow MODID into dmz_ragnarok would silently deselect SU's permission handler on every existing
    // server until an admin edited that config. Kept stable exactly like the pinned config filenames, SU_DIRECTORY and
    // PERM. The dmz_ragnarok config-id stage can revisit it deliberately with an accompanying config migration note.
    public static final ResourceLocation IDENTIFIER = new ResourceLocation("shuruisutilities", "permission_handler");

    private final Set<PermissionNode<?>> registeredNodes;
    private final Set<PermissionNode<?>> immutableRegisteredNodes;

    /**
     * @param permissions the full set of nodes Forge gathered via PermissionGatherEvent.Nodes. We must expose these
     *                    unchanged through {@link #getRegisteredNodes()}, since PermissionAPI rejects any query for a
     *                    node this handler does not report as registered.
     */
    public SuForgePermissionHandler(Collection<PermissionNode<?>> permissions)
    {
        this.registeredNodes = new HashSet<>(permissions);
        this.immutableRegisteredNodes = Collections.unmodifiableSet(this.registeredNodes);
    }

    @Override
    public ResourceLocation getIdentifier()
    {
        return IDENTIFIER;
    }

    @Override
    public Set<PermissionNode<?>> getRegisteredNodes()
    {
        return immutableRegisteredNodes;
    }

    @Override
    public <T> T getPermission(ServerPlayer player, PermissionNode<T> node, PermissionDynamicContext<?>... context)
    {
        // Only boolean nodes have a meaningful SU mapping. For an online player, resolve to a UserIdent and consult
        // SU's own permission system so grants made via /perm reach Forge PermissionAPI callers.
        // Keyless (no permission engine) there are no SU grants to honour, and the keyless helper would read an
        // unregistered node as allowed, so defer to Forge's own default resolver, exactly like Forge's default
        // handler.
        if (player != null && PermissionTypes.BOOLEAN.equals(node.getType())
                && net.shurui.shuruisutilities.api.key.PermissionHooks.available())
        {
            try
            {
                UserIdent ident = UserIdent.get(player);
                if (ident != null)
                {
                    boolean granted = APIRegistry.perms.checkUserPermission(ident, node.getNodeName());
                    // Node type is Boolean here, so this cast is safe.
                    @SuppressWarnings("unchecked")
                    T result = (T) Boolean.valueOf(granted);
                    return result;
                }
            }
            catch (Throwable t)
            {
                // Never let an SU-side failure break another mod's permission query; fall through to the default.
                LoggingHandler.sulog.debug("[Permissions] SU permission bridge failed for node {}; using default. {}",
                        node.getNodeName(), t.toString());
            }
        }

        // Non-boolean node, no player context, no SU opinion, or an error above: honor the node's own default.
        return node.getDefaultResolver().resolve(player, player == null ? null : player.getUUID(), context);
    }

    @Override
    public <T> T getOfflinePermission(UUID player, PermissionNode<T> node, PermissionDynamicContext<?>... context)
    {
        // SU permission resolution needs an online player context (zones, groups by point), so for offline queries we
        // return the node's default resolver value rather than guess. This matches Forge's DefaultPermissionHandler.
        return node.getDefaultResolver().resolve(null, player, context);
    }
}
