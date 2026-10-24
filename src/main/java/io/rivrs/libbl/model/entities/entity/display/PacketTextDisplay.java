package io.rivrs.libbl.model.entities.entity.display;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import io.rivrs.libbl.model.entities.entity.PacketDisplay;
import io.rivrs.libbl.model.flag.Flag;
import io.rivrs.libbl.model.flag.TextDisplayFlags;
import lombok.Getter;
import net.kyori.adventure.text.Component;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

@Getter
public class PacketTextDisplay extends PacketDisplay {

    private Component text = Component.empty();
    private int lineWidth = 200;
    /**
     * Sent with its alpha: a colour without one is fully transparent. The default stays what the
     * background always looked like, invisible; {@code null} sends the vanilla one.
     */
    private Color backgroundColor = Color.fromARGB(0, 0, 0, 0);
    private byte opacity = -1;

    public PacketTextDisplay(Location location) {
        super(EntityType.TEXT_DISPLAY, location);
    }

    public PacketTextDisplay(UUID uniqueId, Location location) {
        super(uniqueId, EntityType.TEXT_DISPLAY, location);
    }

    public PacketTextDisplay(int id, UUID uniqueId, Location location) {
        super(id, uniqueId, EntityType.TEXT_DISPLAY, location);
    }

    public void text(Component text) {
        this.text = text;
        this.updateMetadata();
    }

    public void lineWidth(int lineWidth) {
        this.lineWidth = lineWidth;
        this.updateMetadata();
    }

    public void backgroundColor(Color backgroundColor) {
        this.backgroundColor = backgroundColor;
        this.updateMetadata();
    }

    public void opacity(byte opacity) {
        this.opacity = opacity;
        this.updateMetadata();
    }

    @Override
    public @NotNull List<EntityData<?>> entityData(@NotNull ClientVersion clientVersion) {
        List<EntityData<?>> entityData = super.entityData(clientVersion);
        entityData.add(new EntityData<>(23, EntityDataTypes.ADV_COMPONENT, this.text));
        entityData.add(new EntityData<>(24, EntityDataTypes.INT, this.lineWidth));
        entityData.add(new EntityData<>(25, EntityDataTypes.INT, this.backgroundColor == null ? 0x40000000 : this.backgroundColor.asARGB()));
        entityData.add(new EntityData<>(26, EntityDataTypes.BYTE, this.opacity));
        entityData.add(new EntityData<>(27, EntityDataTypes.BYTE, Flag.toBitMask(this.flags(TextDisplayFlags.class))));
        return entityData;
    }


}
