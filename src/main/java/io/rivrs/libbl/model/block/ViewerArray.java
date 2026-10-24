package io.rivrs.libbl.model.block;

import java.util.UUID;

/// Copy on write helpers for the viewer array of a [FakeBlock].
///
/// A fake block holds a handful of viewers at most and is read far more often than written, so a
/// plain array beats a `CopyOnWriteArraySet` : it is a single object instead of three, and an empty
/// one costs nothing at all since [#EMPTY] is shared.
final class ViewerArray {

    static final UUID[] EMPTY = new UUID[0];

    private ViewerArray() {
    }

    static boolean contains(UUID[] viewers, UUID uniqueId) {
        for (UUID viewer : viewers)
            if (viewer.equals(uniqueId))
                return true;

        return false;
    }

    /// @return an array holding the viewer, or the given one untouched when it was already in
    static UUID[] with(UUID[] viewers, UUID uniqueId) {
        if (contains(viewers, uniqueId))
            return viewers;

        UUID[] updated = new UUID[viewers.length + 1];
        System.arraycopy(viewers, 0, updated, 0, viewers.length);
        updated[viewers.length] = uniqueId;
        return updated;
    }

    /// @return an array without the viewer, or the given one untouched when it was not in
    static UUID[] without(UUID[] viewers, UUID uniqueId) {
        for (int index = 0; index < viewers.length; index++) {
            if (!viewers[index].equals(uniqueId))
                continue;

            if (viewers.length == 1)
                return EMPTY;

            UUID[] updated = new UUID[viewers.length - 1];
            System.arraycopy(viewers, 0, updated, 0, index);
            System.arraycopy(viewers, index + 1, updated, index, viewers.length - index - 1);
            return updated;
        }

        return viewers;
    }
}
