package dev.funayd.fancyMap.packet;

import com.github.retrooper.packetevents.protocol.world.Location;

/** Creates PacketEvents locations without leaking protocol type-name clashes. */
public final class PacketLocations {
    private PacketLocations() {
    }

    /**
     * Creates a protocol location from coordinates and rotation.
     *
     * @param x X coordinate
     * @param y Y coordinate
     * @param z Z coordinate
     * @param yaw yaw angle
     * @param pitch pitch angle
     * @return protocol location
     */
    public static Location at(
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) {
        return new Location(x, y, z, yaw, pitch);
    }
}
