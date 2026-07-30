package dev.funayd.fancyMap.map;

import org.bukkit.ChunkSnapshot;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Persistent per-world cache of compact 16×16 rendered chunk colors.
 */
public final class PersistentChunkRenderCache implements AutoCloseable {
    private static final int MAGIC = 0x464D4348;
    private static final int VERSION = 1;
    private static final int CHUNK_PIXELS = 16 * 16;
    private static final long CACHE_FLUSH_DELAY_MILLIS = 2_000L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int MAX_IN_MEMORY_ENTRIES = 32_768;

    private final JavaPlugin plugin;
    private final Path directory;
    private final ScheduledExecutorService ioExecutor =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "FancyMap-MapCacheIO");
                thread.setDaemon(true);
                return thread;
            });
    private final ConcurrentMap<UUID, WorldCache> worlds = new ConcurrentHashMap<>();
    private final CopyOnWriteArraySet<AsyncChunkSnapshotStore> snapshotStores =
            new CopyOnWriteArraySet<>();

    /**
     * Creates a cache rooted in the plugin data directory.
     *
     * @param plugin owning plugin
     */
    public PersistentChunkRenderCache(JavaPlugin plugin) {
        this.plugin = plugin;
        directory = plugin.getDataFolder().toPath().resolve("map-cache");
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create map cache directory: "
                    + exception.getMessage());
        }
    }

    /**
     * Reads cached colors for one chunk.
     *
     * @param world target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return immutable-by-convention 256-color array, or {@code null}
     */
    public byte[] get(World world, int chunkX, int chunkZ) {
        Entry entry = worldCache(world).entries.get(key(chunkX, chunkZ));
        return entry == null ? null : entry.colors();
    }

    /**
     * Checks whether the current session must compare this chunk to the world.
     *
     * @param world target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return true when validation is still required
     */
    public boolean needsValidation(World world, int chunkX, int chunkZ) {
        return !worldCache(world).validated.contains(key(chunkX, chunkZ));
    }

    /** Checks whether a reusable, validated chunk color entry is available. */
    public boolean hasValidatedEntry(World world, int chunkX, int chunkZ) {
        WorldCache cache = worldCache(world);
        long key = key(chunkX, chunkZ);
        return cache.entries.containsKey(key) && cache.validated.contains(key);
    }

    /**
     * Rebuilds one compact chunk entry and schedules a persistent flush.
     *
     * @param world target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @param snapshot source snapshot
     * @param minHeight world minimum height
     */
    public void update(
            World world,
            int chunkX,
            int chunkZ,
            ChunkSnapshot snapshot,
            int minHeight
    ) {
        WorldCache cache = worldCache(world);
        Entry next = createEntry(snapshot, minHeight);
        long key = key(chunkX, chunkZ);
        Entry previous = cache.entries.put(key, next);
        cache.validated.add(key);
        if (previous == null
                || previous.signature() != next.signature()
                || !Arrays.equals(previous.colors(), next.colors())) {
            cache.dirty.set(true);
            scheduleFlush(cache);
        }
        cache.trimIfNeeded(key);
    }

    /**
     * Marks one chunk stale and asks active snapshot stores to reload it.
     *
     * @param world changed world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     */
    public void invalidate(World world, int chunkX, int chunkZ) {
        WorldCache cache = worlds.get(world.getUID());
        if (cache != null) {
            cache.validated.remove(key(chunkX, chunkZ));
        }
        for (AsyncChunkSnapshotStore store : snapshotStores) {
            store.invalidateIfWorld(world, chunkX, chunkZ);
        }
    }

    /**
     * Returns cache memory counters for the debug command.
     *
     * @param world target world
     * @return current cache statistics
     */
    public String debugSummary(World world) {
        WorldCache cache = worldCache(world);
        return "cache=" + cache.entries.size() + "/" + MAX_IN_MEMORY_ENTRIES
                + ", cacheEvictions=" + cache.evictions;
    }

    /**
     * Flushes and releases cache memory for an unloaded world.
     *
     * @param world world being unloaded
     */
    public void unload(World world) {
        WorldCache cache = worlds.remove(world.getUID());
        if (cache != null) {
            ioExecutor.execute(() -> flush(cache));
        }
    }

    void register(AsyncChunkSnapshotStore store) {
        snapshotStores.add(store);
    }

    void unregister(AsyncChunkSnapshotStore store) {
        snapshotStores.remove(store);
    }

    /** Checks whether any open map currently displays this cached chunk. */
    private boolean retainedByActiveViewport(UUID worldId, long key) {
        for (AsyncChunkSnapshotStore store : snapshotStores) {
            if (store.retains(worldId, key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Flushes dirty cache files and stops the cache I/O executor.
     */
    @Override
    public void close() {
        for (WorldCache cache : worlds.values()) {
            flush(cache);
        }
        ioExecutor.shutdownNow();
    }

    /** Returns or lazily loads the cache for a world. */
    private WorldCache worldCache(World world) {
        return worlds.computeIfAbsent(
                world.getUID(),
            uuid -> new WorldCache(uuid, directory.resolve(uuid + ".bin"))
        );
    }

    /** Schedules a delayed coalesced disk flush. */
    private void scheduleFlush(WorldCache cache) {
        if (cache.flushScheduled.compareAndSet(false, true)) {
            ioExecutor.schedule(() -> {
                flush(cache);
                cache.flushScheduled.set(false);
                if (cache.dirty.get()) {
                    scheduleFlush(cache);
                }
            }, CACHE_FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Writes one world cache atomically to disk. */
    private void flush(WorldCache cache) {
        if (!cache.dirty.compareAndSet(true, false)) {
            return;
        }

        Path temporary = cache.file().resolveSibling(cache.file().getFileName() + ".tmp");
        try {
            Files.createDirectories(cache.file().getParent());
            try (DataOutputStream output = new DataOutputStream(
                    Files.newOutputStream(temporary))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(cache.entries.size());
                for (Map.Entry<Long, Entry> item : cache.entries.entrySet()) {
                    output.writeLong(item.getKey());
                    Entry entry = item.getValue();
                    output.writeLong(entry.signature());
                    output.write(entry.colors());
                }
            }
            try {
                Files.move(temporary, cache.file(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, cache.file(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            cache.dirty.set(true);
            plugin.getLogger().warning("Could not save map cache: "
                    + exception.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next successful flush replaces this temporary file.
            }
        }
    }

    /** Converts a snapshot into compact top-surface colors and a signature. */
    private static Entry createEntry(ChunkSnapshot snapshot, int minHeight) {
        byte[] colors = new byte[CHUNK_PIXELS];
        long signature = FNV_OFFSET;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int highestY = snapshot.getHighestBlockYAt(x, z);
                Material material = highestY < minHeight
                        ? Material.AIR
                        : snapshot.getBlockType(x, highestY, z);
                colors[z * 16 + x] = WorldMapRenderer.mapColor(material);
                signature = mix(signature, highestY);
                signature = mix(signature, material.ordinal());
            }
        }
        return new Entry(signature, colors);
    }

    /** Adds one value to the chunk fingerprint. */
    private static long mix(long value, int data) {
        value ^= data;
        return value * FNV_PRIME;
    }

    /** Packs two chunk coordinates into one cache key. */
    private static long key(int x, int z) {
        return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private final class WorldCache {
        private final UUID worldId;
        private final Path file;
        private final ConcurrentMap<Long, Entry> entries = new ConcurrentHashMap<>();
        private final Set<Long> validated = ConcurrentHashMap.newKeySet();
        private final AtomicBoolean loaded = new AtomicBoolean();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();
        private final AtomicLong evictions = new AtomicLong();

        /** Creates and loads one world cache file. */
        private WorldCache(UUID worldId, Path file) {
            this.worldId = worldId;
            this.file = file;
            load();
        }

        /** Returns the backing cache file. */
        private Path file() {
            return file;
        }

        /** Loads valid entries from disk, ignoring an absent or corrupt file. */
        private void load() {
            if (!loaded.compareAndSet(false, true) || !Files.isRegularFile(file)) {
                return;
            }
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    return;
                }
                int count = input.readInt();
                if (count < 0 || count > 1_000_000) {
                    return;
                }
                for (int index = 0; index < count; index++) {
                    long key = input.readLong();
                    long signature = input.readLong();
                    byte[] colors = input.readNBytes(CHUNK_PIXELS);
                    if (colors.length != CHUNK_PIXELS) {
                        throw new EOFException("Incomplete cache entry");
                    }
                    entries.put(key, new Entry(signature, colors));
                    trimIfNeeded(null);
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not load map cache " + file.getFileName()
                        + ": " + exception.getMessage());
                entries.clear();
            }
        }

        /** Evicts arbitrary old entries to keep the live cache bounded. */
        private void trimIfNeeded(Long protectedKey) {
            while (entries.size() > MAX_IN_MEMORY_ENTRIES) {
                var iterator = entries.keySet().iterator();
                Long candidate = null;
                while (iterator.hasNext()) {
                    Long key = iterator.next();
                    if (!key.equals(protectedKey)
                            && !retainedByActiveViewport(worldId, key)) {
                        candidate = key;
                        break;
                    }
                }
                if (candidate == null) {
                    var fallback = entries.keySet().iterator();
                    if (!fallback.hasNext()) {
                        return;
                    }
                    candidate = fallback.next();
                }
                if (entries.remove(candidate) != null) {
                    validated.remove(candidate);
                    evictions.incrementAndGet();
                }
            }
        }
    }

    private record Entry(long signature, byte[] colors) {
    }
}
