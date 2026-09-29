package net.shurui.dev.sdu.item;

/**
 * Implemented by the buff-token gem items so a single client colour handler can tint each tier from one
 * greyscale base texture. The returned value is an opaque 0xFFrrggbb colour multiplied onto layer 0 of the
 * item's generated model (see the RegisterColorHandlersEvent.Item registration in the client mod-bus events).
 */
public interface TintedGem {

    /** Opaque 0xFFrrggbb tint for this tier, multiplied onto the greyscale base texture. */
    int gemTint();
}
