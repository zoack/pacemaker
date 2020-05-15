package org.pacemaker.services;

import org.pacemaker.manager.FrameworkManager;
import org.pacemaker.manager.FrameworkWrapper;
import org.pacemaker.proto.models.FrameworkInfo;
import org.pacemaker.proto.models.FrameworkStatus;
import org.pacemaker.proto.services.FrameworkCreateRequest;
import org.pacemaker.proto.services.FrameworkIDsRequest;
import org.pacemaker.proto.services.FrameworkResponse;
import org.pacemaker.proto.services.FrameworkServiceGrpc;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class FrameworkService extends FrameworkServiceGrpc.FrameworkServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(FrameworkService.class);
    private final FrameworkManager frameworkManager;

    public FrameworkService(FrameworkManager frameworkManager) {
        this.frameworkManager = frameworkManager;
    }

    @Override
    public void create(FrameworkCreateRequest request, StreamObserver<FrameworkResponse> responseObserver) {
        //TODO @etarascon #Validate
        List<FrameworkInfo> frameworks = request.getFrameworksList();
        boolean validRequest = frameworks.stream().allMatch(this::validate);
        if(validRequest){
            //TODO @etarascon #Robustness #Atomicity
            List<FrameworkWrapper> frameworksCreated = frameworks.stream()
                    .map(frameworkManager::create)
                    .collect(Collectors.toList());
            //TODO how to add to response when completed action?
            if(!frameworksCreated.isEmpty()) {
                responseObserver.onNext(FrameworkResponse.newBuilder().build());
                responseObserver.onCompleted();
            }else {
                responseObserver.onError(Status.DATA_LOSS.withDescription("One or more framework could not be created").asRuntimeException());
            }
        }else{
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Framework request is not valid").asRuntimeException());
        }
    }

    @Override
    public void start(FrameworkIDsRequest request, StreamObserver<FrameworkResponse> responseObserver) {
        List<CompletableFuture<Optional<FrameworkWrapper>>> startedFrameworks = request.getFrameworkIdsList().stream()
                .map(d -> frameworkManager.loadAndUpdate(d,
                        f -> new FrameworkWrapper(f.getFrameworkInfo().toBuilder().setDesiredState(FrameworkStatus.State.Active).build(), f.getFramework())))
                .collect(Collectors.toList());

        //TODO how to add to response when completed action?
        if(!startedFrameworks.isEmpty()) {
            responseObserver.onNext(FrameworkResponse.newBuilder().build());
            responseObserver.onCompleted();
        }else {
            responseObserver.onError(Status.DATA_LOSS.withDescription("One or more frameworks could not be created").asRuntimeException());
        }
    }

    @Override
    public void stop(FrameworkIDsRequest request, StreamObserver<FrameworkResponse> responseObserver) {
        List<CompletableFuture<Optional<FrameworkWrapper>>> stoppedFrameworks = request.getFrameworkIdsList().stream()
                .map(d -> frameworkManager.loadAndUpdate(d,
                        f -> new FrameworkWrapper(f.getFrameworkInfo().toBuilder().setDesiredState(FrameworkStatus.State.Inactive).build(), f.getFramework())))
                .collect(Collectors.toList());
        //TODO how to add to response when completed action?
        if(!stoppedFrameworks.isEmpty()) {
            responseObserver.onNext(FrameworkResponse.newBuilder().build());
            responseObserver.onCompleted();
        }else {
            responseObserver.onError(Status.DATA_LOSS.withDescription("One or more frameworks could not be created").asRuntimeException());
        }

    }

    //TODO #validate
    private boolean validate(FrameworkInfo frameworkInfo){
        return Strings.isNotEmpty(frameworkInfo.getPrincipal());
    }


}
