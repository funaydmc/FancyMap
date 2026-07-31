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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.IntSupplier;

/**
 * Persistent per-world cache of compact 16×16 rendered chunk colors.
 *
 * <p>Disk storage is split into 32×32-chunk regions. Only recently used regions
 * remain resident, so evicting memory never discards a rendered chunk from disk.</p>
 */
public final class PersistentChunkRenderCache implements AutoCloseable {
    private static final int LEGACY_MAGIC = 0x464D4348;
    private static final int MAGIC = 0x464D4352;
    private static final int LOD_MAGIC = 0x464D4C44;
    private static final int VERSION = 1;
    private static final int CHUNK_PIXELS = 16 * 16;
    private static final int REGION_CHUNKS = 32;
    private static final int REGION_ENTRY_COUNT = REGION_CHUNKS * REGION_CHUNKS;
    private static final int MISSING_COLOR = -1;
    private static final long CACHE_FLUSH_DELAY_MILLIS = 2_000L;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;

    private final JavaPlugin plugin;
    private final Path directory;
    private final IntSupplier maxEntries;
    private final IntSupplier maxLodLevel;
    private final AtomicLong accessCounter = new AtomicLong();
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
     * @param maxEntries approximate number of rendered chunks retained in memory per world
     */
    public PersistentChunkRenderCache(
            JavaPlugin plugin,
            IntSupplier maxEntries,
            IntSupplier maxLodLevel
    ) {
        this.plugin = plugin;
        this.maxEntries = maxEntries;
        this.maxLodLevel = maxLodLevel;
        directory = plugin.getDataFolder().toPath().resolve("map-cache");
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create map cache directory: "
                    + exception.getMessage());
        }
    }

    /**
     * Reads cached colors for one chunk. Region file I/O runs only on the async map renderer.
     *
     * @param world target world
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return immutable-by-convention 256-color array, or {@code null}
     */
    public byte[] get(World world, int chunkX, int chunkZ) {
        Region region = worldCache(world).loadRegion(regionKey(chunkX, chunkZ));
        Entry entry = region.entry(localIndex(chunkX, chunkZ));
        return entry == null ? null : entry.colors();
    }

    /**
     * Reads one pre-aggregated overview color. Level zero is represented by the
     * detailed chunk cache and is therefore not accepted here.
     *
     * @return an unsigned map color, or {@value #MISSING_COLOR} when not yet sampled
     */
    public int getLodColor(World world, int level, int cellX, int cellZ) {
        if (level < 1 || level > maxLodLevel()) {
            return MISSING_COLOR;
        }
        LodRegion region = worldCache(world).loadLodRegion(
                new LodRegionKey(level, regionKey(cellX, cellZ))
        );
        return region.color(localIndex(cellX, cellZ));
    }

    /** Returns the overview level whose cells are at least one rendered pixel wide. */
    public int lodLevel(double blocksPerPixel) {
        return selectLodLevel(blocksPerPixel, maxLodLevel());
    }

    /** Selects the first overview level whose cells cover one map pixel. */
    static int selectLodLevel(double blocksPerPixel, int maxLevel) {
        if (!Double.isFinite(blocksPerPixel) || blocksPerPixel <= 0.0D) {
            throw new IllegalArgumentException("blocksPerPixel must be positive.");
        }
        int level = 0;
        double cellBlocks = 16.0D;
        while (level < maxLevel && cellBlocks < blocksPerPixel) {
            level++;
            cellBlocks *= 2.0D;
        }
        return level;
    }

    /** Returns how many chunks one cell represents at the requested overview level. */
    public int lodSpanChunks(int level) {
        return lodSpanChunks(level, maxLodLevel());
    }

    /** Returns a checked power-of-two chunk span for one overview level. */
    static int lodSpanChunks(int level, int maxLevel) {
        if (level < 0 || level > maxLevel) {
            throw new IllegalArgumentException("Unsupported LOD level: " + level);
        }
        return 1 << level;
    }

    /** Marks the resident regions of one completed render as recently used. */
    void touch(
            World world,
            int minChunkX,
            int maxChunkX,
            int minChunkZ,
            int maxChunkZ
    ) {
        WorldCache cache = worlds.get(world.getUID());
        if (cache != null) {
            cache.touchRegions(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
        }
    }

    /** Marks the overview regions used by one completed LOD render as recently used. */
    void touchLod(
            World world,
            int level,
            int minCellX,
            int maxCellX,
            int minCellZ,
            int maxCellZ
    ) {
        WorldCache cache = worlds.get(world.getUID());
        if (cache != null) {
            cache.touchLodRegions(level, minCellX, maxCellX, minCellZ, maxCellZ);
        }
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
        Region region = worldCache(world).residentRegion(regionKey(chunkX, chunkZ));
        return region == null || !region.isValidated(localIndex(chunkX, chunkZ));
    }

    /** Checks whether a reusable, validated chunk color entry is available. */
    public boolean hasValidatedEntry(World world, int chunkX, int chunkZ) {
        Region region = worldCache(world).residentRegion(regionKey(chunkX, chunkZ));
        int index = localIndex(chunkX, chunkZ);
        return region != null && region.entry(index) != null && region.isValidated(index);
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
        RegionKey regionKey = regionKey(chunkX, chunkZ);
        Region region = cache.loadRegion(regionKey);
        int index = localIndex(chunkX, chunkZ);
        Entry next = createEntry(snapshot, minHeight);
        Entry previous = region.put(index, next);
        region.validate(index);
        if (previous == null
                || previous.signature() != next.signature()
                || !Arrays.equals(previous.colors(), next.colors())) {
            region.dirty.set(true);
            scheduleFlush(cache, regionKey, region);
            cache.updateLod(chunkX, chunkZ, next.overviewColor());
        }
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
            Region region = cache.residentRegion(regionKey(chunkX, chunkZ));
            if (region != null) {
                region.invalidate(localIndex(chunkX, chunkZ));
            }
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
        return "cache=" + cache.entryCount() + "/" + maxEntries.getAsInt()
                + ", regions=" + cache.regions.size() + "/" + maxResidentRegions()
                + ", lodCells=" + cache.lodEntryCount()
                + ", lodRegions=" + cache.lodRegions.size() + "/" + maxLodResidentRegions()
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
            ioExecutor.execute(cache::flushAll);
        }
    }

    void register(AsyncChunkSnapshotStore store) {
        snapshotStores.add(store);
    }

    void unregister(AsyncChunkSnapshotStore store) {
        snapshotStores.remove(store);
    }

    /** Flushes dirty cache files and stops the cache I/O executor. */
    @Override
    public void close() {
        for (WorldCache cache : worlds.values()) {
            cache.flushAll();
        }
        ioExecutor.shutdownNow();
    }

    /** Returns or lazily creates the region cache for a world. */
    private WorldCache worldCache(World world) {
        return worlds.computeIfAbsent(
                world.getUID(),
                uuid -> new WorldCache(directory.resolve(uuid.toString()))
        );
    }

    /** Schedules a delayed coalesced region flush. */
    private void scheduleFlush(WorldCache cache, RegionKey key, Region region) {
        if (region.flushScheduled.compareAndSet(false, true)) {
            ioExecutor.schedule(() -> {
                flush(cache, key, region);
                cache.trimIfNeeded(null);
                region.flushScheduled.set(false);
                if (region.dirty.get()) {
                    scheduleFlush(cache, key, region);
                }
            }, CACHE_FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Expedites one dirty region when memory pressure leaves no clean eviction candidate. */
    private void flushSoon(WorldCache cache, RegionKey key, Region region) {
        if (!region.flushExpedited.compareAndSet(false, true)) {
            return;
        }
        ioExecutor.execute(() -> {
            try {
                flush(cache, key, region);
                cache.trimIfNeeded(null);
            } finally {
                region.flushExpedited.set(false);
            }
        });
    }

    /** Schedules a delayed coalesced flush for a compact one-color LOD region. */
    private void scheduleLodFlush(WorldCache cache, LodRegionKey key, LodRegion region) {
        if (region.flushScheduled.compareAndSet(false, true)) {
            ioExecutor.schedule(() -> {
                flushLod(cache, key, region);
                cache.trimLodIfNeeded(null);
                region.flushScheduled.set(false);
                if (region.dirty.get()) {
                    scheduleLodFlush(cache, key, region);
                }
            }, CACHE_FLUSH_DELAY_MILLIS, TimeUnit.MILLISECONDS);
        }
    }

    /** Writes one dirty region atomically to disk. */
    private void flush(WorldCache cache, RegionKey key, Region region) {
        if (!region.dirty.compareAndSet(true, false)) {
            return;
        }
        Path file = cache.file(key);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        RegionSnapshot snapshot = region.snapshot();
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(snapshot.count());
                for (int index = 0; index < REGION_ENTRY_COUNT; index++) {
                    Entry entry = snapshot.entries()[index];
                    if (entry == null) {
                        continue;
                    }
                    output.writeShort(index);
                    output.writeLong(entry.signature());
                    output.write(entry.colors());
                }
            }
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            region.dirty.set(true);
            plugin.getLogger().warning("Could not save map cache region: "
                    + exception.getMessage());
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // The next successful flush replaces this temporary file.
            }
        }
    }

    /** Persists one sparse LOD region atomically. */
    private void flushLod(WorldCache cache, LodRegionKey key, LodRegion region) {
        if (!region.dirty.compareAndSet(true, false)) {
            return;
        }
        Path file = cache.lodFile(key);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        LodRegionSnapshot snapshot = region.snapshot();
        try {
            Files.createDirectories(file.getParent());
            try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(temporary))) {
                output.writeInt(LOD_MAGIC);
                output.writeInt(VERSION);
                output.writeInt(snapshot.count());
                for (int index = 0; index < REGION_ENTRY_COUNT; index++) {
                    int color = snapshot.colors()[index];
                    if (color == MISSING_COLOR) {
                        continue;
                    }
                    output.writeShort(index);
                    output.writeByte(color);
                }
            }
            try {
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicMoveUnsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            region.dirty.set(true);
            plugin.getLogger().warning("Could not save map LOD region: "
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
        int[] colorCounts = new int[256];
        long signature = FNV_OFFSET;
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                int highestY = snapshot.getHighestBlockYAt(x, z);
                Material material = highestY < minHeight
                        ? Material.AIR
                        : snapshot.getBlockType(x, highestY, z);
                byte color = WorldMapRenderer.mapColor(material);
                colors[z * 16 + x] = color;
                colorCounts[Byte.toUnsignedInt(color)]++;
                signature = mix(signature, highestY);
                signature = mix(signature, material.ordinal());
            }
        }
        return new Entry(signature, colors, dominantColor(colorCounts));
    }

    /** Returns the most frequent color from compact palette counts. */
    private static byte dominantColor(int[] counts) {
        int color = 0;
        for (int candidate = 1; candidate < counts.length; candidate++) {
            if (counts[candidate] > counts[color]) {
                color = candidate;
            }
        }
        return (byte) color;
    }

    /** Recreates an entry's overview color when reading the compatible base file format. */
    private static Entry entry(long signature, byte[] colors) {
        int[] counts = new int[256];
        for (byte color : colors) {
            counts[Byte.toUnsignedInt(color)]++;
        }
        return new Entry(signature, colors, dominantColor(counts));
    }

    /** Adds one value to the chunk fingerprint. */
    private static long mix(long value, int data) {
        value ^= data;
        return value * FNV_PRIME;
    }

    /** Returns the region containing one chunk. */
    private static RegionKey regionKey(int chunkX, int chunkZ) {
        return new RegionKey(
                Math.floorDiv(chunkX, REGION_CHUNKS),
                Math.floorDiv(chunkZ, REGION_CHUNKS)
        );
    }

    /** Returns the local entry offset inside a region. */
    private static int localIndex(int chunkX, int chunkZ) {
        return Math.floorMod(chunkZ, REGION_CHUNKS) * REGION_CHUNKS
                + Math.floorMod(chunkX, REGION_CHUNKS);
    }

    /** Returns the configured resident-region limit. */
    private int maxResidentRegions() {
        return Math.max(1, (maxEntries.getAsInt() + REGION_ENTRY_COUNT - 1) / REGION_ENTRY_COUNT);
    }

    /** Keeps small one-color overview regions bounded by the base-cache budget. */
    private int maxLodResidentRegions() {
        return maxResidentRegions() * 4;
    }

    /** Returns the configured number of stored overview layers. */
    private int maxLodLevel() {
        return Math.min(22, maxLodLevel.getAsInt());
    }

    private final class WorldCache {
        private final Path directory;
        private final ConcurrentMap<RegionKey, Region> regions = new ConcurrentHashMap<>();
        private final ConcurrentMap<LodRegionKey, LodRegion> lodRegions = new ConcurrentHashMap<>();
        private final AtomicLong evictions = new AtomicLong();
        private final AtomicBoolean legacyMigrationChecked = new AtomicBoolean();

        private WorldCache(Path directory) {
            this.directory = directory;
        }

        private Region loadRegion(RegionKey key) {
            migrateLegacyIfNeeded();
            Region region = regions.get(key);
            if (region != null) {
                return region;
            }
            region = regions.computeIfAbsent(key, this::readRegion);
            region.lastAccess = accessCounter.incrementAndGet();
            trimIfNeeded(key);
            return region;
        }

        /** Migrates the former single-file cache from the async map-render path once. */
        private synchronized void migrateLegacyIfNeeded() {
            if (!legacyMigrationChecked.compareAndSet(false, true)) {
                return;
            }
            if (Files.isDirectory(directory)) {
                return;
            }
            Path legacyFile = directory.resolveSibling(directory.getFileName() + ".bin");
            if (!Files.isRegularFile(legacyFile)) {
                return;
            }
            try (DataInputStream input = new DataInputStream(Files.newInputStream(legacyFile))) {
                if (input.readInt() != LEGACY_MAGIC || input.readInt() != VERSION) {
                    return;
                }
                int count = input.readInt();
                if (count < 0 || count > 1_000_000) {
                    return;
                }
                for (int entry = 0; entry < count; entry++) {
                    long packed = input.readLong();
                    long signature = input.readLong();
                    byte[] colors = input.readNBytes(CHUNK_PIXELS);
                    if (colors.length != CHUNK_PIXELS) {
                        throw new EOFException("Incomplete legacy cache entry");
                    }
                    int chunkX = (int) (packed >> 32);
                    int chunkZ = (int) packed;
                    RegionKey regionKey = regionKey(chunkX, chunkZ);
                    Region region = regions.computeIfAbsent(regionKey, this::readRegion);
                    region.put(localIndex(chunkX, chunkZ), entry(signature, colors));
                    region.dirty.set(true);
                }
                for (Map.Entry<RegionKey, Region> entry : regions.entrySet()) {
                    if (entry.getValue().dirty.get()) {
                        scheduleFlush(this, entry.getKey(), entry.getValue());
                    }
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not migrate map cache "
                        + legacyFile.getFileName() + ": " + exception.getMessage());
            }
        }

        private Region residentRegion(RegionKey key) {
            return regions.get(key);
        }

        private LodRegion loadLodRegion(LodRegionKey key) {
            LodRegion region = lodRegions.get(key);
            if (region != null) {
                return region;
            }
            region = lodRegions.computeIfAbsent(key, this::readLodRegion);
            region.lastAccess = accessCounter.incrementAndGet();
            trimLodIfNeeded(key);
            return region;
        }

        /** Updates the one parent cell per level without scanning unrelated chunks. */
        private void updateLod(int chunkX, int chunkZ, byte baseColor) {
            int childX = chunkX;
            int childZ = chunkZ;
            int childColor = Byte.toUnsignedInt(baseColor);
            for (int level = 1; level <= maxLodLevel(); level++) {
                int parentX = Math.floorDiv(childX, 2);
                int parentZ = Math.floorDiv(childZ, 2);
                int color = parentColor(level, parentX, parentZ, childColor, childX, childZ);
                LodRegionKey key = new LodRegionKey(level, regionKey(parentX, parentZ));
                LodRegion region = loadLodRegion(key);
                int previous = region.put(localIndex(parentX, parentZ), color);
                if (previous == color) {
                    return;
                }
                region.dirty.set(true);
                scheduleLodFlush(this, key, region);
                childX = parentX;
                childZ = parentZ;
                childColor = color;
            }
        }

        /** Combines the up to four known direct children of one overview cell. */
        private int parentColor(
                int level,
                int parentX,
                int parentZ,
                int changedColor,
                int changedChildX,
                int changedChildZ
        ) {
            int childX = parentX * 2;
            int childZ = parentZ * 2;
            int first = childColor(level, childX, childZ,
                    changedColor, changedChildX, changedChildZ);
            int second = childColor(level, childX + 1, childZ,
                    changedColor, changedChildX, changedChildZ);
            int third = childColor(level, childX, childZ + 1,
                    changedColor, changedChildX, changedChildZ);
            int fourth = childColor(level, childX + 1, childZ + 1,
                    changedColor, changedChildX, changedChildZ);
            return dominantColor(first, second, third, fourth);
        }

        /** Reads one direct child, substituting the value changed in this update. */
        private int childColor(
                int level,
                int childX,
                int childZ,
                int changedColor,
                int changedChildX,
                int changedChildZ
        ) {
            if (childX == changedChildX && childZ == changedChildZ) {
                return changedColor;
            }
            if (level == 1) {
                Entry entry = loadRegion(regionKey(childX, childZ)).entry(localIndex(childX, childZ));
                return entry == null ? MISSING_COLOR : Byte.toUnsignedInt(entry.overviewColor());
            }
            return loadLodRegion(new LodRegionKey(level - 1, regionKey(childX, childZ)))
                    .color(localIndex(childX, childZ));
        }

        /** Chooses the most frequent known color among four direct children. */
        private int dominantColor(int first, int second, int third, int fourth) {
            int winner = first;
            int best = frequency(first, first, second, third, fourth);
            int secondCount = frequency(second, first, second, third, fourth);
            if (second != MISSING_COLOR && secondCount > best) {
                winner = second;
                best = secondCount;
            }
            int thirdCount = frequency(third, first, second, third, fourth);
            if (third != MISSING_COLOR && thirdCount > best) {
                winner = third;
                best = thirdCount;
            }
            int fourthCount = frequency(fourth, first, second, third, fourth);
            if (fourth != MISSING_COLOR && fourthCount > best) {
                winner = fourth;
            }
            return winner;
        }

        private int frequency(int candidate, int first, int second, int third, int fourth) {
            if (candidate == MISSING_COLOR) {
                return 0;
            }
            int count = candidate == first ? 1 : 0;
            count += candidate == second ? 1 : 0;
            count += candidate == third ? 1 : 0;
            return count + (candidate == fourth ? 1 : 0);
        }

        private void touchRegions(
                int minChunkX,
                int maxChunkX,
                int minChunkZ,
                int maxChunkZ
        ) {
            int minRegionX = Math.floorDiv(minChunkX, REGION_CHUNKS);
            int maxRegionX = Math.floorDiv(maxChunkX, REGION_CHUNKS);
            int minRegionZ = Math.floorDiv(minChunkZ, REGION_CHUNKS);
            int maxRegionZ = Math.floorDiv(maxChunkZ, REGION_CHUNKS);
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    Region region = regions.get(new RegionKey(regionX, regionZ));
                    if (region != null) {
                        region.lastAccess = accessCounter.incrementAndGet();
                    }
                }
            }
        }

        private void touchLodRegions(
                int level,
                int minCellX,
                int maxCellX,
                int minCellZ,
                int maxCellZ
        ) {
            int minRegionX = Math.floorDiv(minCellX, REGION_CHUNKS);
            int maxRegionX = Math.floorDiv(maxCellX, REGION_CHUNKS);
            int minRegionZ = Math.floorDiv(minCellZ, REGION_CHUNKS);
            int maxRegionZ = Math.floorDiv(maxCellZ, REGION_CHUNKS);
            for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                    LodRegion region = lodRegions.get(
                            new LodRegionKey(level, new RegionKey(regionX, regionZ))
                    );
                    if (region != null) {
                        region.lastAccess = accessCounter.incrementAndGet();
                    }
                }
            }
        }

        private Path file(RegionKey key) {
            return directory.resolve(key.x() + "_" + key.z() + ".bin");
        }

        private Path lodFile(LodRegionKey key) {
            return directory.resolve("lod")
                    .resolve(Integer.toString(key.level()))
                    .resolve(key.region().x() + "_" + key.region().z() + ".bin");
        }

        private Region readRegion(RegionKey key) {
            Region region = new Region();
            Path file = file(key);
            if (!Files.isRegularFile(file)) {
                return region;
            }
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                    return region;
                }
                int count = input.readInt();
                if (count < 0 || count > REGION_ENTRY_COUNT) {
                    return region;
                }
                for (int entry = 0; entry < count; entry++) {
                    int index = input.readUnsignedShort();
                    if (index >= REGION_ENTRY_COUNT) {
                        throw new IOException("Invalid region entry index");
                    }
                    long signature = input.readLong();
                    byte[] colors = input.readNBytes(CHUNK_PIXELS);
                    if (colors.length != CHUNK_PIXELS) {
                        throw new EOFException("Incomplete cache entry");
                    }
                    region.put(index, entry(signature, colors));
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not load map cache region "
                        + file.getFileName() + ": " + exception.getMessage());
                region.clear();
            }
            return region;
        }

        /** Loads a sparse one-color region for one overview level. */
        private LodRegion readLodRegion(LodRegionKey key) {
            LodRegion region = new LodRegion();
            Path file = lodFile(key);
            if (!Files.isRegularFile(file)) {
                return region;
            }
            try (DataInputStream input = new DataInputStream(Files.newInputStream(file))) {
                if (input.readInt() != LOD_MAGIC || input.readInt() != VERSION) {
                    return region;
                }
                int count = input.readInt();
                if (count < 0 || count > REGION_ENTRY_COUNT) {
                    return region;
                }
                for (int entry = 0; entry < count; entry++) {
                    int index = input.readUnsignedShort();
                    if (index >= REGION_ENTRY_COUNT) {
                        throw new IOException("Invalid LOD region entry index");
                    }
                    region.put(index, input.readUnsignedByte());
                }
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not load map LOD region "
                        + file.getFileName() + ": " + exception.getMessage());
                region.clear();
            }
            return region;
        }

        private void trimIfNeeded(RegionKey protectedKey) {
            while (regions.size() > maxResidentRegions()) {
                Map.Entry<RegionKey, Region> candidate = null;
                Map.Entry<RegionKey, Region> dirtyCandidate = null;
                for (Map.Entry<RegionKey, Region> entry : regions.entrySet()) {
                    if (entry.getKey().equals(protectedKey)) {
                        continue;
                    }
                    if (entry.getValue().dirty.get()) {
                        if (dirtyCandidate == null
                                || entry.getValue().lastAccess
                                < dirtyCandidate.getValue().lastAccess) {
                            dirtyCandidate = entry;
                        }
                        continue;
                    }
                    if (candidate == null
                            || entry.getValue().lastAccess < candidate.getValue().lastAccess) {
                        candidate = entry;
                    }
                }
                if (candidate == null) {
                    if (dirtyCandidate != null) {
                        flushSoon(this, dirtyCandidate.getKey(), dirtyCandidate.getValue());
                    }
                    return;
                }
                if (!regions.remove(candidate.getKey(), candidate.getValue())) {
                    return;
                }
                evictions.incrementAndGet();
            }
        }

        /** Evicts clean LOD regions first; dirty regions stay until their delayed flush completes. */
        private void trimLodIfNeeded(LodRegionKey protectedKey) {
            while (lodRegions.size() > maxLodResidentRegions()) {
                Map.Entry<LodRegionKey, LodRegion> candidate = null;
                for (Map.Entry<LodRegionKey, LodRegion> entry : lodRegions.entrySet()) {
                    if (entry.getKey().equals(protectedKey) || entry.getValue().dirty.get()) {
                        continue;
                    }
                    if (candidate == null
                            || entry.getValue().lastAccess < candidate.getValue().lastAccess) {
                        candidate = entry;
                    }
                }
                if (candidate == null
                        || !lodRegions.remove(candidate.getKey(), candidate.getValue())) {
                    return;
                }
                evictions.incrementAndGet();
            }
        }

        private int entryCount() {
            return regions.values().stream().mapToInt(Region::entryCount).sum();
        }

        private int lodEntryCount() {
            return lodRegions.values().stream().mapToInt(LodRegion::entryCount).sum();
        }

        private void flushAll() {
            for (Map.Entry<RegionKey, Region> entry : regions.entrySet()) {
                flush(this, entry.getKey(), entry.getValue());
            }
            for (Map.Entry<LodRegionKey, LodRegion> entry : lodRegions.entrySet()) {
                flushLod(this, entry.getKey(), entry.getValue());
            }
        }
    }

    private static final class Region {
        private static final int VALIDATION_WORDS = REGION_ENTRY_COUNT / Long.SIZE;

        private final AtomicReferenceArray<Entry> entries =
                new AtomicReferenceArray<>(REGION_ENTRY_COUNT);
        private final AtomicLongArray validated = new AtomicLongArray(VALIDATION_WORDS);
        private final AtomicInteger entryCount = new AtomicInteger();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();
        private final AtomicBoolean flushExpedited = new AtomicBoolean();
        private volatile long lastAccess;

        private Entry entry(int index) {
            return entries.get(index);
        }

        private Entry put(int index, Entry entry) {
            Entry previous = entries.getAndSet(index, entry);
            if (previous == null) {
                entryCount.incrementAndGet();
            }
            return previous;
        }

        private void validate(int index) {
            updateValidation(index, true);
        }

        private void invalidate(int index) {
            updateValidation(index, false);
        }

        private boolean isValidated(int index) {
            int word = index / Long.SIZE;
            long mask = 1L << (index % Long.SIZE);
            return (validated.get(word) & mask) != 0L;
        }

        private void updateValidation(int index, boolean value) {
            int word = index / Long.SIZE;
            long mask = 1L << (index % Long.SIZE);
            long current;
            long updated;
            do {
                current = validated.get(word);
                updated = value ? current | mask : current & ~mask;
            } while (!validated.compareAndSet(word, current, updated));
        }

        private RegionSnapshot snapshot() {
            Entry[] snapshot = new Entry[REGION_ENTRY_COUNT];
            int count = 0;
            for (int index = 0; index < REGION_ENTRY_COUNT; index++) {
                snapshot[index] = entries.get(index);
                if (snapshot[index] != null) {
                    count++;
                }
            }
            return new RegionSnapshot(snapshot, count);
        }

        private int entryCount() {
            return entryCount.get();
        }

        private void clear() {
            for (int index = 0; index < REGION_ENTRY_COUNT; index++) {
                entries.set(index, null);
            }
            entryCount.set(0);
        }
    }

    /** Sparse, one-color region used by one level of the overview pyramid. */
    private static final class LodRegion {
        private final AtomicIntegerArray colors = new AtomicIntegerArray(REGION_ENTRY_COUNT);
        private final AtomicInteger entryCount = new AtomicInteger();
        private final AtomicBoolean dirty = new AtomicBoolean();
        private final AtomicBoolean flushScheduled = new AtomicBoolean();
        private volatile long lastAccess;

        private LodRegion() {
            for (int index = 0; index < REGION_ENTRY_COUNT; index++) {
                colors.set(index, MISSING_COLOR);
            }
        }

        private int color(int index) {
            return colors.get(index);
        }

        private int put(int index, int color) {
            int previous = colors.getAndSet(index, color);
            if (previous == MISSING_COLOR) {
                entryCount.incrementAndGet();
            }
            return previous;
        }

        private LodRegionSnapshot snapshot() {
            int[] snapshot = new int[REGION_ENTRY_COUNT];
            int count = 0;
            for (int index = 0; index < REGION_ENTRY_COUNT; index++) {
                int color = colors.get(index);
                snapshot[index] = color;
                if (color != MISSING_COLOR) {
                    count++;
                }
            }
            return new LodRegionSnapshot(snapshot, count);
        }

        private int entryCount() {
            return entryCount.get();
        }

        private void clear() {
            for (int index = 0; index < REGION_ENTRY_COUNT; index++) {
                colors.set(index, MISSING_COLOR);
            }
            entryCount.set(0);
        }
    }

    private record RegionKey(int x, int z) {
    }

    private record LodRegionKey(int level, RegionKey region) {
    }

    private record Entry(long signature, byte[] colors, byte overviewColor) {
    }

    private record RegionSnapshot(Entry[] entries, int count) {
    }

    private record LodRegionSnapshot(int[] colors, int count) {
    }
}
