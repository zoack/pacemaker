package org.pacemaker.mesos.fenzo.constraints;

import com.netflix.fenzo.ConstraintEvaluator;
import com.netflix.fenzo.TaskRequest;
import com.netflix.fenzo.TaskTrackerState;
import com.netflix.fenzo.VirtualMachineCurrentState;
import org.pacemaker.proto.models.Container;
import org.pacemaker.proto.models.Disk;
import org.pacemaker.proto.models.Resources;
import org.pacemaker.proto.models.Task;
import org.apache.mesos.Protos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class PersistenceDiskConstraint implements ConstraintEvaluator {
    private static final Logger logger = LoggerFactory.getLogger(PersistenceDiskConstraint.class);

    private final Task task;

    public PersistenceDiskConstraint(Task task) {
        this.task = task;
    }


    @Override
    public String getName() {
        return "Persistence Disk Constraint";
    }


    @Override
    public Result evaluate(TaskRequest taskRequest, VirtualMachineCurrentState targetVM, TaskTrackerState taskTrackerState) {
        if(task.getStatus().getInitialized()) {
            Collection<Protos.Offer> currentOffers = targetVM.getAllCurrentOffers();
            List<String> offeredPersistenceId = currentOffers.stream()
                    .map(Protos.Offer::getResourcesList)
                    .flatMap(Collection::stream)
                    .filter(Protos.Resource::hasDisk)
                    .map(Protos.Resource::getDisk)
                    .filter(Protos.Resource.DiskInfo::hasPersistence)
                    .map(Protos.Resource.DiskInfo::getPersistence)
                    .map(Protos.Resource.DiskInfo.Persistence::getId)
                    .collect(Collectors.toList());

            boolean persistenceIdAvailable = task.getContainersList().stream().map(Container::getResources).map(Resources::getDisksList)
                    .flatMap(Collection::stream)
                    .filter(Disk::getPersistent)
                    .filter(Disk::hasVolume)
                    .map(Disk::getId).allMatch(offeredPersistenceId::contains);

            if(persistenceIdAvailable) {
                logger.debug("Offers __MATCHED__ with disk persistence ids for taskID={}", task.getId());
            }else{
                logger.debug("Offers did __NOT__ match with disk persistence ids for taskID={}", task.getId());
            }
            return new Result(persistenceIdAvailable, "Persistence id where not found on offers " + currentOffers.stream().map(Protos.Offer::getId).map(Protos.OfferID::getValue).collect(Collectors.joining(", ")));
        }else {
            logger.debug("Task was not initialized, there is no need to check if there is a disk constraint for taskID={}", task.getId());
            return new Result(true,"");
        }
    }
}
