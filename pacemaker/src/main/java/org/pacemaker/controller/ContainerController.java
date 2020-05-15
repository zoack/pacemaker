package org.pacemaker.controller;

import org.pacemaker.Sensor;
import org.pacemaker.proto.models.Container;
import org.pacemaker.proto.models.ContainerStatus;
import org.pacemaker.proto.models.Error;
import org.pacemaker.proto.models.ProbeResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;

public class ContainerController implements Controller<Container,ProbeResult>{

    private static final Logger logger = LoggerFactory.getLogger(ContainerController.class);

    private Collection<Sensor<ProbeResult>> sensors;

    public ContainerController(Collection<Sensor<ProbeResult>> sensors) {
        this.sensors = sensors;
    }

    @Override
    public Container control(Container container) {
        //TODO @etaracon #FAILOVER
        return container.toBuilder().build();
    }

    @Override
    public ProbeResult fetchStatus(Container c) {
        return sensors.stream()
                .map(Sensor::world)
                //TODO #MultipleWorlds algorithm that select the fittest world for any given container
                .findAny()
                .map(w -> w.get(c.getId()))
                //TODO this should not return Lost if there is no container on the map
                .orElseGet( () -> ProbeResult.newBuilder().setResult(ProbeResult.Result.Lost).build());
    }

    @Override
    public Container updateStatus(Container container, ProbeResult probeResult){
        Container.Builder builder = container.toBuilder();

        if(!probeResult.getLastProbeTime().equals(container.getStatus().getLastProbe().getLastProbeTime())) {
            ContainerStatus.Builder newStatus = container.getStatus().toBuilder()
                    .setLastProbe(probeResult);

            if (probeResult.getResult().equals(ProbeResult.Result.Success)) {
                if (!newStatus.getInitialized()) {
                    newStatus.setInitialized(true);
                    logger.info("Container has been __INITIALIZED__, containerID={}", container.getId());
                } else if (!newStatus.getReady()) {
                    newStatus.setReady(true);
                    logger.info("Container is __READY__, containerID={}", container.getId());
                }
            }

            if (probeResult.getResult().equals(ProbeResult.Result.Failure)) {
                if(newStatus.getConsecutiveFailures() == 0){
                    logger.info("Container has failed its probe test, containerID={}", container.getId());
                }
                newStatus.setConsecutiveFailures(Integer.MAX_VALUE == newStatus.getConsecutiveFailures() ? Integer.MAX_VALUE : newStatus.getConsecutiveFailures() + 1);
            }

            if (!probeResult.getResult().equals(ProbeResult.Result.Failure) && !newStatus.getError().getReason().isEmpty()) {
                newStatus.setError(Error.newBuilder().build()).setConsecutiveFailures(0);
            }
            builder.setStatus(newStatus);
        }
        return builder.build();
    }
}
