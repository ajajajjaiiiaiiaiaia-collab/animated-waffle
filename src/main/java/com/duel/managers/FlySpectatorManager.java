package com.duel.managers;

import com.duel.DuelPlugin;
import com.duel.gui.FlySpeedGUI;
import com.duel.models.DuelSession;
import com.duel.models.PartyDuelSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * نظام المتفرج الجديد: اللاعب يكون SURVIVAL + يطير (مو gamemode spectator).
 *  - السلوت الأول في الهوتبار: بوصلة تفتح GUI بسطر واحد للاعبين اللي في الماتش.
 *  - مخفي عن لاعبي الماتش وعن المتفرجين الثانيين، ما يتصادم، ما يتضرر، ما يكسر/يحط/يلقط.
 *  - يُستخدم للمتفرج الخارجي (/spectate + GUI) وللميت (1v1 / Party / FFA / الخاسر بين الرواندات).
 */
public class FlySpectatorManager {

    private final DuelPlugin plugin;
    private final NamespacedKey compassKey;
    private final NamespacedKey speedKey;
    /** سرعة الطيران الطبيعية (1x). */
    public static final float DEFAULT_SPEED = 0.1f;
    /** السرعة المختارة لكل متفرج — تنمسح عند الخروج. */
    private final Map<UUID, Float> speeds = new ConcurrentHashMap<>();
    private final Set<UUID> active = ConcurrentHashMap.newKeySet();
    /** spectator → اللاعبين اللي خفيناه عنهم (عشان نرجّعه لهم عند الخروج) */
    private final Map<UUID, Set<UUID>> hiddenFrom = new HashMap<>();
    private BukkitTask task;

    public FlySpectatorManager(DuelPlugin plugin) {
        this.plugin = plugin;
        this.compassKey = new NamespacedKey(plugin, "fly_spectator_compass");
        this.speedKey = new NamespacedKey(plugin, "fly_spectator_speed");
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 5L, 5L);
    }

    private volatile boolean packetGuard = false;
    public boolean hasPacketGuard() { return packetGuard; }
    public void setPacketGuard(boolean v) { this.packetGuard = v; }

    public boolean isActive(Player p) {
        return p != null && active.contains(p.getUniqueId());
    }

    // ────────────────────────────────────────────
    //  Compass item
    // ────────────────────────────────────────────

    public ItemStack buildCompass() {
        ItemStack item = new ItemStack(Material.COMPASS);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text("🧭 Players", NamedTextColor.AQUA)
                .decoration(TextDecoration.ITALIC, false).decoration(TextDecoration.BOLD, true));
        meta.lore(List.of(
                Component.text("Right-click to choose a player", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text("and teleport to them.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false)));
        meta.getPersistentDataContainer().set(compassKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isCompass(ItemStack item) {
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(compassKey, PersistentDataType.BYTE);
    }

    // ────────────────────────────────────────────
    //  Fly speed (ايتم الهوتبار + السرعة)
    // ────────────────────────────────────────────

    /** مكان ايتم السرعة في الهوتبار (1-8) — الأيتم الأول للبوصلة. */
    public int speedSlot() {
        int s = plugin.getConfig().getInt("spectator.speed-item-slot", 8);
        return (s < 1 || s > 8) ? 8 : s;
    }

    public float getSpeed(Player p) {
        return speeds.getOrDefault(p.getUniqueId(), DEFAULT_SPEED);
    }

    public void setSpeed(Player p, float speed) {
        if (!isActive(p)) return;
        float v = Math.max(0.01f, Math.min(1f, speed));
        speeds.put(p.getUniqueId(), v);
        try { p.setFlySpeed(v); } catch (Throwable ignored) {}
        updateSpeedItem(p);
    }

    public void resetSpeed(Player p) {
        setSpeed(p, DEFAULT_SPEED);
    }

    public ItemStack buildSpeedItem(Player p) {
        ItemStack item = new ItemStack(Material.FEATHER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage()
                .deserialize("<gradient:#00E5FF:#7C4DFF><bold>✈ Fly Speed</bold></gradient>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("Control how fast you fly", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("while spectating.", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                Component.text("Speed: ", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false)
                        .append(Component.text(FlySpeedGUI.describe(getSpeed(p)), NamedTextColor.AQUA)),
                Component.empty(),
                Component.text("▶ Right-click to open", NamedTextColor.YELLOW).decoration(TextDecoration.ITALIC, false)));
        meta.setEnchantmentGlintOverride(true);
        meta.getPersistentDataContainer().set(speedKey, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public boolean isSpeedItem(ItemStack item) {
        if (item == null || item.getType() != Material.FEATHER || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer().has(speedKey, PersistentDataType.BYTE);
    }

    private void updateSpeedItem(Player p) {
        try { p.getInventory().setItem(speedSlot(), buildSpeedItem(p)); } catch (Throwable ignored) {}
    }

    // ────────────────────────────────────────────
    //  Enter / refresh / exit
    // ────────────────────────────────────────────

    /** يدخّل اللاعب وضع المتفرج الطائر (ينظّف الإنفنتوري ويعطيه البوصلة). */
    public void enter(Player p) {
        if (p == null || !p.isOnline()) return;
        active.add(p.getUniqueId());
        try { p.setFireTicks(0); } catch (Throwable ignored) {}
        try {
            for (org.bukkit.potion.PotionEffect e : new ArrayList<>(p.getActivePotionEffects())) {
                p.removePotionEffect(e.getType());
            }
        } catch (Throwable ignored) {}
        speeds.putIfAbsent(p.getUniqueId(), DEFAULT_SPEED);
        p.getInventory().clear();
        p.getInventory().setItem(0, buildCompass());
        p.getInventory().setItem(speedSlot(), buildSpeedItem(p));
        try { p.getInventory().setHeldItemSlot(0); } catch (Throwable ignored) {}
        apply(p);
    }

    /** يعيد تطبيق الحالة (بعد تغيير عالم مثلاً) بدون ما يمسح الإنفنتوري. */
    public void refresh(Player p) {
        if (!isActive(p) || !p.isOnline()) return;
        if (!isCompass(p.getInventory().getItem(0))) p.getInventory().setItem(0, buildCompass());
        if (!isSpeedItem(p.getInventory().getItem(speedSlot()))) updateSpeedItem(p);
        apply(p);
    }

    private void apply(Player p) {
        try {
            if (p.getGameMode() != GameMode.SURVIVAL) p.setGameMode(GameMode.SURVIVAL);
            p.setInvulnerable(true);
            p.setAllowFlight(true);
            p.setFlying(true);
            p.setFallDistance(0f);
            p.setCollidable(false);
            try { p.setSleepingIgnored(true); } catch (Throwable ignored) {}
            try { p.setAffectsSpawning(false); } catch (Throwable ignored) {}
            p.setFlySpeed(getSpeed(p));
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[FlySpectator] apply failed for " + p.getName(), t);
        }
        hideFromMatch(p);
    }

    /** يخرّج اللاعب من الوضع ويرجّع الحالة الطبيعية (ما ينقله ولا يغيّر الإنفنتوري سوى حذف البوصلة). */
    public void exit(Player p) {
        if (p == null) return;
        if (!active.remove(p.getUniqueId())) return;
        try {
            for (int i = 0; i < p.getInventory().getSize(); i++) {
                ItemStack it = p.getInventory().getItem(i);
                if (isCompass(it) || isSpeedItem(it)) p.getInventory().setItem(i, null);
            }
        } catch (Throwable ignored) {}
        // السرعة تنلغي أول ما يطلع من التفرج
        speeds.remove(p.getUniqueId());
        try { p.setFlySpeed(DEFAULT_SPEED); } catch (Throwable ignored) {}
        try {
            p.setInvulnerable(false);
            p.setCollidable(true);
            try { p.setSleepingIgnored(false); } catch (Throwable ignored) {}
            try { p.setAffectsSpawning(true); } catch (Throwable ignored) {}
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
            p.setFallDistance(0f);
        } catch (Throwable t) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "[FlySpectator] exit failed for " + p.getName(), t);
        }
        showToMatch(p);
    }

    /** ندخّله بعد شوي (بعد الريسباون/نقل العالم) — لو لسا أونلاين. */
    public void scheduleEnter(Player p, long delayTicks) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (p.isOnline()) enter(p);
        }, delayTicks);
    }

    public void shutdown() {
        if (task != null) { task.cancel(); task = null; }
        for (UUID u : new ArrayList<>(active)) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) exit(p);
        }
        active.clear();
    }

    // ────────────────────────────────────────────
    //  Visibility
    // ────────────────────────────────────────────

    private void hideFromMatch(Player spectator) {
        Set<UUID> hidden = hiddenFrom.computeIfAbsent(spectator.getUniqueId(), k -> new HashSet<>());
        for (Player viewer : spectator.getWorld().getPlayers()) {
            if (viewer.equals(spectator)) continue;
            try {
                // المتفرج مخفي عن الكل (لاعبين + متفرجين ثانيين)
                if (viewer.canSee(spectator)) viewer.hidePlayer(plugin, spectator);
                hidden.add(viewer.getUniqueId());

                // المتفرجين ما يشوفون بعض: نخفي المتفرج الثاني عن هذا المتفرج فوراً (بدون انتظار الـ tick)
                if (active.contains(viewer.getUniqueId())) {
                    if (spectator.canSee(viewer)) spectator.hidePlayer(plugin, viewer);
                    hiddenFrom.computeIfAbsent(viewer.getUniqueId(), k -> new HashSet<>())
                              .add(spectator.getUniqueId());
                }
            } catch (Throwable ignored) {}
        }
    }

    private void showToMatch(Player spectator) {
        Set<UUID> hidden = hiddenFrom.remove(spectator.getUniqueId());
        if (hidden == null) return;
        for (UUID u : hidden) {
            Player viewer = Bukkit.getPlayer(u);
            if (viewer == null || !viewer.isOnline()) continue;
            try {
                if (plugin.getSettingsManager().isEnabled(u, SettingsManager.Setting.HIDE_PLAYERS)) continue;
                viewer.showPlayer(plugin, spectator);
            } catch (Throwable ignored) {}
        }
    }

    // ────────────────────────────────────────────
    //  Targets (اللاعبين اللي يظهرون في الـ GUI)
    // ────────────────────────────────────────────

    public List<Player> getTargets(Player spectator) {
        World w = spectator.getWorld();
        List<Player> out = new ArrayList<>();
        for (Player t : w.getPlayers()) {
            if (t.equals(spectator) || !t.isOnline() || active.contains(t.getUniqueId())) continue;
            if (t.getGameMode() == GameMode.SPECTATOR) continue;
            PartyDuelSession ps = plugin.getPartyDuelManager().getSession(t);
            if (ps != null) {
                if (ps.isAlive(t)) out.add(t);
                continue;
            }
            DuelSession ds = plugin.getDuelManager().getSession(t);
            if (ds != null) out.add(t);
        }
        out.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        return out;
    }

    // ────────────────────────────────────────────
    //  Periodic enforcement
    // ────────────────────────────────────────────

    private void tick() {
        for (UUID u : new ArrayList<>(active)) {
            Player p = Bukkit.getPlayer(u);
            if (p == null || !p.isOnline()) { active.remove(u); hiddenFrom.remove(u); speeds.remove(u); continue; }
            if (p.isDead()) continue;
            try {
                // Multiverse وغيره ممكن يغيّر الـ gamemode عند تغيير العالم
                if (p.getGameMode() != GameMode.SURVIVAL || !p.getAllowFlight() || !p.isInvulnerable()) apply(p);
                else hideFromMatch(p);
                if (p.isCollidable()) p.setCollidable(false);

                // ثبّت السرعة المختارة (لو بلجن/كود ثاني غيّرها)
                float want = getSpeed(p);
                if (Math.abs(p.getFlySpeed() - want) > 0.0005f) p.setFlySpeed(want);

                // حماية الفويد: لو نزل تحت العالم نرجّعه
                World w = p.getWorld();
                if (p.getLocation().getY() < w.getMinHeight() + 1) {
                    List<Player> targets = getTargets(p);
                    Location dest = !targets.isEmpty() ? targets.get(0).getLocation() : w.getSpawnLocation();
                    p.teleport(dest);
                    p.setFlying(true);
                }
            } catch (Throwable t) {
                plugin.getLogger().log(java.util.logging.Level.WARNING, "[FlySpectator] tick failed", t);
            }
        }
    }
}
