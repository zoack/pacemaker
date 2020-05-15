package org.pacemaker.secrets;

import org.pacemaker.proto.models.Secret;

import java.util.concurrent.CompletableFuture;

public interface SecretsConnector {
    CompletableFuture<Boolean> create(Secret secret);
    CompletableFuture<Secret> get(String role, String namespace, String name);
}
