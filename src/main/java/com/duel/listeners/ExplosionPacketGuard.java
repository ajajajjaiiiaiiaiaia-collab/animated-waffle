package com.duel.listeners;

import com.duel.DuelPlugin;
import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.util.Vector3d;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerExplosion;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;

/** يصفّر دفعة الانفجار/الويند تشارج داخل الباكيت للمتفرجين فقط (الصوت والجزيئات تبقى). */
public final class ExplosionPacketGuard extends PacketListenerAbstract {

    private static ExplosionPacketGuard instance;
    private final DuelPlugin plugin;
    private volatile Method setter;
    private volatile boolean failed = false;
    private volatile boolean confirmed = false;

    private ExplosionPacketGuard(DuelPlugin plugin) {
        super(PacketListenerPriority.HIGH);
        this.plugin = plugin;
    }

    public static void register(DuelPlugin plugin) {
        instance = new ExplosionPacketGuard(plugin);
        PacketEvents.getAPI().getEventManager().registerListener(instance);
    }

    public static void unregister() {
        if (instance == null) return;
        PacketEvents.getAPI().getEventManager().unregisterListener(instance);
        instance = null;
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (failed) return;
        if (event.getPacketType() != PacketType.Play.Server.EXPLOSION) return;
        Object user = event.getPlayer();
        if (!(user instanceof Player p) || !plugin.getFlySpectatorManager().isActive(p)) return;
        try {
            WrapperPlayServerExplosion w = new WrapperPlayServerExplosion(event);
            if (!zero(w)) { fail("no knockback setter in this PacketEvents version"); return; }
            event.markForReEncode(true);
            if (!confirmed) {
                confirmed = true;
                plugin.getLogger().info("[ExplosionPacketGuard] zeroed explosion knockback for spectator " + p.getName());
            }
        } catch (Throwable t) {
            fail(t.toString());
        }
    }

    /** نبحث عن الدالة بالـ reflection عشان تشتغل مع أكثر من إصدار من PacketEvents. */
    private boolean zero(WrapperPlayServerExplosion w) throws Exception {
        Method m = setter;
        if (m == null) {
            for (String name : new String[]{"setKnockback", "setPlayerMotion"}) {
                try {
                    m = WrapperPlayServerExplosion.class.getMethod(name, Vector3d.class);
                    break;
                } catch (NoSuchMethodException ignored) {}
            }
            if (m == null) return false;
            setter = m;
        }
        m.invoke(w, new Vector3d(0, 0, 0));
        return true;
    }

    /** لو فشل الباكيت نرجع للطريقة القديمة تلقائياً. */
    private void fail(String why) {
        failed = true;
        plugin.getFlySpectatorManager().setPacketGuard(false);
        plugin.getLogger().warning("[ExplosionPacketGuard] disabled, using fallback: " + why);
    }
}
