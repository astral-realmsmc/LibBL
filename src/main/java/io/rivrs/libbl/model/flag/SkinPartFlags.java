package io.rivrs.libbl.model.flag;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * Skin overlay parts of a player shaped entity, a set bit means the part is <strong>shown</strong>.
 *
 * @see io.rivrs.libbl.model.entities.entity.living.PacketMannequin
 */
@RequiredArgsConstructor
@Getter
public enum SkinPartFlags implements Flag {
    CAPE(0x01),
    JACKET(0x02),
    LEFT_SLEEVE(0x04),
    RIGHT_SLEEVE(0x08),
    LEFT_PANTS_LEG(0x10),
    RIGHT_PANTS_LEG(0x20),
    HAT(0x40),
    ;

    private final int value;
}
