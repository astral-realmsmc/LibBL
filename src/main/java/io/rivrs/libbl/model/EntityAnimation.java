package io.rivrs.libbl.model;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityAnimation;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Client side animations an entity can be told to play.
 * <p>
 * The damage tilt is not one of them, it has its own packet since 1.19.4, see
 * {@link io.rivrs.libbl.model.entities.PacketEntity#hurt(float)}.
 */
@RequiredArgsConstructor
@Getter
public enum EntityAnimation {
    SWING_MAIN_HAND(WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_MAIN_ARM),
    WAKE_UP(WrapperPlayServerEntityAnimation.EntityAnimationType.WAKE_UP),
    SWING_OFF_HAND(WrapperPlayServerEntityAnimation.EntityAnimationType.SWING_OFF_HAND),
    CRITICAL_HIT(WrapperPlayServerEntityAnimation.EntityAnimationType.CRITICAL_HIT),
    MAGIC_CRITICAL_HIT(WrapperPlayServerEntityAnimation.EntityAnimationType.MAGIC_CRITICAL_HIT),
    ;

    private final WrapperPlayServerEntityAnimation.EntityAnimationType type;
}
