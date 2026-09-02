package io.rivrs.libbl.task;

import org.bukkit.scheduler.BukkitRunnable;

import io.rivrs.libbl.LibBL;
import io.rivrs.libbl.service.WorldService;
import lombok.RequiredArgsConstructor;

/**
 * Rebuilds, from the main thread, the chunk snapshots that were flagged as outdated by a block change,
 * so that the asynchronous line of sight checks never work on stale data.
 * <p>
 * The amount of chunks rebuilt per run is capped, a mass edit (WorldEdit, explosions, ...) is therefore
 * spread over several runs instead of stalling a single tick.
 */
@RequiredArgsConstructor
public class ChunkRefreshTask extends BukkitRunnable {

    private final WorldService service;

    @Override
    public void run() {
        this.service.refreshDirtyChunks(LibBL.CHUNK_REFRESH_LIMIT());
    }
}
