package org.pacemaker.storage;

import org.pacemaker.proto.models.*;

import java.io.Closeable;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public interface StorageConnector extends Closeable {

    CompletableFuture<Collection<FrameworkInfo>> loadAllFrameworks();
    CompletableFuture<FrameworkInfo> saveFramework(FrameworkInfo frameworkInfo);


    CompletableFuture<Deployment> loadDeployment(String deploymentID);
    CompletableFuture<Collection<Deployment>> loadAllDeployments(String tenant);
    CompletableFuture<Deployment> saveDeployment(Deployment deployment);

    CompletableFuture<Task> saveTask(Task task);
    CompletableFuture<Container> saveContainer(Container container);

    CompletableFuture<Node> loadNode(String nodeID);
    CompletableFuture<Collection<Node>> loadAllNodes();
    CompletableFuture<Node> saveNode(Node node);


}
