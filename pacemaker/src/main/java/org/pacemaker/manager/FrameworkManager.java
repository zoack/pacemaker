package org.pacemaker.manager;

import org.pacemaker.mesos.MesosFramework;
import org.pacemaker.storage.StorageConnector;
import org.pacemaker.proto.models.FrameworkInfo;
import org.pacemaker.proto.models.FrameworkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class FrameworkManager implements Manager<FrameworkWrapper> {
    private static final Logger logger = LoggerFactory.getLogger(FrameworkManager.class);


    private ConcurrentMap<String, FrameworkWrapper> frameworks = new ConcurrentHashMap<>();
    private StorageConnector storageConnector;

    Function<FrameworkInfo, Optional<FrameworkWrapper>> createFrameworkWrapper = (FrameworkInfo frameworkInfo) ->
    {
        if (frameworkInfo == null) return Optional.empty();
        switch (frameworkInfo.getType()) {
            case Mesos:
                return Optional.of(new FrameworkWrapper(frameworkInfo, new MesosFramework(frameworkInfo)));
            case Kubernetes:
            case UNRECOGNIZED:
            default:
                return Optional.empty();
        }
    };


    public FrameworkManager(StorageConnector storageConnector) {
        this.storageConnector = storageConnector;
        storageConnector.loadAllFrameworks()
                .thenApply(Collection::stream)
                //TODO #Error handling
                .thenApply(xs -> xs.map(createFrameworkWrapper).map(o -> o.orElseThrow(RuntimeException::new)))
                .thenApply(xs -> xs.distinct().collect(Collectors.toMap(FrameworkWrapper::getId, p -> p, (p, q) -> p)))
                .handle((r,ex) -> {
                    if(ex == null){
                        frameworks = new ConcurrentHashMap<>(r);
                        return true;
                    }else {
                        throw new RuntimeException(ex);
                    }
                });
    }

    public FrameworkWrapper create(FrameworkInfo frameworkInfo) {
        return storageConnector
                .saveFramework(frameworkInfo)
                .thenApply(createFrameworkWrapper)
                .thenApply(Optional::orElseThrow)
                .whenComplete((r,ex) -> {
                    if(ex == null){
                        frameworks.put(r.getId(),r);
                    }else {
                        throw new RuntimeException(ex);
                    }
                }).join();

    }

    public Collection<FrameworkWrapper> loadActive() {
        return frameworks.values().stream()
                .filter(x -> x.getFrameworkInfo().getStatus().getState().equals(FrameworkStatus.State.Active))
                .collect(Collectors.toList());
    }


    public Map<String,FrameworkWrapper> loadAll() {
        return frameworks;
    }

    public Optional<FrameworkWrapper> load(String id) {
        return Optional.ofNullable(frameworks.getOrDefault(id,null));
    }

    private CompletableFuture<FrameworkWrapper> save(FrameworkWrapper frameworkWrapper) {
        return storageConnector
                .saveFramework(frameworkWrapper.getFrameworkInfo())
                .thenApply(newFrameworkInfo -> new FrameworkWrapper(newFrameworkInfo, frameworkWrapper.getFramework()))
                .whenComplete((r,ex) -> {
                    if(ex == null){
                        frameworks.put(r.getId(),r);
                    }else {
                        throw new RuntimeException(ex);
                    }
                });
    }

    @Override
    public CompletableFuture<Optional<FrameworkWrapper>> loadAndUpdate(String id, UnaryOperator<FrameworkWrapper> updateOperator) {
        return Optional.ofNullable(frameworks.computeIfPresent(id, (k,f) -> updateOperator.apply(f)))
                .map(this::save)
                .map(f -> f.thenApply(Optional::of))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
                .whenComplete((r,ex) -> {
                    r.ifPresent(framework -> frameworks.put(framework.getId(), framework));
                });
    }
}
