package net.shurui.shuruisutilities.hologram.client;

import java.util.ArrayList;
import java.util.List;

import net.shurui.shuruisutilities.hologram.network.GifBillboard;

/** The image billboards the server has told this client about. Replaced wholesale each time the set changes. */
public final class HologramBillboards
{
    private HologramBillboards() {}

    private static volatile List<GifBillboard> all = List.of();

    public static void accept(List<GifBillboard> billboards)
    {
        all = billboards == null ? List.of() : List.copyOf(billboards);
    }

    public static List<GifBillboard> all()
    {
        return all;
    }

    /** Forget everything on disconnect, or the next world would draw the last server's billboards. */
    public static void clear()
    {
        all = new ArrayList<>();
    }
}
