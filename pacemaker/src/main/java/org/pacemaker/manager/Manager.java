package org.pacemaker.manager;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.UnaryOperator;

public interface Manager<T> {

    Map<String,T> loadAll();
    CompletableFuture<Optional<T>> loadAndUpdate(String taskID, UnaryOperator<T> updateOperator);
}
