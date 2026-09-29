package net.shurui.dev.sdu.client;

import com.dragonminez.common.config.ConfigManager;
import com.dragonminez.common.config.FormConfig;
import com.dragonminez.common.stats.StatsData;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.shurui.dev.sdu.DmzNpc;
import net.shurui.dev.sdu.compat.DmzSkills;
import net.shurui.dev.sdu.form.FormQuestGate;
import net.shurui.dev.sdu.network.BuyStackSkillC2S;
import net.shurui.dev.sdu.network.DmzNet;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * Makes DMZ's stack skills (kaioken, ultimate) buyable-at-level-0 from the skills tree. DMZ's own
 * {@code SkillsMenuScreen} double-click REFUSES a stack skill's first level, and its server PURCHASE handler
 * silently bails for these anyway. So on a valid double-click we send {@link BuyStackSkillC2S} over sdu's
 * channel, granting/charging via {@link net.shurui.dev.sdu.compat.DmzSkills#buyStackSkill} (same path as
 * {@code /rg npc buyskill}), never touching DMZ's broken PURCHASE conditions.
 *
 * <p>This was a {@code @Inject(require=0)} on {@code mouseClicked} that silently never ran on the live server
 * (its "active" log never appeared), so nothing was ever bought. Moved to a FORGE CLIENT screen event
 * ({@link ScreenEvent.MouseButtonPressed.Pre}), which always fires and logs unconditionally, so the handler is
 * self-proving (see {@link MultiplayerScreenClientEvents}).
 *
 * <p>DMZ's screen and node internals are reflected by their stable (never-remapped) mod names, handles cached.
 * The first hard failure logs WARN once and latches the feature quiet rather than crashing the screen. Client
 * only via {@code @Mod.EventBusSubscriber}, so nothing classloads on a dedicated server.
 */
@Mod.EventBusSubscriber(modid = "dmz_ragnarok", value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class StackSkillBuyEvents {

    /** Fully-qualified name of DMZ's skills screen. Name-checked (not typed) so we never classload it early. */
    private static final String SCREEN_CLASS = "com.dragonminez.client.gui.character.SkillsMenuScreen";

    /** Our own double-click window (ms). Independent of DMZ's fragile selection/timer bookkeeping. */
    private static final long DOUBLE_CLICK_MS = 500L;

    // Cached reflective handles into DMZ's screen + node internals (resolved once, on first matching screen).
    private static Method toUiX;                 // double toUiX(double)
    private static Method toUiY;                 // double toUiY(double)
    private static Method hovered;               // FormNode getHoveredFormNode(double, double)
    private static Method costForLevel;          // int getUpgradeCostForTargetLevel(String, int)
    private static Method updateStats;           // void updateStatsData()
    private static Field statsDataField;         // StatsData statsData
    private static Field currentCategoryField;   // SkillCategory currentCategory
    private static Field nodeFormTypeField;      // String FormNode.formType
    private static Field nodeDataField;          // FormConfig.FormData FormNode.data
    private static Field nodeGroupField;         // String FormNode.group
    private static boolean handlesReady;

    // One-time diagnostic latches. activeLogged PROVES the handler fires on the real screen; failLogged keeps
    // the failure path from spamming; buyLogged is reset each buy so every purchase logs exactly once.
    private static boolean activeLogged;
    private static boolean failLogged;
    private static boolean failed;

    // Our own double-click tracker, keyed by node group + form name, so the second click must be the SAME node.
    private static String lastClickKey;
    private static long lastClickAt;

    private StackSkillBuyEvents() {
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (failed || event.getButton() != 0) {
            return; // left button only; go quiet permanently after a hard failure.
        }
        if (PlayerInfoView.active()) {
            return; // read-only /dmzinfo viewer: this buy rides sdu's own channel, so block it here too.
        }
        Screen screen = event.getScreen();
        if (screen == null || !SCREEN_CLASS.equals(screen.getClass().getName())) {
            return; // not DMZ's skills screen.
        }

        // Proof line: if this never appears when clicking the kaioken node, the handler is not being reached.
        if (!activeLogged) {
            activeLogged = true;
            DmzNpc.LOGGER.info("[{}] stack-buy screen-event active on SkillsMenuScreen", DmzNpc.MODID);
        }

        try {
            if (!handlesReady) {
                Class<?> cls = screen.getClass();
                toUiX = findMethod(cls, "toUiX", double.class);
                toUiY = findMethod(cls, "toUiY", double.class);
                hovered = findMethod(cls, "getHoveredFormNode", double.class, double.class);
                costForLevel = findMethod(cls, "getUpgradeCostForTargetLevel", String.class, int.class);
                updateStats = findMethod(cls, "updateStatsData");
                statsDataField = findField(cls, "statsData");
                currentCategoryField = findField(cls, "currentCategory");
                handlesReady = true;
            }

            StatsData statsData = (StatsData) statsDataField.get(screen);
            if (statsData == null) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: no statsData yet.", DmzNpc.MODID);
                return;
            }

            // FORMS category only (the stack-skill tree lives here).
            Object category = currentCategoryField.get(screen);
            if (category == null || !"FORMS".equals(((Enum<?>) category).name())) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: not in FORMS category (category={}).", DmzNpc.MODID, category);
                return;
            }

            double ux = (double) toUiX.invoke(screen, event.getMouseX());
            double uy = (double) toUiY.invoke(screen, event.getMouseY());
            Object node = hovered.invoke(screen, ux, uy);
            if (node == null) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: no hovered form node under cursor.", DmzNpc.MODID);
                return;
            }

            if (nodeFormTypeField == null) {
                nodeFormTypeField = node.getClass().getDeclaredField("formType");
                nodeFormTypeField.setAccessible(true);
            }
            if (nodeDataField == null) {
                nodeDataField = node.getClass().getDeclaredField("data");
                nodeDataField.setAccessible(true);
            }
            if (nodeGroupField == null) {
                nodeGroupField = node.getClass().getDeclaredField("group");
                nodeGroupField.setAccessible(true);
            }

            Object typeObj = nodeFormTypeField.get(node);
            if (!(typeObj instanceof String formType) || formType.isEmpty()) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: hovered node has no formType.", DmzNpc.MODID);
                return;
            }

            // Only DMZ stack skills at their level-0 first buy: the exact case DMZ's mouseClicked refuses.
            boolean isStack = ConfigManager.getSkillsConfig().getStackSkills()
                    .contains(formType.toLowerCase(Locale.ROOT));
            if (!isStack) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: '{}' is not a stack skill; deferring to DMZ.",
                        DmzNpc.MODID, formType);
                return; // form skills already work via DMZ's UPGRADE + our form-buy mixins.
            }

            Object dataObj = nodeDataField.get(node);
            if (!(dataObj instanceof FormConfig.FormData formData)) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: hovered stack node '{}' has no FormData.", DmzNpc.MODID, formType);
                return;
            }

            Integer skillLevel = statsData.getSkills().getSkillLevel(formType);
            int currentLevel = skillLevel == null ? 0 : skillLevel;

            // Ultimate's first level is Old Kai's to give. Returning WITHOUT cancelling hands the click back to
            // DMZ's own mouseClicked, which refuses a stack skill's first level, so the node behaves exactly as
            // it does in an unmodded game. The server refuses it too (DmzSkills.isRitualLockedUnlock); this side
            // only stops us sending a request that would be denied. Levels 2+ fall through as normal, so forms
            // built onto the ultimate line stay purchasable here.
            if (DmzSkills.isRitualLockedUnlock(formType, currentLevel)) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: '{}' unlock is Old Kai's ritual; deferring to DMZ.",
                        DmzNpc.MODID, formType);
                return;
            }
            Integer requiredBoxed = formData.getUnlockOnSkillLevel();
            int requiredLevel = requiredBoxed == null ? 0 : requiredBoxed;
            // Mirror DMZ's own gating: only the first stack level (targetLevel 0), only when this node is exactly
            // the next purchasable level. Levels 2+ are left to DMZ's working UPGRADE path.
            int targetLevel = Math.max(0, requiredLevel - 1);
            boolean canPurchaseLevel = requiredLevel == currentLevel + 1;
            if (!canPurchaseLevel || targetLevel != 0) {
                DmzNpc.LOGGER.debug(
                        "[{}] stack-buy: '{}' wrong level (current={}, node requiredLevel={}, targetLevel={}); "
                                + "only the level-0 first buy is handled here.",
                        DmzNpc.MODID, formType, currentLevel, requiredLevel, targetLevel);
                return;
            }

            // Our own double-click detector, keyed by group + form name so the second click must be the SAME
            // node. First click records and falls through WITHOUT cancelling, so DMZ still selects the node.
            Object group = nodeGroupField.get(node);
            String groupStr = group instanceof String g ? g : "";
            String clickKey = groupStr + " " + formData.getName();
            long now = System.currentTimeMillis();
            boolean isDoubleClick = clickKey.equalsIgnoreCase(lastClickKey)
                    && now - lastClickAt < DOUBLE_CLICK_MS;
            lastClickKey = clickKey;
            lastClickAt = now;
            if (!isDoubleClick) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: first click recorded for '{}' (double-click within {}ms to buy).",
                        DmzNpc.MODID, formType, DOUBLE_CLICK_MS);
                return; // first click / different node / expired window: let DMZ handle selection.
            }

            // Advisory quest gate (server is authoritative anyway). Fails open when no gate exists for this form.
            if (FormQuestGate.blocksUpgradeToLevel(statsData, formType, 1)) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: '{}' blocked by quest gate.", DmzNpc.MODID, formType);
                return;
            }

            // DMZ's stack-skill level-0 cost (skills.<name>.costs[0]); MAX_VALUE means unpriced/unbuyable.
            int cost = (int) costForLevel.invoke(screen, formType, 0);
            if (cost == Integer.MAX_VALUE) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: '{}' is unpriced/unbuyable; deferring to DMZ.",
                        DmzNpc.MODID, formType);
                return;
            }
            // Advisory only: DmzSkills.buyStackSkill on the server is the real gate. A NEGATIVE cost is DMZ's
            // "not purchasable at this rung" sentinel (kaioken and ultimate both lead with -1), and the server now
            // refuses it instead of clamping it to a free buy, so stop sending a request it will only deny.
            if (cost < 0) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: '{}' is not purchasable at this rung (earned from the master); deferring to DMZ.",
                        DmzNpc.MODID, formType);
                return;
            }
            int payable = cost;
            if (statsData.getResources().getTrainingPoints() < (float) payable) {
                DmzNpc.LOGGER.debug("[{}] stack-buy: cannot afford '{}' (need {}, have {}).",
                        DmzNpc.MODID, formType, payable, (int) statsData.getResources().getTrainingPoints());
                return;
            }

            // Route through our channel (DmzSkills.buyStackSkill), then consume the click so DMZ's mouseClicked
            // (which would refuse this level) does not also run.
            DmzNet.sendToServer(new BuyStackSkillC2S(formType));
            updateStats.invoke(screen); // refresh the local snapshot so the tree reflects the buy immediately.
            lastClickKey = null;
            lastClickAt = 0L;
            event.setCanceled(true);
            DmzNpc.LOGGER.info("[{}] sent stack-skill buy request for {} (cost {})", DmzNpc.MODID, formType, payable);
        } catch (Throwable t) {
            failed = true; // DMZ internals differ from expectations; disable rather than spam/crash the screen.
            if (!failLogged) {
                failLogged = true;
                DmzNpc.LOGGER.warn("[{}] stack-buy screen-event failed: {}", DmzNpc.MODID, t.toString(), t);
            }
        }
    }

    private static Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Method findMethod(Class<?> cls, String name, Class<?>... params) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
                // try the superclass
            }
        }
        throw new NoSuchMethodException(name);
    }
}
