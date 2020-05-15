package org.pacemaker.services;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.pacemaker.proto.services.SecretsCreateRequest;
import org.pacemaker.proto.services.SecretsResponse;
import org.pacemaker.proto.services.SecretsServiceGrpc;
import org.pacemaker.secrets.SecretsConnector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class SecretsService extends SecretsServiceGrpc.SecretsServiceImplBase {
    private static final Logger logger = LoggerFactory.getLogger(SecretsService.class);
    private final SecretsConnector secretsConnector;

    public SecretsService(SecretsConnector secretsConnector) {
        this.secretsConnector = secretsConnector;
    }


    @Override
    public void create(SecretsCreateRequest request, StreamObserver<SecretsResponse> responseObserver) {
        if(validate(request)) {
            List<CompletableFuture<Boolean>> createdSecrets = request.getSecretsList()
                    .stream()
                    .map(secretsConnector::create)
                    .collect(Collectors.toList());
            if (request.getSecretsList().size() == createdSecrets.size()) {
                responseObserver.onNext(SecretsResponse.newBuilder().build());
                responseObserver.onCompleted();
            } else {
                responseObserver.onError(Status.DATA_LOSS.withDescription("One or more secrets could not be created").asRuntimeException());
            }
        }else{
            responseObserver.onError(Status.INVALID_ARGUMENT.withDescription("Secret request is not valid").asRuntimeException());
        }
    }
    //TODO #validate
    private boolean validate(SecretsCreateRequest request){
        return !request.getSecretsList().isEmpty();
    }


}
