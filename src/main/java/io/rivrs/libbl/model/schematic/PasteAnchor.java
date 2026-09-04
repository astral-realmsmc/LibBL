package io.rivrs.libbl.model.schematic;

/// Defines how a [Schematic] is positioned relatively to the location it is pasted at.
public enum PasteAnchor {

    /// The block at the relative position `0/0/0` is placed exactly on the given location.
    ///
    /// This is the most intuitive mode : the lowest north-west corner of the schematic lands where you asked for.
    CORNER,

    /// The schematic `Offset` is applied to the given location before placing anything.
    ///
    /// This reproduces WorldEdit's `//paste` behaviour : the schematic keeps the position it had
    /// relatively to the player who copied it.
    ORIGIN
}
