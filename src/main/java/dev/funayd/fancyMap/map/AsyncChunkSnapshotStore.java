package dev.funayd.fancyMap.map;

import org.bukkit.Bukkit;
import org.bukkit.ChunkSnapshot;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Comparator;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, prioritized loader for chunk snapshots.
 *
 * <p>Chunk requests are scheduled on the main thread in small batches while
 * snapshot processing remains asynchronous. Completed snapshots are released
 * after validation so large viewports do not retain all world data in memory.</p>
 */
public final class AsyncChunkSnapshotStore implements AutoCloseable {
    // ponytail: keep main-thread chunk requests bounded; increase only after profiling.
    private static final int REQUESTS_PER_TICK = 2;
    private static final int MAX_IN_FLIGHT_REQUESTS = 8;
    private static final int MAX_VALIDATED_CHUNKS = 32_768;
    private static final long RETRY_DELAY_TICKS = 20L;

    private final JavaPlugin plugin;
    private final World world;
    private final ConcurrentMap<Long, CompletableFuture<ChunkSnapshot>> snapshots =
            new ConcurrentHashMap<>();
    private final Set<Long> validated = ConcurrentHashMap.newKeySet();
    private final Set<Long> unavailable = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<Long, Request> queuedRequests =
            new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<Request> requestQueue =
            new PriorityBlockingQueue<>(64, Comparator
                    .comparingLong(Request::priority)
                    .thenComparingLong(Request::sequence));
    private final AtomicBoolean drainScheduled = new AtomicBoolean();
    private final AtomicInteger inFlightRequests = new AtomicInteger();
    private final AtomicLong snapshotVersion = new AtomicLong();
    private final AtomicLong requestSequence = new AtomicLong();
    private final AtomicLong snapshotLoadCount = new AtomicLong();
    private final AtomicLong snapshotLoadNanos = new AtomicLong();
    private final AtomicLong longestSnapshotLoadNanos = new AtomicLong();
    private final PersistentChunkRenderCache renderCache;
    private volatile boolean closed;
    private volatile Viewport retainedViewport;

    /**
     * Creates a store for one world and one map session.
     *
     * @param plugin owning plugin
     * @param world world whose chunks are requested
     * @param renderCache persistent rendered-chunk cache
     */
    public AsyncChunkSnapshotStore(
            JavaPlugin plugin,
            World world,
            PersistentChunkRenderCache renderCache
    ) {
        this.plugin = plugin;
        this.world = world;
        this.renderCache = renderCache;
        renderCache.register(this);
    }

    /**
     * Requests a chunk with default priority.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return snapshot future, or an already-completed future for validated data
     */
    public CompletableFuture<ChunkSnapshot> request(int chunkX, int chunkZ) {
        return request(chunkX, chunkZ, 0L);
    }

    /**
     * Requests a chunk, where lower priority values load sooner.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param priority queue priority
     * @return snapshot future
     */
    public CompletableFuture<ChunkSnapshot> request(
            int chunkX,
            int chunkZ,
            long priority
    ) {
        long key = chunkKey(chunkX, chunkZ);
        if (unavailable.contains(key)) {
            return CompletableFuture.completedFuture(null);
        }
        if (validated.contains(key)
                && renderCache.get(world, chunkX, chunkZ) != null) {
            return CompletableFuture.completedFuture(null);
        }
        validated.remove(key);
        CompletableFuture<ChunkSnapshot> future = snapshots.computeIfAbsent(
                key,
                ignored -> new CompletableFuture<>()
        );
        Request request = new Request(
                chunkX,
                chunkZ,
                key,
                Math.max(0L, priority),
                requestSequence.getAndIncrement()
        );
        if (!closed && !future.isDone()
                && queuedRequests.putIfAbsent(key, request) == null) {
            requestQueue.add(request);
            scheduleDrain();
        }
        return future;
    }

    /**
     * Discards pending work outside the newest viewport.
     *
     * @param minChunkX minimum visible chunk X
     * @param maxChunkX maximum visible chunk X
     * @param minChunkZ minimum visible chunk Z
     * @param maxChunkZ maximum visible chunk Z
     */
    public void retainViewport(
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
    ) {
        retainedViewport = new Viewport(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        requestQueue.removeIf(request -> !inside(
                request.chunkX(),
                request.chunkZ(),
                minChunkX,
                maxChunkX,
                minChunkZ,
                maxChunkZ
        ));
        queuedRequests.entrySet().removeIf(entry -> !inside(
                chunkX(entry.getKey()),
                chunkZ(entry.getKey()),
                minChunkX,
                maxChunkX,
                minChunkZ,
                maxChunkZ
        ));
        snapshots.entrySet().removeIf(entry -> {
            if (inside(
                    chunkX(entry.getKey()),
                    chunkZ(entry.getKey()),
                    minChunkX,
                    maxChunkX,
                    minChunkZ,
                    maxChunkZ
            )) {
                return false;
            }
            entry.getValue().cancel(false);
            return true;
        });
        unavailable.removeIf(key -> !inside(
                chunkX(key),
                chunkZ(key),
                minChunkX,
                maxChunkX,
                minChunkZ,
                maxChunkZ
        ));
    }

    /**
     * Counts completed snapshots still retained for processing.
     *
     * @return number of completed retained snapshots
     */
    public int completedCount() {
        int completed = 0;
        for (CompletableFuture<ChunkSnapshot> snapshot : snapshots.values()) {
            if (snapshot.isDone() && !snapshot.isCompletedExceptionally()) {
                completed++;
            }
        }
        return completed;
    }

    /**
     * Returns the session version used to trigger progressive refreshes.
     *
     * @return current snapshot version
     */
    public long snapshotVersion() {
        return snapshotVersion.get();
    }

    /**
     * Returns the persistent cache associated with this store.
     *
     * @return render cache
     */
    public PersistentChunkRenderCache renderCache() {
        return renderCache;
    }

    /**
     * Invalidates one chunk and schedules it for revalidation.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void invalidate(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        validated.remove(key);
        unavailable.remove(key);
        snapshots.remove(key);
        queuedRequests.remove(key);
        snapshotVersion.incrementAndGet();
    }

    /** Invalidates the chunk only when the event belongs to this world. */
    void invalidateIfWorld(World changedWorld, int chunkX, int chunkZ) {
        if (world.getUID().equals(changedWorld.getUID())) {
            invalidate(chunkX, chunkZ);
        }
    }

    /**
     * Cancels pending work and unregisters this session.
     */
    @Override
    public void close() {
        closed = true;
        retainedViewport = null;
        requestQueue.clear();
        queuedRequests.clear();
        validated.clear();
        unavailable.clear();
        snapshots.clear();
        renderCache.unregister(this);
    }

    /**
     * Releases a processed snapshot and marks its chunk validated.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param future processed snapshot future
     * @return true when this future was still current
     */
    public boolean markValidated(
            int chunkX,
            int chunkZ,
            CompletableFuture<ChunkSnapshot> future
    ) {
        long key = chunkKey(chunkX, chunkZ);
        if (!snapshots.remove(key, future)) {
            return false;
        }
        validated.add(key);
        trimValidated();
        return true;
    }

    /**
     * Returns compact runtime counters for diagnosing render stalls.
     *
     * @return current loader statistics
     */
    public String debugSummary() {
        long loads = snapshotLoadCount.get();
        long averageMillis = loads == 0L
                ? 0L
                : snapshotLoadNanos.get() / loads / 1_000_000L;
        long longestMillis = longestSnapshotLoadNanos.get() / 1_000_000L;
        return "pending=" + snapshots.size()
                + ", queued=" + requestQueue.size()
                + ", validated=" + validated.size()
                + ", unavailable=" + unavailable.size()
                + ", inFlight=" + inFlightRequests.get()
                + ", loads=" + loads
                + ", snapshotAvgMs=" + averageMillis
                + ", snapshotMaxMs=" + longestMillis;
    }

    /** Schedules a bounded main-thread request drain. */
    private void scheduleDrain() {
        if (drainScheduled.compareAndSet(false, true)) {
            Bukkit.getScheduler().runTask(plugin, this::drainOnMain);
        }
    }

    /** Starts at most the configured number of chunk loads for this tick. */
    private void drainOnMain() {
        if (closed) {
            drainScheduled.set(false);
            requestQueue.clear();
            queuedRequests.clear();
            return;
        }

        int scheduled = 0;
        Request request;
        while (scheduled < REQUESTS_PER_TICK
                && inFlightRequests.get() < MAX_IN_FLIGHT_REQUESTS
                && (request = requestQueue.poll()) != null) {
            queuedRequests.remove(request.key(), request);
            CompletableFuture<ChunkSnapshot> future = snapshots.get(request.key());
            if (future == null || future.isDone()) {
                continue;
            }

            scheduled++;
            loadSnapshot(request, future);
        }

        if (!requestQueue.isEmpty()) {
            Bukkit.getScheduler().runTaskLater(plugin, this::drainOnMain, 1L);
            return;
        }

        drainScheduled.set(false);
        if (!requestQueue.isEmpty()) {
            scheduleDrain();
        }
    }

    /** Captures one chunk snapshot and reports completion to its future. */
    private void loadSnapshot(Request request, CompletableFuture<ChunkSnapshot> future) {
        if (!world.isChunkGenerated(request.chunkX(), request.chunkZ())) {
            if (snapshots.remove(request.key(), future)) {
                unavailable.add(request.key());
                future.complete(null);
                snapshotVersion.incrementAndGet();
            }
            return;
        }
        // Paper completes this future on the main thread; snapshot capture stays
        // on that safe boundary, while all pixel work remains on render workers.
        inFlightRequests.incrementAndGet();
        world.getChunkAtAsync(request.chunkX(), request.chunkZ(), false)
                .whenComplete((chunk, exception) -> {
                    try {
                        if (snapshots.get(request.key()) != future) {
                            return;
                        }
                        if (exception != null || chunk == null) {
                            snapshots.remove(request.key(), future);
                            future.completeExceptionally(exception == null
                                    ? new IllegalStateException("Chunk unavailable")
                                    : exception);
                            snapshotVersion.incrementAndGet();
                            retry(request);
                            return;
                        }
                        long snapshotStarted = System.nanoTime();
                        future.complete(chunk.getChunkSnapshot(true, false, false));
                        long snapshotElapsed = System.nanoTime() - snapshotStarted;
                        snapshotLoadCount.incrementAndGet();
                        snapshotLoadNanos.addAndGet(snapshotElapsed);
                        longestSnapshotLoadNanos.accumulateAndGet(
                                snapshotElapsed,
                                Math::max
                        );
                        snapshotVersion.incrementAndGet();
                    } catch (Throwable snapshotException) {
                        snapshots.remove(request.key(), future);
                        future.completeExceptionally(snapshotException);
                        snapshotVersion.incrementAndGet();
                        retry(request);
                    } finally {
                        inFlightRequests.decrementAndGet();
                    }
                });
    }

    /** Retries a failed chunk request after a short delay. */
    private void retry(Request request) {
        if (closed) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (!closed && !snapshots.containsKey(request.key())) {
                request(request.chunkX(), request.chunkZ(), request.priority());
            }
        }, RETRY_DELAY_TICKS);
    }

    /** Packs two chunk coordinates into one map key. */
    private long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    /** Extracts a signed X coordinate from a packed chunk key. */
    private int chunkX(long key) {
        return (int) (key >> 32);
    }

    /** Extracts a signed Z coordinate from a packed chunk key. */
    private int chunkZ(long key) {
        return (int) key;
    }

    /** Checks whether a chunk belongs to the active viewport bounds. */
    private boolean inside(
            int chunkX,
            int chunkZ,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
    ) {
        return chunkX >= minChunkX
                && chunkX <= maxChunkX
                && chunkZ >= minChunkZ
                && chunkZ <= maxChunkZ;
    }

    /** Returns whether a chunk belongs to this store's currently visible viewport. */
    boolean retains(UUID worldId, long key) {
        Viewport viewport = retainedViewport;
        return viewport != null
                && world.getUID().equals(worldId)
                && inside(
                        chunkX(key),
                        chunkZ(key),
                        viewport.minChunkX(),
                        viewport.maxChunkX(),
                        viewport.minChunkZ(),
                        viewport.maxChunkZ()
                );
    }

    /** Keeps per-session validation memory bounded during long map sessions. */
    private void trimValidated() {
        while (validated.size() > MAX_VALIDATED_CHUNKS) {
            var iterator = validated.iterator();
            if (!iterator.hasNext()) {
                return;
            }
            validated.remove(iterator.next());
        }
    }

    private record Request(
            int chunkX,
            int chunkZ,
            long key,
            long priority,
            long sequence
    ) {
    }

    private record Viewport(
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
    ) {
    }
}
