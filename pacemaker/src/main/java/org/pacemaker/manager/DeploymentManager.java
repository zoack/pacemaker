package org.pacemaker.manager;

import org.pacemaker.proto.models.Deployment;
import org.pacemaker.storage.StorageConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class DeploymentManager implements Manager<Deployment> {
    private static final Logger logger = LoggerFactory.getLogger(DeploymentManager.class);

    private final StorageConnector storageConnector;
    private final TaskManager taskManager;
    private ConcurrentMap<String, Deployment> deploymentMap = new ConcurrentHashMap<>();

    public DeploymentManager(StorageConnector storageConnector, TaskManager taskManager) {
        this.storageConnector = storageConnector;
        this.taskManager = taskManager;
        storageConnector.loadAllDeployments("")
                .thenApply(Collection::stream)
                .thenApply(xs -> xs.distinct().collect(Collectors.toMap(Deployment::getId, p -> p, (p, q) -> p)))
                .handle((r,ex) -> {
                    if(ex == null){
                        deploymentMap.putAll(r);
                        return true;
                    }else {
                        throw new RuntimeException(ex);
                    }
                });
    }

    public CompletableFuture<Deployment> create(Deployment deployment){
        return storageConnector.saveDeployment(deployment)
                .thenApply(x -> {
                    x.getTasksList().forEach(taskManager::create);
                    return x;
                })
                .whenComplete((x,ex) -> {
                    if(ex == null){
                        deploymentMap.put(x.getId(),x);
                    }
                    else{
                        throw new RuntimeException();
                    }
                });
    }

    @Override
    public Map<String, Deployment> loadAll() {
        return deploymentMap;
    }

    public CompletableFuture<Optional<Deployment>> loadAndUpdate(String deploymentID, UnaryOperator<Deployment> updateOperator){
        return Optional.ofNullable(deploymentMap.computeIfPresent(deploymentID, (k,d) -> {
            Deployment newDeployment = updateOperator.apply(d);
            newDeployment.getTasksList().forEach(t -> taskManager.loadAndUpdate(t.getId(), t1 -> t1.toBuilder().setDesiredState(t.getDesiredState()).build()));
            return newDeployment;
        }))
                .map(storageConnector::saveDeployment)
                .map(f -> f.thenApply(Optional::of))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()));
    }

}

