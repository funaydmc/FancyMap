package dev.funayd.fancyMap.map;

/** Immutable client-side item-frame and map identifiers for one canvas tile. */
record ClientMapFrame(
        int entityId,
        int mapId,
        double x,
        double y,
        double z,
        int direction
) {
}
