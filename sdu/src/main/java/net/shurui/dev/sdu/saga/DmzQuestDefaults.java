package net.shurui.dev.sdu.saga;

/**
 * Constants shared with DMZ's {@code com.dragonminez.common.quest.QuestUpgrader}, which runs on every
 * default saga manifest, saga quest, and side quest file DMZ owns a baseline for.
 *
 * <p>The upgrader writes a {@link #VERSION_KEY} into each of those files. On the next world load it re-reads
 * the file: if the file's {@link #VERSION_KEY} still equals DMZ's {@link #DEFAULTS_VERSION} it is left alone,
 * otherwise the file is deep-merged back toward DMZ's built-in default and rewritten. Our editor saves the
 * file correctly, but if it drops {@link #VERSION_KEY} then DMZ sees a version mismatch on the next start and
 * reverts the edit (this is why "sagas are not saving stats"). So every save must preserve the field: keep
 * the loaded value, or, for a file we previously stripped, re-stamp {@link #DEFAULTS_VERSION} so DMZ treats
 * the file as current and leaves it alone.
 *
 * <p>{@link #DEFAULTS_VERSION} MUST track {@code QuestUpgrader.DEFAULTS_VERSION} in the DMZ version we ship
 * against (currently DragonMineZ 2.1.3, whose {@code QuestUpgrader.DEFAULTS_VERSION} is "2.1.2"). A DMZ update
 * that changes either the version string or the key name means updating the matching constant here too.
 */
public final class DmzQuestDefaults {

    /** Must equal {@code QuestUpgrader.DEFAULTS_VERSION} in the shipped DMZ. See class doc before changing. */
    public static final String DEFAULTS_VERSION = "2.1.2";

    /** Must equal {@code QuestUpgrader.VERSION_KEY} in the shipped DMZ. See class doc before changing. */
    public static final String VERSION_KEY = "defaultsVersion";

    private DmzQuestDefaults() {
    }
}
