package io.rivrs.libbl.event.entity;

import com.github.retrooper.packetevents.util.Vector3f;
import io.rivrs.libbl.model.entities.PacketEntity;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.MainHand;
import org.jetbrains.annotations.Nullable;

/**
 * A player right-clicked a packet entity. Fired once per click: the client sends an interact-at
 * (with where the entity was clicked) and, for most entities, a plain interact right after; the
 * second one is dropped.
 */
@Getter
public class PacketEntityInteractEvent extends PacketEntityEvent {

    private final Player player;
    private final boolean sneaking;
    private final MainHand hand;
    private final Action action;
    /**
     * Where the entity was clicked, relative to its position; {@code null} for a plain interact.
     */
    private final @Nullable Vector3f target;

    public PacketEntityInteractEvent(PacketEntity entity, Player player, boolean sneaking, MainHand hand) {
        this(entity, player, sneaking, hand, Action.INTERACT, null);
    }

    public PacketEntityInteractEvent(PacketEntity entity, Player player, boolean sneaking, MainHand hand, Action action, @Nullable Vector3f target) {
        super(true, entity);
        this.player = player;
        this.sneaking = sneaking;
        this.hand = hand;
        this.action = action;
        this.target = target;
    }

    public enum Action {
        INTERACT,
        INTERACT_AT
    }
}
