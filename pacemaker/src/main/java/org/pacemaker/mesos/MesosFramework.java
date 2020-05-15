package org.pacemaker.mesos;


import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import com.netflix.fenzo.SchedulingResult;
import org.apache.mesos.MesosSchedulerDriver;
import org.apache.mesos.Protos;
import org.apache.mesos.Scheduler;
import org.apache.mesos.SchedulerDriver;
import org.javatuples.Pair;
import org.pacemaker.framework.Framework;
import org.pacemaker.mesos.fenzo.FenzoAssigner;
import org.pacemaker.proto.models.Error;
import org.pacemaker.proto.models.*;
import org.pacemaker.scheduler.assigner.Assigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.mesos.Protos.TaskState.TASK_RUNNING;

//TODO #Robustness if there has been a while since scheduling we should reconcile the status and check if the task is launched correctly
public class MesosFramework implements Framework,Scheduler {
    private static final Logger logger = LoggerFactory.getLogger(MesosFramework.class);

    private final String id;
    private FrameworkInfo frameworkInfo;
    private FenzoAssigner fenzoAssigner;
    private MesosApiClient mesosApiClient;
    private MesosReconciler reconciler;


    ConcurrentMap<String, TaskStatus> world = new ConcurrentHashMap<>();
    FrameworkStatus frameworkStatus = FrameworkStatus.newBuilder().setState(FrameworkStatus.State.Inactive).build();
    //TODO Load on start
    //TODO clean after deletion
    //TODO this should be on its own model to clarify things
    private Map<String,String> mesosTaskToContainer = new HashMap<>();
    private Map<String,String> containerToTask = new HashMap<>();
    private final AtomicReference<SchedulerDriver> driverRef = new AtomicReference<>();


    private AtomicReference<Boolean> active = new AtomicReference<>();

    @Override
    public Map<String,TaskStatus> world() {
        return world;
    }

    //TODO #setup
    public MesosFramework(FrameworkInfo info) {
        String frameworkId = info.getId();
        assert (frameworkId != null && !frameworkId.isEmpty());
        this.id = frameworkId;
        this.frameworkInfo = info;
        this.reconciler = new MesosReconciler(frameworkId,driverRef);
    }

    public boolean start() {
        return active.updateAndGet(a -> {
            if(a == null || !a){
                Protos.FrameworkInfo.Builder frameworkBuilder = Protos.FrameworkInfo
                        .newBuilder()
                        .setId(Protos.FrameworkID.newBuilder().setValue(id).build())
                        .setName(frameworkInfo.getName())
                        .setUser(frameworkInfo.getUser())
                        .setPrincipal(frameworkInfo.getPrincipal())
                        .addAllRoles(frameworkInfo.getRoleList());

                MesosFrameworkInfo mesosFramework;
                try {
                    mesosFramework = frameworkInfo.getExtension().unpack(MesosFrameworkInfo.class);
                    List<Protos.FrameworkInfo.Capability> capabilities = mesosFramework.getCapabilitiesList().stream()
                            .map(Protos.FrameworkInfo.Capability.Type::valueOf)
                            .map(c -> Protos.FrameworkInfo.Capability.newBuilder().setType(c))
                            .map(Protos.FrameworkInfo.Capability.Builder::build)
                            .collect(Collectors.toList());

                    frameworkBuilder.addAllCapabilities(capabilities);

                    if(mesosFramework.hasFailoverTimeout()){
                        frameworkBuilder.setFailoverTimeout(mesosFramework.getFailoverTimeout().getValue());
                    }


                    //TODO setup and check if this is really working
                    //http://mesos.apache.org/documentation/latest/app-framework-development-guide/
                    frameworkBuilder
                            .putOfferFilters("min_allocatable_resources",
                                    Protos.OfferFilters.newBuilder().setMinAllocatableResources(
                                            Protos.OfferFilters.MinAllocatableResources.newBuilder()
                                                    .addQuantities(
                                                            Protos.OfferFilters.ResourceQuantities.newBuilder().putQuantities("cpus", Protos.Value.Scalar.newBuilder().setValue(0.1d).build()))
                                                    .addQuantities(
                                                            Protos.OfferFilters.ResourceQuantities.newBuilder().putQuantities("mem", Protos.Value.Scalar.newBuilder().setValue(32d).build()))
                                                    .addQuantities(
                                                            Protos.OfferFilters.ResourceQuantities.newBuilder().putQuantities("disks", Protos.Value.Scalar.newBuilder().setValue(32d).build()))
                                    ).build());

                    this.mesosApiClient = new MesosApiClient(mesosFramework.getMaster());
                    this.fenzoAssigner = new FenzoAssigner(frameworkInfo, mesosFramework.getMaster(), x -> driverRef.get().declineOffer(x.getOffer().getId()),driverRef);
                } catch (InvalidProtocolBufferException e) {
                    //TODO #error Handling
                    throw new RuntimeException(e);
                }
                Protos.FrameworkInfo framework = frameworkBuilder.build();
                logger.info("Starting Mesos Framework with frameworkID={}, mesosMaster={}", id, mesosFramework.getMaster());
                logger.trace("FrameworkInfo={}", framework);
                SchedulerDriver driver = new MesosSchedulerDriver(this, framework, mesosFramework.getMaster());
                this.driverRef.set(driver);
                this.driverRef.get().start();
                frameworkStatus = FrameworkStatus.newBuilder()
                        .setStarting(true)
                        .build();
            }
            return true;
        });
    }

    @Override
    public boolean stop() {
        return !active.updateAndGet(a -> {
            if (a != null && a) {
                driverRef.getAndSet(null).abort();
                fenzoAssigner.shutdown();
                frameworkStatus = FrameworkStatus.newBuilder()
                        .setStopping(true)
                        .build();
                reconciler.shutdown();
            }
            return false;
        });
    }

    @Override
    public void shutdown() {
        active.getAndUpdate(a -> {
            driverRef.get().stop(false);
            fenzoAssigner.shutdown();
            frameworkStatus = FrameworkStatus.newBuilder()
                    .setStopping(true)
                    .build();
            reconciler.shutdown();
            return true;
        });
    }

    private void activateFramework(){
        if(reconciler.isReconciled()){
            logger.info("Framework __ACTIVATED__ correctly after reconciling, frameworkID={}", id);
            frameworkStatus = frameworkStatus.toBuilder().setState(FrameworkStatus.State.Active).build();
        }else{
            logger.debug("Framework could __NOT__ be activated after there are one or more mesos tasks on reconciliation, frameworkID={}", id);
        }
    }

    @Override
    public void registered(SchedulerDriver schedulerDriver, Protos.FrameworkID frameworkID, Protos.MasterInfo masterInfo) {
        String frameworkId = frameworkID.getValue();
        logger.info("Framework __REGISTERED__ successfully with mesos master {}, frameworkID={}", masterInfo.getHostname(), id);
        assert Objects.equals(frameworkId, id);
        fenzoAssigner.expireLeases();
        this.driverRef.set(schedulerDriver);

        reconciler.start(mesosTaskToContainer.keySet());
        activateFramework();

    }

    @Override
    public void reregistered(SchedulerDriver schedulerDriver, Protos.MasterInfo masterInfo) {
        logger.info("Mesos Framework had to re-register with mesos master though re-register was successful, frameworkID={}", id);
        fenzoAssigner.expireLeases();
        driverRef.set(schedulerDriver);
        reconciler.start(mesosTaskToContainer.keySet());
        activateFramework();
    }

    @Override
    public void resourceOffers(SchedulerDriver schedulerDriver, List<Protos.Offer> offers) {
        for(Protos.Offer offer: offers) {
            boolean offered = fenzoAssigner.offer(offer);
            if(!offered){
                logger.warn("Offer could __NOT__ be added correctly, not taking in consideration this offer {}", offer.getId().getValue());
            }
        }
    }

    @Override
    public void offerRescinded(SchedulerDriver schedulerDriver, Protos.OfferID offerID) {
        fenzoAssigner.rescindOffer(offerID);
    }

    @Override
    public void statusUpdate(SchedulerDriver schedulerDriver, Protos.TaskStatus taskStatus) {
        logger.trace("TaskStatus={}", taskStatus);
        String mesosTaskID = taskStatus.getTaskId().getValue();
        if(!frameworkStatus.getState().equals(FrameworkStatus.State.Active)){
            activateFramework();
        }

        //TODO #Pod container status
        String containerID = mesosTaskToContainer.getOrDefault(mesosTaskID, "");
        String taskID = containerToTask.getOrDefault(containerID,"'");
        logger.info("Received mesos status update for mesos task, mesosTaskID={}, taskID={}, containerID={}, state={}{}", mesosTaskID, taskID, containerID, taskStatus.getState(), taskStatus.getMessage().isBlank() ? "" : ", message=" + taskStatus.getMessage());

        //TODO only kill if needed
        if(!world.containsKey(taskID)){
            if(taskStatus.getState().equals(TASK_RUNNING)) {
                logger.warn("A running mesos task was not detected by the framework, killing unknown mesos task, please verify that the unknown mesos task does not have any resource reserved otherwise those resources will not be accessible to other tasks, mesosTaskID={}", mesosTaskID);
                driverRef.get().killTask(taskStatus.getTaskId());
            }else{
                logger.warn("Received unknown mesos task status, the task is not running therefore __NO__ action will be perform, mesosTaskID={},  state={}", mesosTaskID,taskStatus.getState());
            }
        }else{
            reconciler.update(taskStatus);
        }

        world.computeIfPresent(taskID, (key,status) -> {
            Instant now = Instant.now();
            Timestamp.Builder timestamp = Timestamp.newBuilder()
                    .setSeconds(now.getEpochSecond())
                    .setNanos(now.getNano());
            TaskStatus.Builder builder = status.toBuilder().setLastProbeTime(timestamp);
            switch (taskStatus.getState()){
                case TASK_RUNNING:
                    return builder
                            .setInitialized(true)
                            .setState(TaskStatus.State.Running)
                            .build();
                case TASK_FAILED:
                    fenzoAssigner.unAssignTask(mesosTaskID);
                    reconciler.remove(mesosTaskID);

                    switch (taskStatus.getReason()){
                        case REASON_CONTAINER_LAUNCH_FAILED:
                        case REASON_CONTAINER_LIMITATION_MEMORY:
                        case REASON_CONTAINER_LIMITATION_DISK:
                        case REASON_IO_SWITCHBOARD_EXITED :
                        case REASON_EXECUTOR_REGISTRATION_TIMEOUT:
                        case REASON_EXECUTOR_REREGISTRATION_TIMEOUT:
                        case REASON_EXECUTOR_TERMINATED:
                        case REASON_COMMAND_EXECUTOR_FAILED:
                            return builder
                                    .setError(Error.newBuilder()
                                            .setReason(taskStatus.getReason().toString())
                                            .setFatal(false).build())
                                    .setState(TaskStatus.State.Failed)
                                    .build();
                        default:
                            throw new IllegalStateException("Unexpected value: " + taskStatus.getReason());
                    }
                case TASK_LOST:
                case TASK_DROPPED:
                case TASK_GONE:
                case TASK_UNREACHABLE:
                    switch (taskStatus.getReason()){
                        //MASTER Status
                        case REASON_SLAVE_DISCONNECTED:
                        case REASON_MASTER_DISCONNECTED:
                        case REASON_SLAVE_REMOVED:
                        case REASON_RESOURCES_UNKNOWN:
                            //AGENT Status
                        case REASON_SLAVE_RESTARTED:
                        case REASON_CONTAINER_PREEMPTED:
                        case REASON_CONTAINER_UPDATE_FAILED:
                        case REASON_EXECUTOR_TERMINATED:
                        case REASON_GC_ERROR:
                        case REASON_INVALID_OFFERS:
                            //TODO verify this state
                        case REASON_RECONCILIATION:
                            return builder
                                    .setError(Error.newBuilder()
                                            .setReason(taskStatus.getReason().toString())
                                            .setFatal(false).build())
                                    .setState(TaskStatus.State.Lost)
                                    .build();
                        default:
                            throw new IllegalStateException("Unexpected value: " + taskStatus.getReason());
                    }
                case TASK_ERROR:
                    fenzoAssigner.unAssignTask(mesosTaskID);
                    reconciler.remove(mesosTaskID);

                    switch (taskStatus.getReason()){
                        //MASTER Status
                        case REASON_TASK_INVALID:
                        case REASON_TASK_GROUP_INVALID:
                            //MASTER & AGENT Status
                        case REASON_TASK_UNAUTHORIZED:
                        case REASON_TASK_GROUP_UNAUTHORIZED:
                            return builder
                                    .setError(Error.newBuilder()
                                            .setReason(taskStatus.getReason().toString())
                                            .setFatal(true).build())
                                    .setState(TaskStatus.State.Failed)
                                    .build();
                        default:
                            throw new IllegalStateException("Unexpected value: " + taskStatus.getReason());
                    }
                case TASK_GONE_BY_OPERATOR:
                case TASK_FINISHED:
                case TASK_KILLED:
                    fenzoAssigner.unAssignTask(mesosTaskID);
                    reconciler.remove(mesosTaskID);
                    return builder
                            .setState(TaskStatus.State.Finished)
                            .build();
                case TASK_KILLING:
                case TASK_UNKNOWN:
                case TASK_STAGING:
                default:
                    return builder.build();
            }
        });
    }

    @Override
    public void frameworkMessage(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, byte[] bytes) {
        String message = bytes != null && bytes.length > 0 ? new String(bytes) : "";
        logger.warn("Received a message from an mesos executor but this is not implemented, executorID={}, slaveID={}, message={},  frameworkID={}", executorID.getValue(), slaveID.getValue(), message,id);
    }

    @Override
    public void disconnected(SchedulerDriver schedulerDriver) {
        logger.info("Mesos Framework has being disconnected, frameworkID={}", id);
        frameworkStatus = FrameworkStatus.newBuilder()
                .setState(FrameworkStatus.State.Inactive)
                .build();
        reconciler.shutdown();
    }

    @Override
    public void slaveLost(SchedulerDriver schedulerDriver, Protos.SlaveID slaveID) {
        logger.warn("Mesos Agent __LOST__, agentID={}  frameworkID={}", slaveID.getValue(), id);
        fenzoAssigner.expireOffersFromAgent(slaveID.getValue());
        //TODO agent sensor
    }

    @Override
    public void executorLost(SchedulerDriver schedulerDriver, Protos.ExecutorID executorID, Protos.SlaveID slaveID, int i) {
        logger.info("Mesos Executor is lost, executorID={}, agentID={}, frameworkID={}", executorID.getValue(), slaveID.getValue(), id);
    }

    @Override
    public void error(SchedulerDriver schedulerDriver, String s) {
        logger.error("Framework has encounter an error, frameworkID={}, message={}",id,s);
        reconciler.shutdown();
        //TODO #ErrorHandling
        frameworkStatus = FrameworkStatus.newBuilder()
                .setState(FrameworkStatus.State.Failed)
                .setStopping(false)
                .setStarting(false)
                .setError(Error.newBuilder()
                        .setFatal(false)
                        .setReason(s)
                        .build())
                .build();
    }

    @Override
    public boolean submit(ScheduleAction action) {
        Set<Protos.OfferID> acceptedOfferIDs = new HashSet<>();

        MesosMapper mesosMapper;
        Task task = action.getTask();
        MesosTask mesosTask;
        MesosAssignment mesosAssignment;
        try {
            mesosTask = task.getExtension().unpack(MesosTask.class);
            mesosAssignment = task.getAssignment().getExtension().unpack(MesosAssignment.class);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException();
        }
        List<Protos.OfferID> acceptedOffersList = mesosAssignment.getAcceptedOffersList().stream().map(x -> Protos.OfferID.newBuilder().setValue(x).build()).collect(Collectors.toList());
        String taskID = task.getId();
        world.put(taskID,task.getStatus());

        mesosMapper = new MesosMapper(id, task.getAssignment().getRole(), task.getAssignment().getPrincipal(), task, mesosTask, mesosAssignment);
        Map<String, String> mesosTaskIDsToContainerID = mesosAssignment.getTaskIDsMap();
        List<Protos.Offer.Operation> operations = action.getActionsList().stream().flatMap(a -> {
            switch (a) {
                        case None:
                            return Stream.empty();
                        case Launch:
                            acceptedOfferIDs.addAll(acceptedOffersList);
                            mesosTaskToContainer.putAll(mesosTaskIDsToContainerID);
                            mesosTaskIDsToContainerID.values().forEach(c -> containerToTask.put(c,taskID));
                            Protos.Offer.Operation build = mesosMapper.launchTaskOperation().build();
                            mesosTaskIDsToContainerID.keySet().forEach(reconciler::launching);
                            return Stream.of(build);
                        case Kill:

                            Set<String> mesosTaskIDs = mesosAssignment.getTaskIDsMap().keySet();
                            mesosTaskIDs.stream()
                                    .peek(x -> logger.debug("Sending kill action for mesos task, mesosTaskID={}", x))
                                    .map(Protos.TaskID.newBuilder()::setValue)
                                    .map(Protos.TaskID.Builder::build).forEach(driverRef.get()::killTask);
                            logger.info("Mesos has __ACCEPTED__ kill task operation, taskID={}", taskID);
                            mesosTaskIDsToContainerID.keySet().forEach(reconciler::killing);
                            return Stream.empty();
                        case CreateVolume:
                            acceptedOfferIDs.addAll(acceptedOffersList);
                            return Stream.of(
                                    mesosMapper.reserveResourcesOperation(),
                                    mesosMapper.createVolumesOperation()
                            ).map(Protos.Offer.Operation.Builder::build);
                        case DestroyVolume:
                            Pair<Collection<Protos.Offer>, SchedulingResult> validatedOffers = fenzoAssigner.validateOffers(task, mesosTask);
                            acceptedOfferIDs.addAll(validatedOffers.getValue0().stream().map(Protos.Offer::getId).collect(Collectors.toList()));

                            //TODO verify that destroy/unreserve has been completed
                            world.put(taskID,task.getStatus().toBuilder()
                                    .setState(TaskStatus.State.Finished)
                                    .setUnassigned(true)
                                    .build());
                            return Stream.of(
                                    mesosMapper.destroyVolumesOperation(),
                                    mesosMapper.unReserveResourcesOperation()
                            ).map(Protos.Offer.Operation.Builder::build);
                        case UNRECOGNIZED:
                        default:
                            throw new IllegalStateException("Unexpected value: " + a);
                    }
                }
        )
        .collect(Collectors.toList());

        //TODO #errorHandling when there are no offers
        if (!operations.isEmpty()) {
            logger.debug("Mesos operations to be sent [{}]", operations.stream().map(Protos.Offer.Operation::getType).map(Enum::name).collect(Collectors.joining(", ")));
            logger.trace("Accepted offers \n{}", acceptedOfferIDs);
            logger.trace("Operations to be launched \n{}", operations);
            driverRef.get().acceptOffers(acceptedOfferIDs, operations, Protos.Filters.newBuilder().build());
        }
        return true;
    }

    @Override
    public FrameworkStatus status() {
        return frameworkStatus;
    }


    @Override
    public Assigner assigner() {
        return fenzoAssigner;
    }

    /*@Override
    public CompletableFuture<Collection<String>> agents() {
        return mesosApiClient.getAgents()
                .thenApply(json -> json.getAsJsonArray("slaves"))
                .thenApply(jsonArray -> StreamSupport.stream(jsonArray.spliterator(), true))
                .thenApply(xs -> xs.map(JsonElement::getAsJsonObject))
                .thenApply(xs -> xs.map(x -> x.get("id")))
                .thenApply(xs -> xs.map(JsonElement::getAsString))
                .thenApply(xs -> xs.collect(Collectors.toList()));
    }*/
}
