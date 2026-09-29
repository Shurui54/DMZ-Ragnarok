package net.shurui.shuruisutilities.api.permissions;

import net.shurui.shuruisutilities.api.permissions.ServerZone.PermissionDebugger;
import net.shurui.shuruisutilities.commons.selections.WorldArea;
import net.shurui.shuruisutilities.commons.selections.WorldPoint;

// root of the tree, lowest priority. holds defaults set via registerPermissionProperty.
public class RootZone extends Zone
{

    /**
     * All three are LIVE RUNTIME WIRING, never persisted data, so all three are {@code transient}.
     *
     * <p>This is hardening, NOT the fix for the 2026-08-27 boot crash: that one was
     * {@code Point.blockPos}, and the walk described there never gets this far because
     * {@code ServerZone.rootZone} is already {@code @Expose(serialize = false)}, so Gson never descends into
     * a RootZone at all. Verified by asking a Gson built exactly like {@code DataManager.getGson()} for
     * {@code getAdapter(ServerZone.class)} against the 1.1.72 classes: it succeeds.
     *
     * <p>They are marked anyway because the day somebody drops that {@code @Expose} these become a live
     * bridge out of the permission tree. {@code permissionHelper} and {@code permissionDebugger} both hold
     * the one live {@code ZonedPermissionHelper} (the Ragnarok Key's engine, which implements
     * both and passes itself in), and Gson's {@code TypeAdapterRuntimeTypeWrapper} swaps a reflective
     * declared-type adapter for the RUNTIME type's adapter at write time, so the interface declaration is no
     * protection. {@code serverZone} is the back half of a reference cycle.
     *
     * <p>Nothing is lost by excluding them. {@code ZonedPermissionHelper.load()} calls
     * {@code rootZone.setServerZone(loadedZone)} on its OWN live root zone, whose helper and debugger were wired
     * in the helper's constructor, and {@code ServerZone.afterLoad()} rebuilds the rest of the back-references.
     */
    protected transient ServerZone serverZone;

    protected transient IPermissionsHelper permissionHelper;

    protected transient PermissionDebugger permissionDebugger;

    public RootZone(IPermissionsHelper permissionHelper)
    {
        super(0);
        this.permissionHelper = permissionHelper;
    }

    @Override
    public boolean isInZone(WorldPoint point)
    {
        return true;
    }

    @Override
    public boolean isInZone(WorldArea point)
    {
        return true;
    }

    @Override
    public boolean isPartOfZone(WorldArea point)
    {
        return true;
    }

    @Override
    public String getName()
    {
        return "_ROOT_";
    }

    @Override
    public Zone getParent()
    {
        return null;
    }

    @Override
    public ServerZone getServerZone()
    {
        return serverZone;
    }

    public void setServerZone(ServerZone serverZone)
    {
        this.serverZone = serverZone;
        if (serverZone != null)
            serverZone.setRootZone(this);
    }

    public IPermissionsHelper getPermissionHelper()
    {
        return permissionHelper;
    }

    public void setPermissionDebugger(PermissionDebugger permissionDebugger)
    {
        this.permissionDebugger = permissionDebugger;
    }

    public PermissionDebugger getPermissionDebugger()
    {
        return permissionDebugger;
    }

    @Override
    public void setDirty()
    {
        permissionHelper.setDirty(true);
    }

}
