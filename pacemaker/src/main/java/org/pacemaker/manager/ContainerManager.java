package org.pacemaker.manager;

import org.pacemaker.proto.models.Container;
import org.pacemaker.storage.StorageConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;

public class ContainerManager implements Manager<Container> {
    private static final Logger logger = LoggerFactory.getLogger(ContainerManager.class);

    private final StorageConnector storageConnector;
    private ConcurrentMap<String, Container> containers = new ConcurrentHashMap<>();

    public ContainerManager(StorageConnector storageConnector) {
        this.storageConnector = storageConnector;
    }


    public Map<String,Container> loadAll(){
        return containers;
    }

    public Optional<Container> load(String containerID){
        return Optional.ofNullable(containers.get(containerID));
    }

    public CompletableFuture<Optional<Container>> loadAndUpdate(String containerID, UnaryOperator<Container> updateOperator){
        return Optional.ofNullable(containers.computeIfPresent(containerID, (k, d) -> updateOperator.apply(d)))
                .map(storageConnector::saveContainer)
                .map(f -> f.thenApply(Optional::of))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
                .whenComplete((r,ex) -> {
                    r.ifPresent(container -> containers.put(container.getId(), container));
                });
    }

    public CompletableFuture<Container> create(Container container) {
        return storageConnector.saveContainer(container)
                .whenComplete((x,ex) -> {
                    if(ex == null){
                        containers.put(x.getId(),x);
                    }
                    else{
                        throw new RuntimeException();
                    }
                });
    }
}

