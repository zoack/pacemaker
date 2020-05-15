package org.pacemaker.watchers;

import org.pacemaker.manager.TaskManager;
import org.pacemaker.proto.models.Container;
import org.pacemaker.proto.models.Error;
import org.pacemaker.proto.models.Probe;
import org.pacemaker.proto.models.ProbeResult;
import org.pacemaker.watchers.probes.HttpProbe;
import org.pacemaker.watchers.probes.TCPProbe;
import org.pacemaker.utils.FutureUtils;
import org.pacemaker.utils.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.stream.Collectors;

public class WatcherImpl implements Watcher {

    private static final Logger logger = LoggerFactory.getLogger(WatcherImpl.class);


    private final ScheduledExecutorService executor;

    private final ConcurrentMap<String, ScheduledFuture<?>> watchers;
    private final TaskManager taskManager;
    private ConcurrentMap<String, ProbeResult> world = new ConcurrentHashMap<>();

    //TODO resilience
    public WatcherImpl(TaskManager taskManager) {
        //TODO #setup
        executor = Executors.newScheduledThreadPool(5);
        watchers = new ConcurrentHashMap<>();
        this.taskManager = taskManager;
    }

    @Override
    public Map<String, ProbeResult> world() {
        return world;
    }

    private Probe getProbeSpec(Container container){
        Probe probeSpec;
        if (container.getStatus().getReady()) {
            logger.trace("Configuring __LIVENESS__ probe for container, containerID={}", container.getId());
            probeSpec = container.getLivenessProbe();
        } else if (container.getStatus().getInitialized()) {
            logger.trace("Configuring __READINESS__ probe for container, containerID={}", container.getId());
            probeSpec = container.getReadinessProbe();
        } else {
            logger.trace("Configuring __STARTUP__ probe for container, containerID={}", container.getId());
            probeSpec = container.getStartUpProbe();
        }
        return probeSpec;
    }

    private org.pacemaker.watchers.probes.Probe getProbe(Container container) {
        //TODO #ServiceDiscovery
        String defaultHost = container.getInternalName() + ".pacemaker.mesos";
        Probe probeSpec = getProbeSpec(container);
        org.pacemaker.watchers.probes.Probe probe = null;

        if (probeSpec.hasTcpSocket()) {
            //TODO #ServiceDiscovery
            if (probeSpec.getTcpSocket().getHost().equals("")) {
                Probe.Builder defaultHostProbe = probeSpec.toBuilder().setTcpSocket(probeSpec.getTcpSocket().toBuilder().setHost(defaultHost));
                probe = new TCPProbe(defaultHostProbe.getTcpSocket());
            } else {
                probe = new TCPProbe(probeSpec.getTcpSocket());
            }
        } else if (probeSpec.hasHttpRequest()) {
            //TODO #ServiceDiscovery
            if (probeSpec.getHttpRequest().getHost().equals("")) {
                Probe.Builder defaultHostProbe = probeSpec.toBuilder().setHttpRequest(probeSpec.getHttpRequest().toBuilder().setHost(defaultHost));
                probe = new HttpProbe(defaultHostProbe.getHttpRequest());
            } else {
                probe = new HttpProbe(probeSpec.getHttpRequest());
            }
        }else {
            logger.trace("No probe specification found for container, default probe will be used, containerID={}", container.getId());
            probe = () -> CompletableFuture.completedFuture(ProbeResult.newBuilder().setResult(ProbeResult.Result.Success).build());
        }

        return probe;
    }


    @Override
    public void watch(String taskID) {
        watchers.compute(taskID, (k, v) -> {
            if (v == null || v.isCancelled()) {
                Runnable watcher = () -> taskManager.load(taskID).map(task ->
                        FutureUtils.all(task.getContainersList()
                                .stream()
                                .peek(x -> LogUtils.peekAndTraceLog(x,logger,"Probing container, containerID={}", x.getId()))
                                .map(container ->
                                        getProbe(container).probe()
                                                .thenApply(x -> LogUtils.peekAndTraceLog(x, logger, "Probe result for container: [{}], containerID={}",x.getResult(), container.getId()))
                                                .whenComplete((r, ex) ->
                                                    world.put(container.getId(),
                                                        Objects.requireNonNullElseGet(r, () -> ProbeResult.newBuilder()
                                                                .setResult(ProbeResult.Result.Failure)
                                                                .setError(Error.newBuilder()
                                                                        .setFatal(true)
                                                                        .setReason(ex.getMessage())
                                                                .build())
                                                .build())))
                                ).collect(Collectors.toList())).join());
                //TODO #setup
                int delay = 20;
                int period = 10;
                logger.debug("__STARTING__ watching  task with delay {}s and a period of {}s, task={}", delay, period, taskID);
                return executor.scheduleAtFixedRate(watcher, delay, period, TimeUnit.SECONDS);
            }else{
                return v;
            }
        });
    }


    @Override
    public void stop(String containerID) {
        watchers.computeIfPresent(containerID, (k,v) -> {
            if(!v.isCancelled()) {
                logger.debug("__STOPPING__ watching container, containerID={}", containerID);
                v.cancel(false);
            }
            return v;
        });
    }

    @Override
    public void close() {
        logger.info("__SHUTTING_DOWN__ controller...");
        if(executor != null) executor.shutdown();
    }


}
