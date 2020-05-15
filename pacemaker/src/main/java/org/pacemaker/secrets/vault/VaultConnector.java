package org.pacemaker.secrets.vault;

import org.pacemaker.proto.models.Secret;
import org.pacemaker.secrets.SecretsConnector;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class VaultConnector implements SecretsConnector {

    ConcurrentHashMap<String, Secret> variables = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Boolean> create(Secret secret) {
        variables.put(secret.getName(), secret);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Secret> get(String role, String namespace, String name) {
        return CompletableFuture.completedFuture(variables.get(name));
    }
}
