package net.shurui.dev.shuruis_dmz_tournaments.reward;

// editable form of one reward so the GUI can show a type dropdown + per-type inputs (like sdu's saga
// rewards). round-trips to/from the flat token strings RewardManager consumes.
public class Reward {
    public static final String[] TYPES = {"ITEM", "COMMAND", "TP", "SKILL", "TITLE", "MESSAGE"};

    public String type = "ITEM";
    public String item = "minecraft:diamond";
    public int count = 1;
    public String command = "say hi {player}";
    public float tp = 1000;
    public String skill = "";
    public int level = 1;
    public String title = "champion";
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
            case "tp" -> { r.type = "TP"; r.tp = parseFloat(arg, 1000); }
            case "skill" -> {
                r.type = "SKILL";
                String[] parts = arg.split(":");
                r.skill = parts[0];
                r.level = parts.length >= 2 ? parseInt(parts[1], 1) : 1;
            }
            case "title" -> { r.type = "TITLE"; r.title = arg.trim(); }
            case "message" -> { r.type = "MESSAGE"; r.message = arg; }
            default -> { r.type = "MESSAGE"; r.message = token; }
        }
        return r;
    }

    public String toToken() {
        return switch (type) {
            case "ITEM" -> "item:" + item + ":" + count;
            case "COMMAND" -> "command:" + command;
            case "TP" -> "tp:" + fmtTp(tp);
            case "SKILL" -> "skill:" + skill + ":" + level;
            case "TITLE" -> "title:" + title;
            default -> "message:" + message;
        };
    }

    // shown in the reward list
    public String summary() {
        return switch (type) {
            case "ITEM" -> "Item: " + count + "x " + item;
            case "COMMAND" -> "Command: /" + command;
            case "TP" -> "Training Points: +" + fmtTp(tp);
            case "SKILL" -> "Skill: " + skill + " +" + level;
            case "TITLE" -> "Grant title: " + title;
            default -> "Message: " + message;
        };
    }

    private static int parseInt(String s, int fb) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }

    private static float parseFloat(String s, float fb) {
        try {
            return Float.parseFloat(s.trim());
        } catch (NumberFormatException e) {
            return fb;
        }
    }

    // drop trailing ".0" for whole numbers
    public static String fmtTp(float f) {
        return f == Math.rint(f) ? Long.toString((long) f) : Float.toString(f);
    }
}
