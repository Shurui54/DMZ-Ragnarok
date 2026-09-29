package net.shurui.dev.shuruis_raid_bosses.reward;

/**
 * One editable reward. Round-trips to/from the flat token strings {@link RewardManager} consumes,
 * stored on a {@link net.shurui.dev.shuruis_raid_bosses.raid.RaidBossDef}.
 *
 * <p>Command and message tokens may carry placeholders {@link RewardManager} substitutes per recipient:
 * {@code %player%}, {@code %damage%}, {@code %damage_percent%}, {@code %rank%}, {@code %boss%},
 * {@code %total_damage%}.
 */
public class Reward {
    public static final String[] TYPES = {"COMMAND", "ITEM", "SKILL", "TP", "MESSAGE"};

    public String type = "COMMAND";
    public String item = "minecraft:diamond";
    public int count = 1;
    public String command = "give %player% minecraft:diamond 1";
    public String tp = "100"; // DragonMineZ training points to grant
    public String skill = "";
    public int level = 1;
    public String message = "&aWell fought!";

    public static Reward fromToken(String token) {
        Reward r = new Reward();
        if (token == null) return r;
        int colon = token.indexOf(':');
        if (colon < 0) { r.type = "MESSAGE"; r.message = token; return r; }
        String type = token.substring(0, colon).toLowerCase();
        String arg = token.substring(colon + 1);
        switch (type) {
            case "item" -> {
                r.type = "ITEM";
                String[] parts = arg.split(":");
                if (parts.length >= 3) {
                    r.item = parts[0] + ":" + parts[1];
                    r.count = parseInt(parts[2], 1);
                } else {
                    r.item = arg;
                }
            }
            case "command" -> { r.type = "COMMAND"; r.command = arg; }
            case "tp" -> { r.type = "TP"; r.tp = arg.trim(); }
            case "skill" -> {
                r.type = "SKILL";
                String[] parts = arg.split(":");
                r.skill = parts[0];
                r.level = parts.length >= 2 ? parseInt(parts[1], 1) : 1;
            }
            case "message" -> { r.type = "MESSAGE"; r.message = arg; }
            default -> { r.type = "MESSAGE"; r.message = token; }
        }
        return r;
    }

    public String toToken() {
        return switch (type) {
            case "ITEM" -> "item:" + item + ":" + count;
            case "TP" -> "tp:" + tp;
            case "SKILL" -> "skill:" + skill + ":" + level;
            case "MESSAGE" -> "message:" + message;
            default -> "command:" + command;
        };
    }

    public String summary() {
        return switch (type) {
            case "ITEM" -> "Item: " + count + "x " + item;
            case "TP" -> "Training Points: +" + tp;
            case "SKILL" -> "Skill: " + skill + " +" + level;
            case "MESSAGE" -> "Message: " + message;
            default -> "Command: /" + command;
        };
    }

    private static int parseInt(String s, int fb) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }
}
