package dev.funayd.fancyMap.map;

import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Asynchronously produces one complete map canvas.
 */
@FunctionalInterface
public interface AsyncMapCanvasRenderer {
    /**
     * Starts rendering on the supplied worker executor.
     *
     * @param executor bounded render executor
     * @return future canvas
     */
    CompletionStage<MapCanvas> render(Executor executor);
}
