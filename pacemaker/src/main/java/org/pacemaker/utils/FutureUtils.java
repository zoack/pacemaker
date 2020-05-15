package org.pacemaker.utils;



import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class FutureUtils {

    private FutureUtils() {
    }

    // Waits for *all* futures to complete and returns a list of results.
    // If *any* future completes exceptionally then the resulting future will also complete exceptionally.
    @SuppressWarnings("rawtypes")
    public static <T> CompletableFuture<List<T>> all(List<CompletableFuture<T>> futures) {
        CompletableFuture[] cfs = futures.toArray(new CompletableFuture[0]);

        return CompletableFuture.allOf(cfs)
                .thenApply(ignored -> futures.stream()
                        .map(CompletableFuture::join)
                        .collect(Collectors.toList())
                );
    }
}
