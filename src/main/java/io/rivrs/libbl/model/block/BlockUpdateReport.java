package io.rivrs.libbl.model.block;

/// What a batched block update cost, returned by [io.rivrs.libbl.service.BlockService#placeAll]
/// and [io.rivrs.libbl.service.BlockService#removeAll].
///
/// @param blocks       how many blocks actually changed state, blocks that were already in that
///                     state - or whose event was cancelled - are not counted
/// @param viewers      how many players the packets went to
/// @param packets      how many packets were sent, every viewer summed up
/// @param prepareNanos time spent firing the events and picking the viewers up
/// @param buildNanos   time spent grouping the blocks per chunk section and encoding the packets
/// @param sendNanos    time spent handing the packets over to netty
public record BlockUpdateReport(int blocks, int viewers, int packets,
                                long prepareNanos, long buildNanos, long sendNanos) {

    public static final BlockUpdateReport EMPTY = new BlockUpdateReport(0, 0, 0, 0L, 0L, 0L);

    public long totalNanos() {
        return this.prepareNanos + this.buildNanos + this.sendNanos;
    }

    /// A rough idea of what keeping these blocks around costs in the heap, in bytes.
    public long approximateHeapSize() {
        return (long) this.blocks * FakeBlock.APPROXIMATE_HEAP_SIZE;
    }
}
