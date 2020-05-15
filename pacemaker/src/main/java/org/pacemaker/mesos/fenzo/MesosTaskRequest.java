package org.pacemaker.mesos.fenzo;


import com.netflix.fenzo.ConstraintEvaluator;
import com.netflix.fenzo.TaskRequest;
import com.netflix.fenzo.VMTaskFitnessCalculator;
import org.pacemaker.mesos.fenzo.constraints.PersistenceDiskConstraint;
import org.pacemaker.proto.models.*;

import java.util.*;

public class MesosTaskRequest implements TaskRequest {
    private final Task task;
    private final MesosTask mesosTask;
    private AssignedResources assignedResources;
    private String uuid;

    public MesosTaskRequest(Task task, MesosTask mesosTask, String uuid) {
        //TODO we should save this somewhere
        this.task = task;
        this.mesosTask = mesosTask;
        this.uuid = uuid;
    }

    @Override
    public String getId() {
        return uuid;
    }


    @Override
    public String taskGroupName() {
        return task.getName();
    }

    @Override
    public double getCPUs() {
        return task.getContainersList().stream().map(Container::getResources).mapToDouble(Resources::getCpus).sum() +
                (!mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getCpus());
    }

    @Override
    public double getMemory() {
        return task.getContainersList().stream().map(Container::getResources).mapToDouble(Resources::getMemory).sum() +
                (!mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getMemory());
    }

    @Override
    public double getNetworkMbps() {
        return 0;
    }

    @Override
    public double getDisk() {
        return task.getContainersList().stream().map(Container::getResources).map(Resources::getDisksList).flatMap(Collection::stream).mapToDouble(Disk::getSize).sum() +
                (!mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getDisksList().stream().mapToDouble(Disk::getSize).sum());
    }

    @Override
    public int getPorts() {
        return task.getContainersList().stream().map(Container::getResources).mapToInt(Resources::getPortsCount).sum()
                + (!mesosTask.hasExecutorResources() ? 0 :mesosTask.getExecutorResources().getPortsCount());
    }


    @Override
    public Map<String, Double> getScalarRequests() {
        return null;
    }


    @Override
    public Map<String, NamedResourceSetRequest> getCustomNamedResources() {
        return null;
    }


    @Override
    //TODO match persistence disk offers
    public List<? extends ConstraintEvaluator> getHardConstraints() {
        //TODO #workloads think this better, what types of workload will need hard constraints???
        return task.getWorkload().equals(Task.Workload.STATEFUL) ? Collections.singletonList(new PersistenceDiskConstraint(task)) : Collections.emptyList();
    }


    @Override
    public List<? extends VMTaskFitnessCalculator> getSoftConstraints() {
        return new ArrayList<>();//constraintGenerator.generateSoftPlacement(deployment, task.getName());
    }

    @Override
    public void setAssignedResources(AssignedResources assignedResources) {
        this.assignedResources = assignedResources;
    }

    @Override
    public AssignedResources getAssignedResources() {
        return assignedResources;
    }

}
