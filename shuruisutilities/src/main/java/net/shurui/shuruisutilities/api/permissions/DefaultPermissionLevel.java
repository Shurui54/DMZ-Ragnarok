package net.shurui.shuruisutilities.api.permissions;

// Forge dropped its own DefaultPermissionLevel in the 1.19+ permission rewrite. SU has its own storage,
// so we keep this SU-side (mirrors the old Forge values) to preserve the API surface.
public enum DefaultPermissionLevel
{
    ALL, OP, NONE
}
