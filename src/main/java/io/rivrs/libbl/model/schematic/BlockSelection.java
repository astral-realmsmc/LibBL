package io.rivrs.libbl.model.schematic;

/// Which blocks of a [Schematic] an iteration or a paste actually hands over.
public enum BlockSelection {

    /// Every block, air included. Use it to blank an area out rather than to build in it.
    ALL,

    /// Everything but air.
    SOLID,

    /// Only the blocks somebody could actually see : air is skipped, and so is anything walled in on
    /// all six sides by full opaque blocks.
    ///
    /// A solid build collapses to its shell, which is a big cut in fake blocks, in packets and in
    /// memory for the exact same result on screen. The blocks are gone though, so a viewer who breaks
    /// through the shell sees whatever the real world holds behind it rather than the inside of the
    /// schematic.
    VISIBLE
}
