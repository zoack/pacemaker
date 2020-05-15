package org.pacemaker;

import org.pacemaker.api.GRPCServer;
import org.pacemaker.controller.ContainerController;
import org.pacemaker.controller.ControlLoop;
import org.pacemaker.controller.FrameworkController;
import org.pacemaker.controller.TaskController;
import org.pacemaker.framework.Framework;
import org.pacemaker.manager.*;
import org.pacemaker.proto.models.*;
import org.pacemaker.scheduler.Scheduler;
import org.pacemaker.scheduler.SchedulerQueue;
import org.pacemaker.secrets.SecretsConnector;
import org.pacemaker.secrets.vault.VaultConnector;
import org.pacemaker.storage.StorageConnector;
import org.pacemaker.storage.zookeeper.ZookeeperConnector;
import org.pacemaker.variables.VariablesConnector;
import org.pacemaker.variables.etcd.EtcdVariablesConnector;
import org.pacemaker.watchers.Watcher;
import org.pacemaker.watchers.WatcherImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class App {

    private static final Logger logger = LoggerFactory.getLogger("App");

    public static void main(String[] args) {
        logger.info("Starting Pacemaker...");
        try(StorageConnector connector = new ZookeeperConnector()) {
            //TODO #multitenant
            //TODO #setup

            ContainerManager containerManager = new ContainerManager(connector);
            TaskManager taskManager = new TaskManager(connector, containerManager);
            DeploymentManager deploymentManager = new DeploymentManager(connector,taskManager);
            FrameworkManager frameworkManager = new FrameworkManager(connector);

            SecretsConnector secretsConnector = new VaultConnector();
            VariablesConnector variablesConnector = new EtcdVariablesConnector();

            try (GRPCServer server = new GRPCServer(frameworkManager, deploymentManager, secretsConnector, variablesConnector, 8080);
                Watcher watcher = new WatcherImpl(taskManager)) {

                ControlLoop<FrameworkWrapper,FrameworkStatus> frameworkControlLoop = new ControlLoop<>(
                        frameworkManager,
                        f -> true,
                        new FrameworkController()
                );


                Scheduler scheduler = new SchedulerQueue(frameworkManager);

                Preprocessor<Task> assigner = task -> CompletableFuture.supplyAsync(() ->
                                frameworkManager.loadActive()
                                        .stream()
                                        .findFirst()
                                        .map(FrameworkWrapper::getFramework)
                                        .map(Framework::assigner)
                                        .flatMap(a -> a.assign(task)
                                                .map(assignment -> task.toBuilder().setAssignment(assignment).build()))
                                        .orElseGet(
                                                () -> task.toBuilder().setStatus(
                                                        task.getStatus().toBuilder()
                                                                .setState(TaskStatus.State.Failed)
                                                                .setError(task.getStatus().getError().toBuilder()
                                                                        .setFatal(false)
                                                                        .setReason("Task could not be assigned when starting")))
                                                        .build()));
                List<Preprocessor<Task>> preprocessors = Collections.singletonList(assigner);

                ControlLoop<Task,TaskStatus> taskControlLoop = new ControlLoop<>(
                        taskManager,
                        t -> true,
                        new TaskController(scheduler,frameworkManager, preprocessors, watcher)
                );

                ControlLoop<Container,ProbeResult> containerControlLoop = new ControlLoop<>(
                        containerManager,
                        c -> true,
                        new ContainerController(Collections.singletonList(watcher))
                );


                Service service = new Service(server,scheduler, frameworkControlLoop, taskControlLoop,containerControlLoop);
                service.run();
                System.exit(0);
            } catch (IOException e) {
                //TODO #errorHandling
                throw new RuntimeException("Error creating grpc server",e);
            }
        } catch (Exception e) {
            logger.error("Unexpected error creating storage connector, shutting down app...", e);
            System.exit(1);
        }
    }
}
