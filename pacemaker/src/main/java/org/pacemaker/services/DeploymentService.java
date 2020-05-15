package org.pacemaker.services;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.util.Strings;
import org.pacemaker.manager.DeploymentManager;
import org.pacemaker.proto.models.Deployment;
import org.pacemaker.proto.models.Task;
import org.pacemaker.proto.models.TaskStatus;
import org.pacemaker.proto.services.DeploymentRequest;
import org.pacemaker.proto.services.DeploymentResponse;
import org.pacemaker.proto.services.DeploymentServiceGrpc;
import org.pacemaker.proto.services.IDsRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class DeploymentService extends DeploymentServiceGrpc.DeploymentServiceImplBase  {
    private static final Logger logger = LoggerFactory.getLogger(DeploymentService.class);
    private final DeploymentManager deploymentManager;

    public DeploymentService(DeploymentManager deploymentManager) {
        this.deploymentManager = deploymentManager;
    }


    @Override
    public void deploy(DeploymentRequest request, StreamObserver<DeploymentResponse> responseObserver) {
        //TODO @etarascon #Validate
        List<Deployment> deployments = request.getDeploymentsList();
        boolean validRequest = deployments.stream().allMatch(this::validateDeployment);
        if(validRequest){
            //TODO @etarascon #Robustness #Atomicity
            List<CompletableFuture<Deployment>> acceptedDeployments = deployments.stream()
                    .map(deploymentManager::create).collect(Collectors.toList());
            //TODO how to add to response when completed action?
            if(!acceptedDeployments.isEmpty()) {
                responseObserver.onNext(DeploymentResponse.newBuilder().build());
                responseObserver.onCompleted();
            }else {
                responseObserver.onError(Status.DATA_LOSS.withDescription("One or more deployments could not be offered to be deployed").asRuntimeException());
            }
        }else{
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Deployment is not valid").asRuntimeException());
        }
    }

    @Override
    public void stop(IDsRequest request, StreamObserver<DeploymentResponse> responseObserver) {
        List<String> deployments = request.getIdsList();
        List<CompletableFuture<Optional<Deployment>>> stoppedDeployments = deployments.stream()
                .map(d -> deploymentManager.loadAndUpdate(d, this::stopDeployment))
                .collect(Collectors.toList());

        //TODO how to add to response when completed action?
        if(!stoppedDeployments.isEmpty()) {
            responseObserver.onNext(DeploymentResponse.newBuilder().build());
            responseObserver.onCompleted();
        }else {
            responseObserver.onError(Status.DATA_LOSS.withDescription("One or more deployments could not be stopped").asRuntimeException());
        }
    }

    @Override
    public void start(IDsRequest request, StreamObserver<DeploymentResponse> responseObserver) {
        List<String> deployments = request.getIdsList();
        List<CompletableFuture<Optional<Deployment>>> startedDeployments = deployments.stream()
                .map(d -> deploymentManager.loadAndUpdate(d, this::startDeployment))
                .collect(Collectors.toList());
        //TODO how to add to response when completed action?
        if(!startedDeployments.isEmpty()) {
            responseObserver.onNext(DeploymentResponse.newBuilder().build());
            responseObserver.onCompleted();
        }else {
            responseObserver.onError(Status.DATA_LOSS.withDescription("One or more deployments could not started").asRuntimeException());
        }
    }


    private Deployment stopDeployment(Deployment deployment){
        return deployment.toBuilder()
                .clearTasks()
                .addAllTasks(deployment
                        .getTasksList()
                        .stream()
                        .map(t -> t.toBuilder()
                                .setDesiredState(TaskStatus.State.Finished)
                                /*.clearContainers()
                                .addAllContainers(
                                        t.getContainersList()
                                                .stream()
                                                .map(c -> c.toBuilder().setStatus(c.getStatus().toBuilder().setDesiredState(ContainerStatus.State.Finished)).build())
                                                .collect(Collectors.toList())
                                )*/)
                        .map(Task.Builder::build).collect(Collectors.toList()))
                .build();
    }


    private Deployment startDeployment(Deployment deployment){
        return deployment.toBuilder()
                .clearTasks()
                .addAllTasks(deployment
                        .getTasksList()
                        .stream()
                        .map(t -> t.toBuilder().setDesiredState(TaskStatus.State.Running)
                               /* .clearContainers()
                                .addAllContainers(
                                        t.getContainersList()
                                                .stream()
                                                //.map(c -> c.toBuilder().setStatus(c.getStatus().toBuilder().setDesiredState(ContainerStatus.State.Running)).build())
                                                .collect(Collectors.toList())
                                )*/)
                        .map(Task.Builder::build).collect(Collectors.toList()))
                .build();
    }


    //TODO #validate
    private boolean validateDeployment(Deployment deployment){
        return Strings.isNotEmpty(deployment.getPrincipal());
    }


}
