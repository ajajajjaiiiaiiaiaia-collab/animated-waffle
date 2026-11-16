package com.duel.managers;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.*;

/**
 * يعزل الـ TAB list لمجموعة لاعبين — كل لاعب يشوف بس اللاعبين اللي في نفس المجموعة.
 *
 * يعمل عبر إرسال PacketPlayOutPlayerInfo (REMOVE) لكل لاعب خارج المجموعة
 * ويحفظ قائمة "المخفيّين" لكل لاعب حتى يقدر يسترجعهم عند الخروج.
 *
 * Supports Paper 1.21.4 via reflection-based packet sending.
 */
public class TabIsolationManager {

    private final com.duel.DuelPlugin plugin;
    private boolean warnedPacketFailure = false;

    public TabIsolationManager(com.duel.DuelPlugin plugin) {
        this.plugin = plugin;
    }

    /** playerUUID → قائمة UUIDs المخفيّة عنه */
    private final Map<UUID, Set<UUID>> hiddenFromPlayer = new HashMap<>();

    /** outsiderUUID → اللاعبين (اللي داخل ماتش) المخفيّين عن هذا اللاعب بالـ TAB */
    private final Map<UUID, Set<UUID>> matchHiddenFromOutsider = new HashMap<>();

    /** يخفي لاعبين الماتش من TAB اللي برا الماتش (من الكونفق: tab-isolation.hide-from-outsiders) */
    private boolean hideFromOutsiders = false;
    public void setHideFromOutsiders(boolean v) { this.hideFromOutsiders = v; }

    /** لو false: العزل بالـ packets مطفي بالكامل (TAB plugin + %rel_duel_color% يتكفلون بالعرض). */
    private boolean enabled = true;
    public void setEnabled(boolean v) { this.enabled = v; }

    /**
     * يطبّق عزل TAB على مجموعة لاعبين.
     * كل لاعب في المجموعة يشوف بس اللاعبين اللي فيها.
     *
     * @param group المجموعة المراد عزلها
     */
    public void isolate(List<Player> group) {
        if (!enabled) return;
        Set<UUID> groupUUIDs = new HashSet<>();
        for (Player p : group) groupUUIDs.add(p.getUniqueId());

        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        for (Player viewer : group) {
            Set<UUID> toHide = new HashSet<>();

            for (Player other : online) {
                if (!groupUUIDs.contains(other.getUniqueId())) {
                    // أخفِ هذا اللاعب من viewer
                    hideFromTab(viewer, other);
                    toHide.add(other.getUniqueId());
                }
            }

            hiddenFromPlayer.put(viewer.getUniqueId(), toHide);
        }

        // العكس: أخفِ لاعبين الماتش من TAB كل اللي برا الماتش (سبون/لوبي)
        if (!hideFromOutsiders) return;
        for (Player outsider : online) {
            if (groupUUIDs.contains(outsider.getUniqueId())) continue;
            Set<UUID> set = matchHiddenFromOutsider.computeIfAbsent(outsider.getUniqueId(), k -> new HashSet<>());
            for (Player member : group) {
                hideFromTab(outsider, member);
                set.add(member.getUniqueId());
            }
        }
    }

    /**
     * يسترجع الـ TAB الكامل للاعب (عند انتهاء المباراة).
     *
     * @param player اللاعب المراد إعادة TAB الكامل له
     */
    public void restore(Player player) {
        UUID me = player.getUniqueId();
        Set<UUID> hidden = hiddenFromPlayer.remove(me);

        // 1) رجّع للاعب اللي طلع من الماتش TAB الباقين (إلا اللي هم الحين داخل ماتش ثاني)
        if (hidden != null) {
            for (UUID uuid : hidden) {
                Player target = Bukkit.getPlayer(uuid);
                if (target == null || !target.isOnline()) continue;
                if (hideFromOutsiders && hiddenFromPlayer.containsKey(uuid)) {
                    // hide-from-outsiders شغّال والهدف داخل ماتش ثاني — يرجع له لما ينتهي ماتشه
                    // (لو الخيار مطفي، اللي برا الماتش يشوفون اللاعبين اللي داخل ماتشات — فنظهرهم له فوراً)
                    matchHiddenFromOutsider.computeIfAbsent(me, k -> new HashSet<>()).add(uuid);
                } else {
                    showInTab(player, target);
                }
            }
        }

        // 2) رجّع اللاعب نفسه يظهر في TAB اللي برا الماتش
        for (Iterator<Map.Entry<UUID, Set<UUID>>> it = matchHiddenFromOutsider.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Set<UUID>> e = it.next();
            if (!e.getValue().remove(me)) continue;
            Player viewer = Bukkit.getPlayer(e.getKey());
            if (viewer != null && viewer.isOnline()) {
                Set<UUID> viewerHidden = hiddenFromPlayer.get(viewer.getUniqueId());
                if (viewerHidden != null) {
                    // المشاهد نفسه داخل ماتش — يظهر له بعد ما ينتهي ماتشه
                    viewerHidden.add(me);
                } else {
                    showInTab(viewer, player);
                }
            }
            if (e.getValue().isEmpty()) it.remove();
        }

        // 3) مزامنة كاملة بعد الرجوع للسبون — تصلّح أي لاعب ضاع من الـ TAB
        scheduleResync(player);
    }

    /**
     * يرسل للاعب كل اللاعبين اللي المفروض يشوفهم في الـ TAB، ويرسله لهم.
     * آمن للتكرار (ADD_PLAYER على لاعب موجود يستبدل مدخله بنفس البيانات).
     * يحترم: عزل الماتش الحالي + خيار "إخفاء اللاعبين".
     */
    public void resync(Player viewer) {
        if (viewer == null || !viewer.isOnline()) return;
        Collection<? extends Player> online = Bukkit.getOnlinePlayers();

        List<Player> visibleToViewer = new ArrayList<>();
        for (Player other : online) {
            if (shouldShow(viewer, other)) visibleToViewer.add(other);
        }
        sendAddPackets(viewer, visibleToViewer);

        for (Player other : online) {
            if (other.equals(viewer)) continue;
            if (shouldShow(other, viewer)) sendAddPackets(other, Collections.singletonList(viewer));
        }
    }

    /** مزامنة بعد tick (بعد الـ teleport) وبعد ثانية ونص (لو الـ TAB plugin غيّر شي). */
    public void scheduleResync(Player player) {
        if (plugin == null || !plugin.isEnabled()) return;
        UUID id = player.getUniqueId();
        for (long delay : new long[]{2L, 30L}) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) resync(p);
            }, delay);
        }
    }

    private boolean shouldShow(Player viewer, Player target) {
        if (viewer.equals(target)) return true;
        // لو اللاعب مفعّل "إخفاء اللاعبين" — نحترم اختياره
        if (!viewer.canSee(target)) return false;

        Set<UUID> hidden = hiddenFromPlayer.get(viewer.getUniqueId());
        if (hidden != null) return !hidden.contains(target.getUniqueId());   // المشاهد داخل ماتش

        if (!hideFromOutsiders) return true;   // برا الماتش = يشوف الكل (إلا لو أخفاهم بإعداد Hide Players)
        if (hiddenFromPlayer.containsKey(target.getUniqueId())) return false;
        Set<UUID> deferred = matchHiddenFromOutsider.get(viewer.getUniqueId());
        return deferred == null || !deferred.contains(target.getUniqueId());
    }

    /** يرجّع الـ TAB لكل اللاعبين الأونلاين (يُستخدم عند onDisable). */
    public void restoreEveryone() {
        for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) restore(p);
        hiddenFromPlayer.clear();
        matchHiddenFromOutsider.clear();
        for (Player p : Bukkit.getOnlinePlayers()) resync(p);
    }

    /**
     * يسترجع TAB الكامل لكل اللاعبين في المجموعة.
     */
    public void restoreAll(List<Player> group) {
        for (Player p : group) {
            if (p.isOnline()) restore(p);
        }
    }

    /**
     * عندما ينضم لاعب جديد للسيرفر — نضيفه للـ hidden list لكل اللاعبين المعزولين.
     */
    public void onPlayerJoin(Player joined) {
        for (Map.Entry<UUID, Set<UUID>> entry : hiddenFromPlayer.entrySet()) {
            Player viewer = Bukkit.getPlayer(entry.getKey());
            if (viewer == null || !viewer.isOnline()) continue;
            // اللاعب الجديد ليس في مجموعة الـ viewer — أخفِه
            hideFromTab(viewer, joined);
            entry.getValue().add(joined.getUniqueId());
        }

        // اللاعب الجديد ما يشوف اللي داخل ماتشات في TAB
        if (!hideFromOutsiders) return;
        for (UUID uuid : hiddenFromPlayer.keySet()) {
            if (uuid.equals(joined.getUniqueId())) continue;
            Player member = Bukkit.getPlayer(uuid);
            if (member == null || !member.isOnline()) continue;
            hideFromTab(joined, member);
            matchHiddenFromOutsider.computeIfAbsent(joined.getUniqueId(), k -> new HashSet<>()).add(uuid);
        }
    }

    /** تنظيف عند خروج لاعب من السيرفر */
    public void onPlayerQuit(Player player) {
        UUID me = player.getUniqueId();
        matchHiddenFromOutsider.remove(me);
        // لا نخلي أثر قديم يخلي الباقين يعتبرونه "داخل ماتش" للأبد
        hiddenFromPlayer.remove(me);
        for (Iterator<Map.Entry<UUID, Set<UUID>>> it = matchHiddenFromOutsider.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Set<UUID>> e = it.next();
            e.getValue().remove(me);
            if (e.getValue().isEmpty()) it.remove();
        }
    }

    public boolean isIsolated(Player player) {
        return hiddenFromPlayer.containsKey(player.getUniqueId());
    }

    // ──────────────────────────────────────────────
    //  Packet helpers — Paper 1.21.4
    // ──────────────────────────────────────────────

    /**
     * يخفي `target` من TAB list الخاص بـ `viewer`
     * عبر إرسال ClientboundPlayerInfoRemovePacket.
     */
    private void hideFromTab(Player viewer, Player target) {
        try {
            // Paper 1.21: player.hidePlayer(plugin, target) يخفيه من العالم
            // لكن ما يخفيه من TAB — نحتاج packet مباشر.
            // نستخدم getHandle().connection.send() عبر reflection

            Object handle = getHandle(viewer);
            Object connection = getField(handle, "connection");
            Object packet = buildRemovePacket(target.getUniqueId());
            sendPacket(connection, packet);
        } catch (Exception e) {
            warnPacketFailure("hide", e);
        }
    }

    /**
     * يُعيد إظهار `target` في TAB list الخاص بـ `viewer`
     * عبر إرسال ClientboundPlayerInfoUpdatePacket (ADD_PLAYER).
     */
    private void showInTab(Player viewer, Player target) {
        try {
            Object handle = getHandle(viewer);
            Object connection = getField(handle, "connection");
            sendAddPackets(viewer, Collections.singletonList(target));
        } catch (Exception e) {
            warnPacketFailure("show", e);
        }
    }

    /** يرسل ADD_PLAYER لمجموعة لاعبين في packet واحد. */
    private void sendAddPackets(Player viewer, List<Player> targets) {
        if (targets.isEmpty()) return;
        try {
            Object connection = getField(getHandle(viewer), "connection");
            Class<?> cls = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
            Method factory = cls.getMethod("createPlayerInitializing", Collection.class);
            List<Object> handles = new ArrayList<>(targets.size());
            for (Player t : targets) handles.add(getHandle(t));
            sendPacket(connection, factory.invoke(null, handles));
        } catch (Exception e) {
            warnPacketFailure("add", e);
        }
    }

    private void warnPacketFailure(String what, Exception e) {
        if (warnedPacketFailure || plugin == null) return;
        warnedPacketFailure = true;
        plugin.getLogger().log(java.util.logging.Level.WARNING,
                "[TabIsolation] packet '" + what + "' failed - TAB isolation may not work on this server version", e);
    }

    // ── NMS Reflection ──────────────────────────────

    private static Object getHandle(Player player) throws Exception {
        Method getHandle = player.getClass().getMethod("getHandle");
        return getHandle.invoke(player);
    }

    private static Object getField(Object obj, String name) throws Exception {
        java.lang.reflect.Field f = findField(obj.getClass(), name);
        f.setAccessible(true);
        return f.get(obj);
    }

    private static java.lang.reflect.Field findField(Class<?> cls, String name) throws NoSuchFieldException {
        while (cls != null) {
            try { return cls.getDeclaredField(name); }
            catch (NoSuchFieldException ignored) {}
            cls = cls.getSuperclass();
        }
        throw new NoSuchFieldException(name);
    }

    private static void sendPacket(Object connection, Object packet) throws Exception {
        // Paper 1.21: ServerGamePacketListenerImpl.send(Packet)
        for (Method m : connection.getClass().getMethods()) {
            if (m.getName().equals("send") && m.getParameterCount() == 1) {
                Class<?> paramType = m.getParameterTypes()[0];
                if (paramType.isAssignableFrom(packet.getClass())) {
                    m.invoke(connection, packet);
                    return;
                }
            }
        }
    }

    /**
     * يبني ClientboundPlayerInfoRemovePacket لـ UUID واحد.
     * net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket(List<UUID>)
     */
    private static Object buildRemovePacket(UUID uuid) throws Exception {
        Class<?> cls = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
        return cls.getConstructor(List.class).newInstance(Collections.singletonList(uuid));
    }
}
