package dev.funayd.fancyMap.map;

/** Minimal executable guard for the LOD scale boundaries. */
public final class LodMathSelfCheck {
    private LodMathSelfCheck() {
    }

    /** Runs without a test framework via {@code gradlew.bat lodSelfCheck}. */
    public static void main(String[] args) {
        require(PersistentChunkRenderCache.selectLodLevel(16.0D, 22) == 0);
        require(PersistentChunkRenderCache.selectLodLevel(16.01D, 22) == 1);
        require(PersistentChunkRenderCache.selectLodLevel(64.0D, 22) == 2);
        require(PersistentChunkRenderCache.lodSpanChunks(22, 22) == 4_194_304);
    }

    private static void require(boolean condition) {
        if (!condition) {
            throw new AssertionError("Unexpected LOD scale result");
        }
    }
}
