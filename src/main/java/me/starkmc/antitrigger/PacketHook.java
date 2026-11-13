package me.starkmc.antitrigger;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketReceiveEvent;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientPlayerFlying;
import java.util.UUID;

/**
 * Optional: records the exact millisecond a player last moved the mouse, from the raw packet.
 * Only loaded when PacketEvents is installed; the plugin works without it.
 */
final class PacketHook extends PacketListenerAbstract
{
    private final AntiTriggerPlugin plugin;

    PacketHook(AntiTriggerPlugin plugin)
    {
        super(PacketListenerPriority.LOW);
        this.plugin = plugin;
    }

    void register()
    {
        PacketEvents.getAPI().getEventManager().registerListener(this);
    }

    void unregister()
    {
        PacketEvents.getAPI().getEventManager().unregisterListener(this);
    }

    @Override
    public void onPacketReceive(PacketReceiveEvent event)
    {
        if (event.getUser() == null || event.getUser().getUUID() == null)
        {
            return;
        }

        if (WrapperPlayClientPlayerFlying.isFlying(event.getPacketType()))
        {
            WrapperPlayClientPlayerFlying flying = new WrapperPlayClientPlayerFlying(event);

            if (flying.hasRotationChanged())
            {
                UUID id = event.getUser().getUUID();
                this.plugin.recordRotation(id, System.currentTimeMillis());
            }
        }
    }
}
