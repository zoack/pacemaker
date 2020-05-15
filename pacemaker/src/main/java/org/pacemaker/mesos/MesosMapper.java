package org.pacemaker.mesos;

import org.pacemaker.proto.models.*;
import org.apache.mesos.Protos;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MesosMapper {

    public static final String DEPLOYMENT_ID_LABEL = "deploymendId";
    public static final String TASK_ID_LABEL = "taskId";

    private final String frameworkId;
    private final String role;
    private final String principal;
    private final Task task;
    private final MesosTask mesosTask;
    private final MesosAssignment mesosAssignment;

    public MesosMapper(String frameworkId, String role, String principal, Task task, MesosTask mesosTask, MesosAssignment assignment){
        this.frameworkId = frameworkId;
        this.role = role;
        this.principal = principal;
        this.task = task;
        this.mesosTask = mesosTask;
        this.mesosAssignment = assignment;
    }

    Predicate<Task> needsReservation = t -> t.getWorkload().equals(Task.Workload.STATEFUL) || t.getWorkload().equals(Task.Workload.ANALYTICAL) || t.getWorkload().equals(Task.Workload.BATCH);


    public Protos.Offer.Operation.Builder createVolumesOperation(){


        List<Protos.Resource> disksToBeCreated = Stream.concat(
                task.getContainersList().stream().map(Container::getResources).map(Resources::getDisksList)
                        .flatMap(Collection::stream).filter(Disk::getPersistent).filter(Disk::hasVolume),
                mesosTask.getExecutorResources().getDisksList().stream().filter(Disk::getPersistent).filter(Disk::hasVolume)
        )
                .map(d -> this.toDiskResourceWithPersistence(needsReservation.test(task),d))
                .map(Protos.Resource.Builder::build)
                .collect(Collectors.toList());

        return Protos.Offer.Operation.newBuilder()
                .setType(Protos.Offer.Operation.Type.CREATE)
                .setCreate(Protos.Offer.Operation.Create.newBuilder()
                        .addAllVolumes(disksToBeCreated).build());

    }


    public Protos.Offer.Operation.Builder destroyVolumesOperation(){


        List<Protos.Resource> disksToBeDestroyed = Stream.concat(
                task.getContainersList().stream().map(Container::getResources).map(Resources::getDisksList)
                        .flatMap(Collection::stream).filter(Disk::getPersistent).filter(Disk::hasVolume),
                mesosTask.getExecutorResources().getDisksList().stream().filter(Disk::getPersistent).filter(Disk::hasVolume)
        )
                .map(d -> this.toDiskResourceWithPersistence(needsReservation.test(task),d))
                .map(Protos.Resource.Builder::build)
                .collect(Collectors.toList());

        return Protos.Offer.Operation.newBuilder()
                .setType(Protos.Offer.Operation.Type.DESTROY)
                .setDestroy(Protos.Offer.Operation.Destroy.newBuilder()
                        .addAllVolumes(disksToBeDestroyed).build());

    }


    public Protos.Offer.Operation.Builder reserveResourcesOperation(){
        Resources resources = task.getAssignment().getResources();
        double cpus = resources.getCpus() +
                (!mesosTask.getDocker() && !mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getCpus());
        Protos.Resource.Builder cpuResource = toCpuResource(cpus).addReservations(toReservation());
        double mem = resources.getMemory() +
                (!mesosTask.getDocker() && !mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getMemory());
        Protos.Resource.Builder memResource = toMemoryResource(mem).addReservations(toReservation());

        double diskSizeToBeReserved =
                resources.getDisksList().stream().mapToDouble(Disk::getSize).sum() +
                (!mesosTask.getDocker() && !mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getDisksList().stream().mapToDouble(Disk::getSize).sum());
        Protos.Resource.Builder diskResource = toDiskResource(diskSizeToBeReserved).addReservations(toReservation());
        return Protos.Offer.Operation.newBuilder()
                .setType(Protos.Offer.Operation.Type.RESERVE)
                .setReserve(
                        Protos.Offer.Operation.Reserve.newBuilder()
                                .addResources(cpuResource)
                                .addResources(memResource)
                                .addResources(diskResource)
                                .build());

    }


    public Protos.Offer.Operation.Builder unReserveResourcesOperation(){
        Resources resources = task.getAssignment().getResources();
        double cpus = resources.getCpus() +
                (!mesosTask.getDocker() && !mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getCpus());
        Protos.Resource.Builder cpuResource = toCpuResource(cpus).addReservations(toReservation());
        double mem = resources.getMemory() +
                (!mesosTask.getDocker() && !mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getMemory());
        Protos.Resource.Builder memResource = toMemoryResource(mem).addReservations(toReservation());

        double diskSizeToBeReserved =
                resources.getDisksList().stream().mapToDouble(Disk::getSize).sum() +
                        (!mesosTask.getDocker() && !mesosTask.hasExecutorResources() ? 0.0f : mesosTask.getExecutorResources().getDisksList().stream().mapToDouble(Disk::getSize).sum());
        Protos.Resource.Builder diskResource = toDiskResource(diskSizeToBeReserved).addReservations(toReservation());
        return Protos.Offer.Operation.newBuilder().
                setType(Protos.Offer.Operation.Type.UNRESERVE)
                .setUnreserve(
                        Protos.Offer.Operation.Unreserve.newBuilder()
                                .addResources(cpuResource)
                                .addResources(memResource)
                                .addResources(diskResource)
                                .build());

    }



    public Protos.Offer.Operation.Builder launchTaskOperation(){
        List<Protos.TaskInfo> tasks = task.getContainersList()
                .stream()
                .map(this::toTaskInfo).map(Protos.TaskInfo.Builder::build).collect(Collectors.toList());

        if(mesosTask.getDocker()){
            return Protos.Offer.Operation.newBuilder()
                    .setType(Protos.Offer.Operation.Type.LAUNCH)
                    .setLaunch(Protos.Offer.Operation.Launch.newBuilder()
                            .addAllTaskInfos(tasks));
        }else {

            return Protos.Offer.Operation.newBuilder()
                    .setType(Protos.Offer.Operation.Type.LAUNCH_GROUP)
                    .setLaunchGroup(Protos.Offer.Operation.LaunchGroup.newBuilder()
                            .setTaskGroup(Protos.TaskGroupInfo.newBuilder().addAllTasks(tasks))
                            .setExecutor(toExecutor()));
        }
    }


    private Protos.Resource.Builder toCpuResource(double cpus){
        return Protos.Resource.newBuilder()
                .setName("cpus")
                .setScalar(Protos.Value.Scalar.newBuilder().setValue(cpus).build())
                .setType(Protos.Value.Type.SCALAR);
    }


    private Protos.Resource.Builder toMemoryResource(double value){
        return Protos.Resource.newBuilder()
                .setName("mem")
                .setScalar(Protos.Value.Scalar.newBuilder().setValue(value).build())
                .setType(Protos.Value.Type.SCALAR);
    }


    private Protos.Resource.Builder toDiskResource(double value){
        return Protos.Resource.newBuilder()
                .setName("disk")
                .setScalar(Protos.Value.Scalar.newBuilder().setValue(value).build())
                .setType(Protos.Value.Type.SCALAR);
    }


    private Protos.Resource.Builder toDiskResourceWithPersistence(boolean needsReservation, Disk disk){
        Protos.Resource.Builder diskResource = toDiskResource(disk.getSize());
        if(needsReservation && disk.getPersistent()) {
            return diskResource
                    .setRole(role)
                    .setReservation(Protos.Resource.ReservationInfo.newBuilder()
                            .setPrincipal(principal))
                    .setDisk(toDiskInfo(disk));
        }
        return diskResource;
    }


    private Protos.Resource.DiskInfo.Builder toDiskInfo(Disk disk){
        return Protos.Resource.DiskInfo.newBuilder()
                .setVolume(toVolume(disk.getVolume()))
                .setPersistence(Protos.Resource.DiskInfo.Persistence.newBuilder()
                        .setPrincipal(principal)
                        .setId(disk.getId())
                        .build());
    }

    private Protos.Resource.DiskInfo.Source.Builder toDiskSource(Disk disk) {
        switch (disk.getType()) {
            case Path:
                return Protos.Resource.DiskInfo.Source.newBuilder()
                                .setType(Protos.Resource.DiskInfo.Source.Type.PATH)
                                .setPath(Protos.Resource.DiskInfo.Source.Path.newBuilder()
                                        .setRoot(disk.getVolume().getDestinationPath()));
            case Mount:
                return Protos.Resource.DiskInfo.Source.newBuilder()
                        .setType(Protos.Resource.DiskInfo.Source.Type.MOUNT)
                        .setMount(Protos.Resource.DiskInfo.Source.Mount.newBuilder()

                                .setRoot(disk.getVolume().getDestinationPath()))
                                ;
            case Root:
            case UNRECOGNIZED:
            default:
                return null;
        }
    }



    private Protos.Resource.ReservationInfo.Builder toReservation(){
        return Protos.Resource.ReservationInfo.newBuilder()
                .setType(Protos.Resource.ReservationInfo.Type.DYNAMIC)
                .setRole(role)
                .setPrincipal(principal);
    }


    private Protos.ExecutorInfo.Builder toExecutor() {
        Resources executorResources = mesosTask.getExecutorResources();
        Protos.Resource.Builder executorCpus = toCpuResource(executorResources.getCpus());
        Protos.Resource.Builder executorMemory = toMemoryResource(executorResources.getMemory());
        boolean needReservation = needsReservation.test(task);
        List<Protos.Resource.Builder> executorDiskBuilder = executorResources.getDisksList().stream()
                .map(d -> this.toDiskResourceWithPersistence(needReservation,d))
                .collect(Collectors.toList());
        if(needReservation){
            executorCpus.addReservations(toReservation());
            executorMemory.addReservations(toReservation());
            executorDiskBuilder = executorDiskBuilder.stream().map(b -> b.addReservations(toReservation())).collect(Collectors.toList());
        }

        return Protos.ExecutorInfo.newBuilder()
                .setExecutorId(Protos.ExecutorID.newBuilder().setValue(UUID.randomUUID().toString()).build())
                .addResources(executorCpus)
                .addResources(executorMemory)
                .addAllResources(executorDiskBuilder.stream().map(Protos.Resource.Builder::build).collect(Collectors.toList()))
                .setFrameworkId(Protos.FrameworkID.newBuilder().setValue(frameworkId).build())
                .setType(Protos.ExecutorInfo.Type.DEFAULT);
    }



    private Protos.TaskInfo.Builder toTaskInfo(Container container){
        Resources resources = container.getResources();
        Protos.Resource.Builder taskCpus = toCpuResource(resources.getCpus());
        Protos.Resource.Builder taskMemory = toMemoryResource(resources.getMemory());


        boolean needReservation = needsReservation.test(task);
        List<Protos.Resource.Builder> taskDisksBuilder = resources.getDisksList().stream()
                .map(d -> this.toDiskResourceWithPersistence(needReservation,d))
                .collect(Collectors.toList());

        if(needReservation){
            taskCpus.addReservations(toReservation());
            taskMemory.addReservations(toReservation());
            taskDisksBuilder = taskDisksBuilder.stream()
                    .map(b -> b.addReservations(toReservation()))
                    .collect(Collectors.toList());
        }

        String mesosTaskID = mesosAssignment.getTaskIDsMap().entrySet().stream().filter(k -> k.getValue().equals(container.getId())).map(Map.Entry::getKey).findFirst().orElseThrow(RuntimeException::new);
        Protos.TaskInfo.Builder taskInfoBuilder =
                Protos.TaskInfo.newBuilder()
                        .setTaskId(Protos.TaskID.newBuilder().setValue(mesosTaskID).build())
                        .setSlaveId(Protos.SlaveID.newBuilder().setValue(task.getAssignment().getAgentId()).build())
                        .addResources(taskCpus)
                        .addResources(taskMemory)
                        .addAllResources(taskDisksBuilder.stream().map(Protos.Resource.Builder::build).collect(Collectors.toList()))
                        .setCommand(Protos.CommandInfo.newBuilder()
                                .setEnvironment(Protos.Environment.newBuilder()
                                        .addAllVariables(
                                                container.getVariablesList()
                                                        .stream()
                                                        .map(v -> Protos.Environment.Variable.newBuilder()
                                                                .setName(v.getKey())
                                                                .setValue(v.getValue()).build()).collect(Collectors.toList())
                                        )
                                        .build())
                                .setShell(false)
                                .build());

        List<Protos.NetworkInfo> networksInfo = task.getNetworksList().stream().map(n -> Protos.NetworkInfo.newBuilder().setName(n).build()).collect(Collectors.toList());


        List<Protos.Volume> volumes = container.getVolumesList()
                .stream()
                .map(this::toVolume)
                .map(Protos.Volume.Builder::build)
                .collect(Collectors.toList());

        Protos.TaskInfo.Builder mesosTaskInfo = taskInfoBuilder
                .setName(container.getInternalName())
                .setLabels(Protos.Labels.newBuilder()
                        .addLabels(Protos.Label.newBuilder().setKey(TASK_ID_LABEL).setValue(task.getId()).build())
                        .build());
        Protos.ContainerInfo containerInfo;
        if(!mesosTask.getDocker()) {
            containerInfo = Protos.ContainerInfo.newBuilder()
                    .setType(Protos.ContainerInfo.Type.MESOS)
                    .addAllNetworkInfos(networksInfo)
                    .addAllVolumes(volumes)
                    .setMesos(Protos.ContainerInfo.MesosInfo.newBuilder()
                            .setImage(Protos.Image.newBuilder()
                                    .setType(Protos.Image.Type.DOCKER)
                                    .setDocker(Protos.Image.Docker.newBuilder()
                                            .setName(container.getImage())
                                            .build())
                                    .build())
                            .build())
                    .build();
            mesosTaskInfo.setContainer(containerInfo);
        }else{
            containerInfo = Protos.ContainerInfo.newBuilder()
                    .setType(Protos.ContainerInfo.Type.DOCKER)
                    .addAllNetworkInfos(networksInfo)
                    .setDocker(Protos.ContainerInfo.DockerInfo.newBuilder()
                            .setImage(container.getImage())
                            //TODO #networkModel
                            .setNetwork(Protos.ContainerInfo.DockerInfo.Network.USER)
                            .build())
                    .build();

        }
        mesosTaskInfo.setContainer(containerInfo);
        return mesosTaskInfo;
    }



    private Protos.Volume.Builder toVolume(Volume volume){
        Protos.Volume.Builder builder = Protos.Volume.newBuilder()
                .setMode((toVolumeMode(volume.getMode())))
                .setContainerPath(volume.getDestinationPath());

        if(!volume.getSourceType().equals(Volume.SourceType.None)) {
            switch (volume.getSourceType()) {
                case Host:
                    builder.setHostPath(volume.getDestinationPath());
                    break;
                case Sandbox:
                    Protos.Volume.Source.SandboxPath.Builder sandboxPathBuilder = Protos.Volume.Source.SandboxPath.newBuilder()
                            .setPath(volume.getDestinationPath());
                    if (volume.getShared()) {
                        builder.setContainerPath(volume.getSourcePath())
                                .setSource(Protos.Volume.Source.newBuilder()
                                        .setType(Protos.Volume.Source.Type.SANDBOX_PATH)
                                        .setSandboxPath(sandboxPathBuilder.build())
                                        .build());
                        sandboxPathBuilder.setType(Protos.Volume.Source.SandboxPath.Type.PARENT);
                    } else {
                        sandboxPathBuilder.setType(Protos.Volume.Source.SandboxPath.Type.SELF);
                    }
                    break;
                case Docker:
                    //TODO #ErrorHandlong
                    throw new RuntimeException("Mapping __NOT__ implemented for Docker Volume.SourceType");
                case Secret:
                    //TODO #ErrorHandlong
                    throw new RuntimeException("Mapping __NOT__ implemented for Secret Volume.SourceType");
                case UNRECOGNIZED:
                default:
                    //TODO #ErrorHandlong
                    throw new RuntimeException("Mapping __NOT__ implemented Volume.SourceType for " + volume.getSourceType().toString());
            }
        }
        return builder;
    }


    private Protos.Volume.Mode toVolumeMode(Volume.Mode mode){
        switch (mode){
            case READ_ONLY:
                return Protos.Volume.Mode.RO;
            case READ_WRITE:
                return Protos.Volume.Mode.RW;
            case UNRECOGNIZED:
            default:
                throw new RuntimeException("Mapping __NOT__ implemented Volume.Mode for " + mode.toString());
        }
    }


}
