package io.rivrs.libbl;

import java.util.List;

import org.bukkit.plugin.java.JavaPlugin;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListenerPriority;

import io.rivrs.libbl.listener.EntityInteractionListener;
import io.rivrs.libbl.listener.MapListener;
import io.rivrs.libbl.listener.PlayerListener;
import io.rivrs.libbl.listener.WorldListener;
import io.rivrs.libbl.service.BlockService;
import io.rivrs.libbl.service.EntityService;
import io.rivrs.libbl.service.ViewerService;
import io.rivrs.libbl.service.WorldService;
import lombok.Getter;

@Getter
public final class LibBL extends JavaPlugin {

    private static LibBL instance;

    @Getter
    private static int ENTITY_SIMULATION_DISTANCE_SQR;
    @Getter
    private static int RENDER_DISTANCE_SQR;
    @Getter
    private static int ENTITY_UPDATE_RATE;

    private EntityService entityService;
    private BlockService blockService;
    private ViewerService viewerService;
    private WorldService worldService;

    public static LibBL get() {
        return instance;
    }

    @Override
    public void onEnable() {
        // Initialize static variables
        int simulationDistance = this.getServer().getSimulationDistance() * 16;
        ENTITY_SIMULATION_DISTANCE_SQR = simulationDistance * simulationDistance;
        int renderDistance = this.getServer().getViewDistance() * 16;
        RENDER_DISTANCE_SQR = renderDistance * renderDistance;

        // Config
        saveDefaultConfig();
        ENTITY_UPDATE_RATE = this.getConfig().getInt("entity-update-rate", 250);

        // Services
        this.entityService = new EntityService(this);
        this.blockService = new BlockService(this);
        this.viewerService = new ViewerService(this);
        this.worldService = new WorldService();

        this.blockService.init();
        this.entityService.init();
        this.viewerService.init();


        // Listeners
        List.of(
                new PlayerListener(this),
                new WorldListener(this)
        ).forEach(player -> this.getServer().getPluginManager().registerEvents(player, this));

        // Packet listeners
        PacketEvents.getAPI().getEventManager().registerListener(new EntityInteractionListener(this));
        PacketEvents.getAPI().getEventManager().registerListener(new MapListener(this), PacketListenerPriority.HIGHEST);

        instance = this;

    }

    @Override
    public void onDisable() {
        // Service
        this.entityService.shutdown();
        this.blockService.shutdown();
        this.viewerService.shutdown();

        instance = null;
    }
}
