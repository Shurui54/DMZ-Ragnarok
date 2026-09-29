package net.shurui.shuruisutilities.permissions.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.annotations.Expose;

import net.shurui.shuruisutilities.api.permissions.Zone;

/**
 * One stored permission schedule (the data the key's {@code PermissionScheduler} runs). It stays in CORE because
 * DataManager names its folder after the SIMPLE class name ({@code SUData/json/PermissionSchedule}), so this class
 * must keep the simple name {@code PermissionSchedule} and no second class may take it. It used to be nested in
 * PermissionScheduler; the fields, their order and their annotations are unchanged, so Gson reads and writes the
 * same JSON.
 */
public class PermissionSchedule
{

    /** One toggled node: the value written while the schedule is on, and the value written while it is off. */
    public static class PermissionEntry
    {

        public String on;

        public String off;

        public PermissionEntry(String on, String off)
        {
            this.on = on;
            this.off = off;
        }

    }

    @Expose(serialize = false)
    public boolean state;

    public boolean isRealTime = true;

    public boolean isDelay = false;

    public int zoneId = 1;

    public String group = Zone.GROUP_DEFAULT;

    public String onMessage;

    public String offMessage;

    public List<Integer> times = new ArrayList<>();

    public Map<String, PermissionEntry> permissions = new HashMap<>();

}
