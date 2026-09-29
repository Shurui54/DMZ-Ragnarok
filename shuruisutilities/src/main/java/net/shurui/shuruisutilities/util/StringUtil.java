package net.shurui.shuruisutilities.util;

public class StringUtil
{
    public static <T> String toJsonString(T[] strings)
    {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < strings.length; i++)
        {
            if (i > 0)
            {
                sb.append(", ");
            }
            sb.append("\"");
            sb.append(strings[i]);
            sb.append("\"");
        }
        sb.append("]");
        return sb.toString();
    }

    // seconds -> H:MM:SS, dropping the hours block when zero (M:SS). negatives clamp to 0.
    // 3661 -> "1:01:01", 125 -> "2:05", 0 -> "0:00"
    public static String formatDuration(long seconds)
    {
        if (seconds < 0)
            seconds = 0;
        long h = seconds / 3600;
        long m = (seconds % 3600) / 60;
        long s = seconds % 60;
        return h > 0 ? String.format("%d:%02d:%02d", h, m, s) : String.format("%d:%02d", m, s);
    }
}
