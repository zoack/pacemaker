package org.pacemaker.variables.etcd;

import org.pacemaker.proto.models.Variable;
import org.pacemaker.variables.VariablesConnector;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class EtcdVariablesConnector implements VariablesConnector {
    ConcurrentHashMap<String, Variable> variables = new ConcurrentHashMap<>();


    @Override
    public CompletableFuture<Boolean> create(Variable variable) {
        variables.put(variable.getKey(), variable);
        return CompletableFuture.completedFuture(true);
    }

    @Override
    public CompletableFuture<Variable> get(String role, String namespace, String name) {
        return CompletableFuture.completedFuture(variables.get(name));
    }

    @Override
    public void close() throws IOException {

    }
}
