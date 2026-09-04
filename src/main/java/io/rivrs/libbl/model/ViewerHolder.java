package io.rivrs.libbl.model;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Unmodifiable;

import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ViewerHolder {

    void addViewer(Player player);

    void removeViewer(Player viewer);

    /**
     * Removes a viewer from its unique id, works even when the player is offline.
     */
    void removeViewer(UUID uniqueId);

    boolean isViewer(Player player);

    boolean isViewer(UUID uniqueId);

    @Unmodifiable
    Set<UUID> viewers();

    @Unmodifiable
    List<Object> viewersAsChannel();

    @Unmodifiable
    List<Player> viewersAsPlayer();

    void autoViewable(boolean autoViewable);

    boolean isAutoViewable();
}
