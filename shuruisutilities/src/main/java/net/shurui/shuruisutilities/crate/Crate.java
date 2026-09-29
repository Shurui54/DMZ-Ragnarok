package net.shurui.shuruisutilities.crate;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

// a named crate: its key item, display name, and weighted reward list. Persisted by CrateManager, which also
// tracks the bound world blocks by position.
public class Crate
{
    private static final Random RANDOM = new Random();

    public String name;

    public String keyItem = "minecraft:tripwire_hook"; // registry id of the item that opens it

    public String displayName = ""; // GUI title

    public List<CrateReward> rewards = new ArrayList<>();

    public Crate()
    {
    }

    public Crate(String name)
    {
        this.name = name;
        this.displayName = name;
    }

    public int totalWeight()
    {
        int total = 0;
        for (CrateReward r : rewards)
            total += Math.max(1, r.weight);
        return total;
    }

    // weighted random pick from rewards, or null if none
    public CrateReward roll()
    {
        int total = totalWeight();
        if (total <= 0 || rewards.isEmpty())
            return null;
        int pick = RANDOM.nextInt(total);
        for (CrateReward r : rewards)
        {
            pick -= Math.max(1, r.weight);
            if (pick < 0)
                return r;
        }
        return rewards.get(rewards.size() - 1);
    }
}
