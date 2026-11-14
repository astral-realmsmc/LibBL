package io.rivrs.libbl.listener;

import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.SimplePacketListenerAbstract;
import com.github.retrooper.packetevents.event.simple.PacketPlayReceiveEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.InteractionHand;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientAttack;
import com.github.retrooper.packetevents.wrapper.play.client.WrapperPlayClientInteractEntity;
import com.polaris.polarisUtils.api.PolarisUtilsAPI;
import com.polaris.polarisUtils.api.model.PlayerSnapshot;
import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.event.entity.PacketEntityAttackEvent;
import io.rivrs.libbl.event.entity.PacketEntityInteractEvent;
import io.rivrs.libbl.model.entities.PacketEntity;
import lombok.RequiredArgsConstructor;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MainHand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns the clicks on packet entities into {@link PacketEntityAttackEvent}s and
 * {@link PacketEntityInteractEvent}s. Both are fired asynchronously, from the Netty thread of the
 * player: a listener touching the world has to hop onto the player's scheduler first.
 */
@RequiredArgsConstructor
public class EntityInteractionListener extends SimplePacketListenerAbstract {

    /**
     * A plain interact this soon after an interact-at on the same entity with the same hand is the
     * same click, which the client sends twice.
     */
    private static final long SAME_CLICK = 100;

    private final LibBL plugin;
    private final Map<UUID, InteractAt> lastInteractAt = new ConcurrentHashMap<>();

    @Override
    public void onPacketPlayReceive(PacketPlayReceiveEvent event) {
        if (event.getPacketType().equals(PacketType.Play.Client.ATTACK)) {
            this.onAttack(event);
            return;
        }
        if (!event.getPacketType().equals(PacketType.Play.Client.INTERACT_ENTITY))
            return;

        WrapperPlayClientInteractEntity packet = new WrapperPlayClientInteractEntity(event);
        this.plugin.entityService()
                .findById(packet.getEntityId())
                .ifPresent(packetEntity -> {
                    event.setCancelled(true);

                    Player player = event.getPlayer();

                    if (!this.inReach(player, packetEntity)) {
                        // Too far away, the packet is likely spoofed and we ignore it
                        return;
                    }

                    MainHand hand = packet.getHand().equals(InteractionHand.MAIN_HAND) ? MainHand.RIGHT : MainHand.LEFT;
                    boolean sneaking = packet.isSneaking().orElse(false);

                    switch (packet.getAction()) {
                        case ATTACK -> new PacketEntityAttackEvent(packetEntity, player, sneaking, hand).callEvent();
                        case INTERACT_AT -> {
                            this.lastInteractAt.put(player.getUniqueId(), new InteractAt(packet.getEntityId(), packet.getHand(), System.currentTimeMillis()));
                            new PacketEntityInteractEvent(packetEntity, player, sneaking, hand, PacketEntityInteractEvent.Action.INTERACT_AT,
                                    packet.getTarget().orElse(null)).callEvent();
                        }
                        case INTERACT -> {
                            InteractAt last = this.lastInteractAt.remove(player.getUniqueId());
                            if (last != null && last.isSameClick(packet.getEntityId(), packet.getHand()))
                                return;
                            new PacketEntityInteractEvent(packetEntity, player, sneaking, hand, PacketEntityInteractEvent.Action.INTERACT, null).callEvent();
                        }
                    }
                });
    }

    /**
     * From 26.1 the client sends attacks as their own packet, which carries neither hand nor
     * sneaking: an attack is always the main hand, and sneaking is read off the player.
     */
    private void onAttack(PacketPlayReceiveEvent event) {
        WrapperPlayClientAttack packet = new WrapperPlayClientAttack(event);
        this.plugin.entityService()
                .findById(packet.getEntityId())
                .ifPresent(packetEntity -> {
                    event.setCancelled(true);

                    Player player = event.getPlayer();
                    if (!this.inReach(player, packetEntity))
                        return;

                    new PacketEntityAttackEvent(packetEntity, player, player.isSneaking(), MainHand.RIGHT).callEvent();
                });
    }

    /**
     * Whether the player stands within 5 blocks of the entity, read off its snapshot: the packet
     * comes in on Netty, which does not own the player on Folia.
     */
    private boolean inReach(Player player, PacketEntity entity) {
        Location location = entity.location();
        PlayerSnapshot snapshot = PolarisUtilsAPI.get().snapshot(player.getUniqueId()).orElse(null);
        return snapshot != null
               && location != null
               && location.getWorld() != null
               && snapshot.isInWorld(location.getWorld().key())
               && snapshot.distanceSquared(location.getX(), location.getY(), location.getZ()) <= 25;
    }

    @Override
    public PacketListenerPriority getPriority() {
        return PacketListenerPriority.MONITOR;
    }

    private record InteractAt(int entityId, InteractionHand hand, long at) {

        boolean isSameClick(int entityId, InteractionHand hand) {
            return this.entityId == entityId && this.hand == hand && System.currentTimeMillis() - this.at <= SAME_CLICK;
        }
    }
}
