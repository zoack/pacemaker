package org.pacemaker;

import java.util.concurrent.CompletableFuture;

@FunctionalInterface
public interface Preprocessor<T> {

    CompletableFuture<T> process(T value);
}
