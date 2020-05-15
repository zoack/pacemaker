package org.pacemaker.manager;

import org.pacemaker.proto.models.Container;
import org.pacemaker.proto.models.Task;
import org.pacemaker.storage.StorageConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class TaskManager implements Manager<Task> {
    private static final Logger logger = LoggerFactory.getLogger(TaskManager.class);

    private final StorageConnector storageConnector;
    private final ConcurrentMap<String, Task> tasks = new ConcurrentHashMap<>();
    private final ContainerManager containerManager;

    public TaskManager(StorageConnector storageConnector, ContainerManager containerManager) {
        this.storageConnector = storageConnector;
        this.containerManager = containerManager;
    }


    public Map<String,Task> loadAll(){
        return tasks;
    }

    public Optional<Task> load(String taskID){
        Task task = tasks.get(taskID);
        if(task != null) {
            List<Container> collect = task.getContainersList().stream().map(Container::getId).map(containerManager::load).flatMap(Optional::stream).collect(Collectors.toList());
            return Optional.of(task.toBuilder().clearContainers().addAllContainers(collect).build());
        }else{
            return Optional.empty();
        }
    }

    public CompletableFuture<Optional<Task>> loadAndUpdate(String taskID, UnaryOperator<Task> updateOperator){
        return Optional.ofNullable(tasks.computeIfPresent(taskID, (k,t) -> {
            Task newTask = updateOperator.apply(t);
            newTask.getContainersList().stream().map(Container::getId).forEach(id -> containerManager.loadAndUpdate(id, c -> c));
            return newTask;
        }))
                .map(storageConnector::saveTask)
                .map(f -> f.thenApply(Optional::of))
                .orElseGet(() -> CompletableFuture.completedFuture(Optional.empty()))
                .whenComplete((r,ex) -> {
                    r.ifPresent(task -> tasks.put(task.getId(), task));
                });
    }

    public CompletableFuture<Task> create(Task task) {
        return storageConnector.saveTask(task)
                .thenApply(x -> {
                    x.getContainersList().forEach(containerManager::create);
                    return x;
                })
                .whenComplete((x,ex) -> {
                    if(ex == null){
                        tasks.put(x.getId(),x);
                    }
                    else{
                        throw new RuntimeException();
                    }
                });
    }
}

