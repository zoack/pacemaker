package org.pacemaker.storage.zookeeper;

import org.pacemaker.proto.models.*;
import org.pacemaker.storage.StorageConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ZookeeperConnector implements StorageConnector {

    private static final Logger logger = LoggerFactory.getLogger(ZookeeperConnector.class);


    @Override
    public CompletableFuture<Collection<FrameworkInfo>> loadAllFrameworks() {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }


    @Override
    public CompletableFuture<FrameworkInfo> saveFramework(FrameworkInfo frameworkInfo) {
        String id = frameworkInfo.getId();
        if(id.isEmpty()){
            id = UUID.randomUUID().toString();
        }
        return CompletableFuture.completedFuture(frameworkInfo.toBuilder().setId(id).build());
    }


    @Override
    public CompletableFuture<Node> loadNode(String nodeID) {
        return CompletableFuture.completedFuture(Node.newBuilder().build());
    }


    @Override
    public CompletableFuture<Deployment> loadDeployment(String deployment) {
        return CompletableFuture.completedFuture(Deployment.newBuilder().build());
    }


    @Override
    public CompletableFuture<Collection<Deployment>> loadAllDeployments(String tenant) {
        return CompletableFuture.completedFuture(Collections.emptyList());
    }


    @Override
    public CompletableFuture<Deployment> saveDeployment(Deployment deployment) {
        if(deployment.getId() == null || deployment.getId().isEmpty()) {
            return CompletableFuture.completedFuture(deployment.toBuilder().setId(UUID.randomUUID().toString()).build());
        }else {
            return CompletableFuture.completedFuture(deployment);
        }
    }

    @Override
    public CompletableFuture<Task> saveTask(Task task) {
        return CompletableFuture.completedFuture(task);
    }

    @Override
    public CompletableFuture<Container> saveContainer(Container container) {
        return CompletableFuture.completedFuture(container);
    }

    @Override
    public CompletableFuture<Node> saveNode(Node node) {
        return CompletableFuture.completedFuture(node);
    }

    @Override
    public CompletableFuture<Collection<Node>> loadAllNodes() {
        return CompletableFuture.supplyAsync(Collections::emptyList);
    }

    @Override
    public void close() throws IOException {

    }
}
