package org.pacemaker.variables;

import org.pacemaker.proto.models.*;

import java.io.Closeable;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;

public interface VariablesConnector extends Closeable {

    CompletableFuture<Boolean> create(Variable variable);
    CompletableFuture<Variable> get(String role, String namespace, String name);

}
