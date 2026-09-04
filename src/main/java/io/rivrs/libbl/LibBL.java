package io.rivrs.libbl;

import java.util.List;

import org.bukkit.Chunk;
import org.bukkit.World;
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
import io.rivrs.libbl.utils.FieldOfView;
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
    @Getter
    private static boolean FIELD_OF_VIEW_ENABLED;
    @Getter
    private static double FIELD_OF_VIEW_COS_HALF_ANGLE;
    @Getter
    private static double FIELD_OF_VIEW_ENTITY_RADIUS;
    @Getter
    private static double FIELD_OF_VIEW_ENTITY_OFFSET;
    @Getter
    private static long FIELD_OF_VIEW_GRACE_PERIOD;
    @Getter
    private static boolean LINE_OF_SIGHT_ENABLED;
    @Getter
    private static double LINE_OF_SIGHT_MAX_DISTANCE;
    @Getter
    private static int CHUNK_REFRESH_INTERVAL;
    @Getter
    private static int CHUNK_REFRESH_LIMIT;

    private EntityService entityService;
    private BlockService blockService;
    private ViewerService viewerService;
    private WorldService worldService;

    public static LibBL get() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        // Initialize static variables
        int simulationDistance = this.getServer().getSimulationDistance() * 16;
        ENTITY_SIMULATION_DISTANCE_SQR = simulationDistance * simulationDistance;
        int renderDistance = this.getServer().getViewDistance() * 16;
        RENDER_DISTANCE_SQR = renderDistance * renderDistance;

        // Config
        saveDefaultConfig();
        ENTITY_UPDATE_RATE = this.getConfig().getInt("entity-update-rate", 250);
        FIELD_OF_VIEW_ENABLED = this.getConfig().getBoolean("visibility.field-of-view.enabled", true);
        FIELD_OF_VIEW_COS_HALF_ANGLE = FieldOfView.cosHalfAngle(this.getConfig().getDouble("visibility.field-of-view.angle", 130.0D));
        FIELD_OF_VIEW_ENTITY_RADIUS = this.getConfig().getDouble("visibility.field-of-view.entity-radius", 1.0D);
        FIELD_OF_VIEW_ENTITY_OFFSET = this.getConfig().getDouble("visibility.field-of-view.entity-offset", 1.0D);
        FIELD_OF_VIEW_GRACE_PERIOD = this.getConfig().getLong("visibility.field-of-view.grace-period", 1000L);
        LINE_OF_SIGHT_ENABLED = this.getConfig().getBoolean("visibility.line-of-sight.enabled", false);
        LINE_OF_SIGHT_MAX_DISTANCE = this.getConfig().getDouble("visibility.line-of-sight.max-distance", 64.0D);
        CHUNK_REFRESH_INTERVAL = this.getConfig().getInt("chunk-cache.refresh-interval", 20);
        CHUNK_REFRESH_LIMIT = this.getConfig().getInt("chunk-cache.refresh-limit", 32);

        // Services
        this.entityService = new EntityService(this);
        this.blockService = new BlockService(this);
        this.viewerService = new ViewerService(this);
        this.worldService = new WorldService(this);

        // Cache the chunks that are already loaded, the listener only sees the next ones
        for (World world : this.getServer().getWorlds()) {
            for (Chunk chunk : world.getLoadedChunks()) {
                this.worldService.registerChunk(chunk);
            }
        }

        this.blockService.init();
        this.entityService.init();
        this.viewerService.init();
        this.worldService.init();


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
        this.worldService.shutdown();

        instance = null;
    }
}
