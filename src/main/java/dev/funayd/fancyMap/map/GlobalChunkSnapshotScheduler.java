package dev.funayd.fancyMap.map;

import dev.funayd.fancyMap.config.ChunkSchedulerSettings;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Globally bounded, fair loader for all map chunk snapshots.
 *
 * <p>Only this scheduler may start a Paper chunk load. Sessions contribute a
 * small viewport cursor; they never allocate a request or snapshot per visible
 * chunk. Work is selected round-robin and each cursor walks outward from the
 * viewport center.</p>
 */
public final class GlobalChunkSnapshotScheduler implements AutoCloseable {
    private final PersistentChunkRenderCache renderCache;
    private final JavaPlugin plugin;
    private final BooleanSupplier loadUngeneratedChunks;
    private final int requestsPerTick;
    private final int maxInFlightRequests;
    private final int maxCandidateScansPerTick;
    private final int maxRetries;
    private final CopyOnWriteArrayList<AsyncChunkSnapshotStore> stores =
            new CopyOnWriteArrayList<>();
    private final ConcurrentMap<ChunkKey, Job> jobs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Job> retryQueue = new ConcurrentLinkedQueue<>();
    private final ThreadPoolExecutor snapshotWorkers;
    private final BukkitTask tickTask;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger nextStoreIndex = new AtomicInteger();
    private final AtomicLong startedLoads = new AtomicLong();
    private final AtomicLong completedLoads = new AtomicLong();
    private final AtomicLong failedLoads = new AtomicLong();
    private volatile boolean closed;

    /**
     * Starts one shared scheduler for all map sessions.
     *
     * @param plugin owning plugin
     * @param renderCache shared rendered-chunk cache
     * @param settings global scheduler configuration
     * @param loadUngeneratedChunks current generation policy
     */
    public GlobalChunkSnapshotScheduler(
            JavaPlugin plugin,
            PersistentChunkRenderCache renderCache,
            ChunkSchedulerSettings settings,
            BooleanSupplier loadUngeneratedChunks
    ) {
        this.plugin = plugin;
        this.renderCache = renderCache;
        this.loadUngeneratedChunks = loadUngeneratedChunks;
        requestsPerTick = settings.requestsPerTick();
        maxInFlightRequests = settings.maxInFlightRequests();
        maxCandidateScansPerTick = settings.maxCandidateScansPerTick();
        maxRetries = settings.maxRetries();
        snapshotWorkers = new ThreadPoolExecutor(
                settings.snapshotWorkers(),
                settings.snapshotWorkers(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maxInFlightRequests),
                runnable -> {
                    Thread thread = new Thread(runnable, "FancyMap-ChunkSnapshot");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
        tickTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::drainOnMain,
                1L,
                1L
        );
    }

    /** Registers one active map-session viewport. */
    void register(AsyncChunkSnapshotStore store) {
        stores.addIfAbsent(store);
    }

    /** Unregisters a closed map-session viewport. */
    void unregister(AsyncChunkSnapshotStore store) {
        stores.remove(store);
    }

    /** Wakes the next scheduled tick after a viewport or invalidation change. */
    void wake() {
        // The repeating task already runs every tick; this documents the synchronization point.
    }

    /** Returns compact global counters for debug output. */
    public String debugSummary() {
        return "schedulerSessions=" + stores.size()
                + ", schedulerJobs=" + jobs.size()
                + ", schedulerInFlight=" + inFlight.get()
                + ", schedulerRetries=" + retryQueue.size()
                + ", schedulerLoads=" + completedLoads.get() + "/" + startedLoads.get()
                + ", schedulerFailures=" + failedLoads.get();
    }

    /** Cancels future scheduling and releases worker resources. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        tickTask.cancel();
        jobs.clear();
        retryQueue.clear();
        stores.clear();
        snapshotWorkers.shutdownNow();
    }

    /** Starts a small globally bounded number of candidates on the main thread. */
    private void drainOnMain() {
        if (closed) {
            return;
        }
        int startedThisTick = 0;
        while (startedThisTick < requestsPerTick
                && inFlight.get() < maxInFlightRequests) {
            Job job = nextJob();
            if (job == null) {
                return;
            }
            start(job);
            startedThisTick++;
        }
    }

    /** Selects a retry first, then a fair center-first viewport candidate. */
    private Job nextJob() {
        Job retry = retryQueue.poll();
        if (retry != null && !retry.store().isClosed()
                && !renderCache.hasValidatedEntry(
                retry.store().world(), retry.chunkX(), retry.chunkZ()
        )) {
            return jobs.putIfAbsent(retry.key(), retry) == null ? retry : null;
        }

        int storeCount = stores.size();
        for (int scans = 0; scans < maxCandidateScansPerTick && storeCount > 0; scans++) {
            int index = Math.floorMod(nextStoreIndex.getAndIncrement(), storeCount);
            AsyncChunkSnapshotStore store = stores.get(index);
            AsyncChunkSnapshotStore.ChunkCoordinate candidate = store.nextCandidate();
            if (candidate == null || renderCache.hasValidatedEntry(
                    store.world(), candidate.x(), candidate.z()
            )) {
                continue;
            }
            ChunkKey key = new ChunkKey(store.world().getUID(), candidate.x(), candidate.z());
            Job created = new Job(store, candidate.x(), candidate.z(), key, 0);
            Job existing = jobs.putIfAbsent(key, created);
            if (existing == null) {
                return created;
            }
        }
        return null;
    }

    /** Starts one Paper chunk request while retaining the global in-flight permit. */
    private void start(Job job) {
        World world = job.store().world();
        if (!loadUngeneratedChunks.getAsBoolean()
                && !world.isChunkGenerated(job.chunkX(), job.chunkZ())) {
            discard(job);
            return;
        }
        inFlight.incrementAndGet();
        startedLoads.incrementAndGet();
        world.getChunkAtAsync(
                job.chunkX(),
                job.chunkZ(),
                loadUngeneratedChunks.getAsBoolean()
        )
                .whenComplete((chunk, error) -> Bukkit.getScheduler().runTask(
                        plugin,
                        () -> snapshotOnMain(job, chunk, error)
                ));
    }

    /** Captures the immutable snapshot at Paper's completion boundary. */
    private void snapshotOnMain(Job job, Chunk chunk, Throwable error) {
        if (closed || error != null || chunk == null) {
            failed(job);
            return;
        }
        try {
            ChunkSnapshot snapshot = chunk.getChunkSnapshot(true, false, false);
            snapshotWorkers.execute(() -> cacheSnapshot(job, snapshot));
        } catch (RuntimeException exception) {
            failed(job);
        }
    }

    /** Converts a snapshot off-thread, then notifies visible sessions. */
    private void cacheSnapshot(Job job, ChunkSnapshot snapshot) {
        try {
            renderCache.update(
                    job.store().world(),
                    job.chunkX(),
                    job.chunkZ(),
                    snapshot,
                    job.store().world().getMinHeight()
            );
            completedLoads.incrementAndGet();
            for (AsyncChunkSnapshotStore store : stores) {
                store.cached(job.store().world(), job.chunkX(), job.chunkZ());
            }
            finish(job);
        } catch (RuntimeException exception) {
            failed(job);
        }
    }

    /** Releases a completed job and its global in-flight permit. */
    private void finish(Job job) {
        jobs.remove(job.key(), job);
        inFlight.decrementAndGet();
    }

    /** Discards a job that never acquired an in-flight permit. */
    private void discard(Job job) {
        jobs.remove(job.key(), job);
    }

    /** Requeues a transient failure a limited number of times. */
    private void failed(Job job) {
        failedLoads.incrementAndGet();
        boolean retry = job.attempt() < maxRetries && !closed && !job.store().isClosed();
        finish(job);
        if (retry) {
            retryQueue.offer(job.retry());
        }
    }

    /** Deduplication key for one world chunk. */
    private record ChunkKey(UUID worldId, int chunkX, int chunkZ) {
    }

    /** One globally scheduled chunk request. */
    private record Job(
            AsyncChunkSnapshotStore store,
            int chunkX,
            int chunkZ,
            ChunkKey key,
            int attempt
    ) {
        private Job retry() {
            return new Job(store, chunkX, chunkZ, key, attempt + 1);
        }
    }
}
