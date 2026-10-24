package io.rivrs.libbl.utils;

import lombok.experimental.UtilityClass;

/// Packs a block position into a single `long`, the way vanilla does : 26 bits of x, 12 bits of y and
/// 26 bits of z.
///
/// Lets a fake block key the registries and remember where it stands without holding a
/// [org.bukkit.Location] - which carries a weak reference to its world, hashes six floating point
/// fields and costs two objects per block.
///
/// The range is `-33 554 432 .. 33 554 431` on x and z, well past the world border, and
/// `-2048 .. 2047` on y, well past the tallest world.
@UtilityClass
public class BlockPos {

    private static final int X_Z_BITS = 26;
    private static final int Y_BITS = 12;
    private static final long X_Z_MASK = (1L << X_Z_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private static final int Y_SHIFT = X_Z_BITS;
    private static final int X_SHIFT = X_Z_BITS + Y_BITS;

    public static long pack(int x, int y, int z) {
        return ((x & X_Z_MASK) << X_SHIFT)
                | ((y & Y_MASK) << Y_SHIFT)
                | (z & X_Z_MASK);
    }

    public static int x(long position) {
        return (int) (position << (64 - X_SHIFT - X_Z_BITS) >> (64 - X_Z_BITS));
    }

    public static int y(long position) {
        return (int) (position << (64 - Y_SHIFT - Y_BITS) >> (64 - Y_BITS));
    }

    public static int z(long position) {
        return (int) (position << (64 - X_Z_BITS) >> (64 - X_Z_BITS));
    }

    /// The key of the 16x16 chunk column holding this position, as [org.bukkit.Chunk#getChunkKey].
    public static long chunkKey(long position) {
        return org.bukkit.Chunk.getChunkKey(x(position) >> 4, z(position) >> 4);
    }
}
