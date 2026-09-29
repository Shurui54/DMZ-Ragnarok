package net.shurui.dev.sdu.compat.dmz;

/**
 * The suite's modids + a case-insensitive substring test, to suppress DMZ's JSON-load diagnostics about
 * our own addons.
 *
 * <p>DMZ funnels JSON-load problems through {@code JsonLoadReport} and mirrors them into chat on login.
 * Both {@code JsonLoadReportMixin} (cancels at the source) and the log4j2 filter in {@code DmzNpc} consult
 * this class so the muted set stays in one place.
 */
public final class AddonNamespaceMute {

    /** modids whose DMZ JSON-load noise we suppress. The five addons now ship under the merged dmz_ragnarok id. */
    private static final String[] MODIDS = {
            "dmz_ragnarok",
    };

    private AddonNamespaceMute() {
    }

    /** true if text contains one of our modids (case-insensitive) */
    public static boolean matches(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String modid : MODIDS) {
            if (lower.contains(modid)) {
                return true;
            }
        }
        return false;
    }

    /** true if any of the three JSON-report fields references one of our addons */
    public static boolean matchesAny(String source, String file, String message) {
        return matches(source) || matches(file) || matches(message);
    }

    /**
     * True for the benign "unknown type 'FORM_PURCHASE' (entry ignored)" report DMZ emits for sdu's own reward
     * marker. sdu writes {@code "type":"FORM_PURCHASE"} into DMZ's saga/quest JSON as a form-gate marker
     * ({@code SagaData}); DMZ's reward parser ignores it and reports via {@code JsonKeys.reportBadType(...)},
     * but sdu enforces the gate itself ({@code SaveSagaPacket} + {@code UpdateSkillC2SMixin}), so it's noise.
     *
     * <p>Narrow on purpose: matches only {@code FORM_PURCHASE}, not every "(entry ignored)", so real admin
     * typos still surface. New custom reward types (see {@code SagaData.REWARD_TYPES}) => add their strings here.</p>
     */
    public static boolean matchesUnknownAddonRewardType(String message) {
        return message != null && message.contains("FORM_PURCHASE");
    }

    /**
     * True for DMZ's benign schema noise: entries DMZ marked as ignored (message contains {@code (ignored)}).
     * Real load failures lack the marker and stay visible.
     */
    public static boolean isBenignIgnored(String message) {
        if (message == null || message.isEmpty()) {
            return false;
        }
        return message.toLowerCase(java.util.Locale.ROOT).contains("(ignored)");
    }
}
