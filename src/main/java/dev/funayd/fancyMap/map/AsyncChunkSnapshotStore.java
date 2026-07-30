package dev.funayd.fancyMap.map;

import org.bukkit.World;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight map-session view registered with the shared chunk scheduler.
 *
 * <p>A session stores only its current viewport and a revision counter. Chunk
 * requests and snapshots belong to {@link GlobalChunkSnapshotScheduler}, so a
 * large viewport cannot allocate one request object per chunk.</p>
 */
public final class AsyncChunkSnapshotStore implements AutoCloseable {
    private final World world;
    private final PersistentChunkRenderCache renderCache;
    private final GlobalChunkSnapshotScheduler scheduler;
    private final AtomicLong snapshotVersion = new AtomicLong();
    private volatile Viewport viewport;
    private volatile boolean closed;

    /**
     * Creates a session viewport backed by the shared scheduler.
     *
     * @param world world shown by this session
     * @param renderCache persistent rendered-chunk cache
     * @param scheduler shared bounded chunk scheduler
     */
    public AsyncChunkSnapshotStore(
            World world,
            PersistentChunkRenderCache renderCache,
            GlobalChunkSnapshotScheduler scheduler
    ) {
        this.world = world;
        this.renderCache = renderCache;
        this.scheduler = scheduler;
        renderCache.register(this);
        scheduler.register(this);
    }

    /**
     * Replaces the viewport being filled, prioritizing its center chunk.
     *
     * @param minChunkX minimum visible chunk X
     * @param maxChunkX maximum visible chunk X
     * @param minChunkZ minimum visible chunk Z
     * @param maxChunkZ maximum visible chunk Z
     * @param centerChunkX viewport center chunk X
     * @param centerChunkZ viewport center chunk Z
     */
    public void retainViewport(
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ,
            int centerChunkX,
            int centerChunkZ
    ) {
        Viewport next = new Viewport(
                minChunkX,
                maxChunkX,
                minChunkZ,
                maxChunkZ,
                centerChunkX,
                centerChunkZ
        );
        if (!next.equals(viewport)) {
            viewport = next;
            scheduler.wake();
        }
    }

    /**
     * Returns the revision used to trigger a progressive canvas refresh.
     *
     * @return incrementing revision of cached chunks in this viewport
     */
    public long snapshotVersion() {
        return snapshotVersion.get();
    }

    /**
     * Returns the persistent cache shared by all map sessions.
     *
     * @return rendered-chunk cache
     */
    public PersistentChunkRenderCache renderCache() {
        return renderCache;
    }

    /**
     * Invalidates an active viewport after a world change.
     *
     * @param chunkX changed chunk X
     * @param chunkZ changed chunk Z
     */
    public void invalidate(int chunkX, int chunkZ) {
        if (contains(chunkX, chunkZ)) {
            snapshotVersion.incrementAndGet();
            scheduler.wake();
        }
    }

    /** Invalidates one chunk only when it belongs to this session's world. */
    void invalidateIfWorld(World changedWorld, int chunkX, int chunkZ) {
        if (world.getUID().equals(changedWorld.getUID())) {
            invalidate(chunkX, chunkZ);
        }
    }

    /**
     * Releases this session's viewport registration.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        viewport = null;
        scheduler.unregister(this);
        renderCache.unregister(this);
    }

    /** Returns bounded shared scheduler counters for debug output. */
    public String debugSummary() {
        return scheduler.debugSummary();
    }

    /** Returns this session's world. */
    World world() {
        return world;
    }

    /** Returns whether this session remains active. */
    boolean isClosed() {
        return closed;
    }

    /** Returns whether a chunk is visible in the current viewport. */
    boolean retains(UUID worldId, long key) {
        return !closed && world.getUID().equals(worldId)
                && contains(chunkX(key), chunkZ(key));
    }

    /** Returns the next center-first candidate chunk, or {@code null}. */
    ChunkCoordinate nextCandidate() {
        Viewport current = viewport;
        return closed || current == null ? null : current.next();
    }

    /** Notifies the session that a rendered chunk became available. */
    void cached(World cachedWorld, int chunkX, int chunkZ) {
        if (world.getUID().equals(cachedWorld.getUID()) && contains(chunkX, chunkZ)) {
            snapshotVersion.incrementAndGet();
        }
    }

    /** Returns whether the current viewport contains one chunk. */
    private boolean contains(int chunkX, int chunkZ) {
        Viewport current = viewport;
        return current != null && current.contains(chunkX, chunkZ);
    }

    /** Packs a chunk coordinate into the cache key format. */
    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Extracts chunk X from a packed key. */
    private static int chunkX(long key) {
        return (int) (key >> 32);
    }

    /** Extracts chunk Z from a packed key. */
    private static int chunkZ(long key) {
        return (int) key;
    }

    /** One chunk coordinate selected by a viewport cursor. */
    record ChunkCoordinate(int x, int z) {
    }

    /**
     * Mutable center-first square-ring cursor for one viewport.
     */
    private static final class Viewport {
        private final int minX;
        private final int maxX;
        private final int minZ;
        private final int maxZ;
        private final int centerX;
        private final int centerZ;
        private final int maxRadius;
        private int radius;
        private int perimeterIndex;

        private Viewport(
                int minX,
                int maxX,
                int minZ,
                int maxZ,
                int centerX,
                int centerZ
        ) {
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.centerX = centerX;
            this.centerZ = centerZ;
            maxRadius = Math.max(
                    Math.max(Math.abs(minX - centerX), Math.abs(maxX - centerX)),
                    Math.max(Math.abs(minZ - centerZ), Math.abs(maxZ - centerZ))
            );
        }

        private boolean contains(int chunkX, int chunkZ) {
            return chunkX >= minX && chunkX <= maxX && chunkZ >= minZ && chunkZ <= maxZ;
        }

        private ChunkCoordinate next() {
            while (radius <= maxRadius) {
                if (radius == 0) {
                    radius = 1;
                    return contains(centerX, centerZ)
                            ? new ChunkCoordinate(centerX, centerZ)
                            : null;
                }
                int count = radius * 8;
                if (perimeterIndex == count) {
                    radius++;
                    perimeterIndex = 0;
                    continue;
                }
                ChunkCoordinate coordinate = perimeterCoordinate(radius, perimeterIndex++);
                if (contains(coordinate.x(), coordinate.z())) {
                    return coordinate;
                }
            }
            return null;
        }

        private ChunkCoordinate perimeterCoordinate(int ring, int index) {
            int topLength = ring * 2 + 1;
            if (index < topLength) {
                return new ChunkCoordinate(centerX - ring + index, centerZ - ring);
            }
            index -= topLength;
            int edgeLength = ring * 2;
            if (index < edgeLength) {
                return new ChunkCoordinate(centerX + ring, centerZ - ring + 1 + index);
            }
            index -= edgeLength;
            if (index < edgeLength) {
                return new ChunkCoordinate(centerX + ring - 1 - index, centerZ + ring);
            }
            index -= edgeLength;
            return new ChunkCoordinate(centerX - ring, centerZ + ring - 1 - index);
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof Viewport viewport)) {
                return false;
            }
            return minX == viewport.minX && maxX == viewport.maxX
                    && minZ == viewport.minZ && maxZ == viewport.maxZ
                    && centerX == viewport.centerX && centerZ == viewport.centerZ;
        }

        @Override
        public int hashCode() {
            int result = Integer.hashCode(minX);
            result = 31 * result + Integer.hashCode(maxX);
            result = 31 * result + Integer.hashCode(minZ);
            result = 31 * result + Integer.hashCode(maxZ);
            result = 31 * result + Integer.hashCode(centerX);
            return 31 * result + Integer.hashCode(centerZ);
        }
    }
}
