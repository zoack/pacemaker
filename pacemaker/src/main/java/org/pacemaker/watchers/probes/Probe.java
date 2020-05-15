package org.pacemaker.watchers.probes;

import org.pacemaker.proto.models.ProbeResult;

import java.util.concurrent.CompletableFuture;

public interface Probe {

    CompletableFuture<ProbeResult> probe();

}
