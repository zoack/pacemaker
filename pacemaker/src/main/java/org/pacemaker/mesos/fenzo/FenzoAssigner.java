package org.pacemaker.mesos.fenzo;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.netflix.fenzo.*;
import com.netflix.fenzo.functions.Action1;
import org.pacemaker.mesos.MesosApiClient;
import org.pacemaker.proto.models.*;
import org.pacemaker.scheduler.assigner.Assigner;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.javatuples.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class FenzoAssigner  implements Assigner {

    private static final Logger logger = LoggerFactory.getLogger(FenzoAssigner.class);

    private final String frameworkID;
    private final String frameworkName;
    private final String frameworkDomain;
    private final String principal;
    private final MesosApiClient apiClient;
    private final TaskScheduler taskScheduler;
    private final BlockingQueue<Protos.Offer> offersQueue = new LinkedBlockingQueue<>();
    private final ConcurrentMap<String, Pair<String,String>> assignedContainers = new ConcurrentHashMap<>();

    //TODO protect accesses from driver
    private final AtomicReference<SchedulerDriver> driverRef;
    private volatile boolean requestOffers;

    //TODO #setup
    private long suppressionThresholdInMs = 30000;
    private long offerRefuseSeconds = 3000;
    private long timeToWaitForOffersMs = 5000;
    private Instant lastAction = Instant.now();

    //TODO #Role&Principal we should match framework's role & principal with deployment's role & principal
    //TODO #Robustness we need some process to reject offers if they are not offered
    //TODO #Bug if a stateless try to launch it will try to take reserved resources when it shouldn't because stateless does not have reservation
    public FenzoAssigner(FrameworkInfo frameworkInfo, String master, Action1<VirtualMachineLease> leaseFailureCallback, AtomicReference<SchedulerDriver> driver) {
        this.frameworkID = frameworkInfo.getId();
        this.frameworkName = frameworkInfo.getName();
        this.frameworkDomain = frameworkInfo.getDomain();
        this.principal = frameworkInfo.getPrincipal();
        this.apiClient = new MesosApiClient(master);
        this.driverRef = driver;
        taskScheduler = new TaskScheduler.Builder()
                .withLeaseOfferExpirySecs(2000)
                .withLeaseRejectAction(leaseFailureCallback)
                .build();
    }

    
    public boolean offer(Protos.Offer offer){
        if(requestOffers){
            logger.trace("Assigner __ACCEPT__ offer, offerID={}", offer.getId().getValue());
            return offersQueue.offer(offer);
        } else {
            logger.trace("Assigner is not accepting offers, __DECLINING__ offer, offerID={}", offer.getId().getValue());
            driverRef.get().declineOffer(offer.getId());
            return true;
        }
    }

    public void rescindOffer(Protos.OfferID id){
        logger.debug("Offer rescinded, removing offer from offers queue, offerID={}", id.getValue());
        offersQueue.stream().filter(o -> o.getId().equals(id)).findFirst().ifPresent(offersQueue::remove);
    }

    public void expireLeases(){
        taskScheduler.expireAllLeases();
        List<Protos.Offer> offers = new ArrayList<>();
        offersQueue.drainTo(offers);
        rejectOffers(offers, Collections.emptyList());
    }

    public void expireOffersFromAgent(String agentID){
        logger.debug("Expiring all leases for agent, agentID={}", agentID);
        taskScheduler.expireAllLeasesByVMId(agentID);
    }

    public void unAssignTask(String mesosTaskID){
        if(mesosTaskID != null) {
            if (assignedContainers.containsKey(mesosTaskID)) {

                Pair<String,String> assignedTask = assignedContainers.get(mesosTaskID);
                String hostname = assignedTask.getValue1();
                String fenzoTaskID = assignedTask.getValue0();
                logger.debug("__REMOVING__ container from fenzo scheduler state, fenzoTaskID={}, hostname={}", fenzoTaskID, hostname);
                taskScheduler.getTaskUnAssigner().call(fenzoTaskID, hostname);
                assignedContainers.remove(mesosTaskID);
            } else {
                logger.warn("__UNKNOWN__ task to be unassigned from fenzo schedulers, mesosTaskID={}", mesosTaskID);
            }
        }else{
            logger.warn("Task id is __EMPTY__, please check how task ids are sent to be unassigned");
        }
    }

    public void shutdown(){
        taskScheduler.shutdown();
    }

    private void rejectOffers(Collection<Protos.Offer> offers, List<String> offersUsed){
        offers.stream()
                .map(Protos.Offer::getId)
                .filter(x -> !offersUsed.contains(x.getValue()))
                .peek(x -> logger.trace("__DECLINING__  non used mesos offer, offerID={}",x))
                .forEach(offerID -> driverRef.get().declineOffer(offerID, Protos.Filters.newBuilder().setRefuseSeconds(offerRefuseSeconds).build()));
    }

    public Pair<Collection<Protos.Offer>, SchedulingResult> validateOffers(Task task, MesosTask mesosTask){
        requestOffers = true;
        try {
            Thread.sleep(timeToWaitForOffersMs);
        } catch (InterruptedException e) {
            //TODO #errorHandling
            Thread.currentThread().interrupt();
        }
        requestOffers = false;

        List<Protos.Offer> offers = new ArrayList<>();
        this.offersQueue.drainTo(offers);
        Collections.shuffle(offers)
        ;
        List<VirtualMachineLease> leases = offers.stream()
                .map(o -> new VMLease(Collections.singletonList(task.getRole()), principal, o))
                .peek(o -> logger.debug("Offer __ADDED__ as candidate to match task requirements, offerID={} on host={}", o.getId(), o.hostname()))
                .peek(o -> logger.trace("Offer added={}", o))
                .collect(Collectors.toList());
        MesosTaskRequest mesosTaskRequest = new MesosTaskRequest(task, mesosTask, UUID.randomUUID().toString());
        return new Pair<>(offers,taskScheduler.scheduleOnce(Collections.singletonList(mesosTaskRequest), leases));
    }

    @Override
    public Optional<Assignment> assign(Task task) {

        MesosTask mesosTask;
        try {
            mesosTask = task.getExtension().unpack(MesosTask.class);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        logger.debug("Task to be assigned on mesos,  frameworkID={}, taskID={}", frameworkID, task.getId());

        Pair<Collection<Protos.Offer>, SchedulingResult> offerValidation = validateOffers(task, mesosTask);
        Collection<Protos.Offer> offers = offerValidation.getValue0();
        SchedulingResult schedulingResult = offerValidation.getValue1();
        List<Exception> exceptions = schedulingResult.getExceptions();
        Optional<Assignment> result = Optional.empty();
        if(exceptions.isEmpty()){
            //TODO check constraints
            schedulingResult.getFailures().forEach((key, value) -> value
                    .forEach(assignmentResult -> {
                        ConstraintFailure constraintFailure = assignmentResult.getConstraintFailure();
                        if(constraintFailure != null){
                            logger.info("Offer did __NOT__ match constraints, for taskId={}, constraint={}, reason={}",
                                    task.getId(), constraintFailure.getName(), constraintFailure.getReason());
                        }
                        assignmentResult
                                .getFailures()
                                .forEach(failure ->
                                        logger.info("Offer did __NOT__ match asked resources, for taskId={}, asked for {} {}, available {}",
                                                task.getId(), failure.getAsking(), failure.getResource().name(), failure.getAvailable())
                                );
                    })
            );

            Map<String, VMAssignmentResult> resultMap = schedulingResult.getResultMap();
            if(!resultMap.isEmpty()) {
                result = resultMap.values().stream().findFirst().flatMap(r -> {
                    Protos.SlaveID slaveId = r.getLeasesUsed().get(0).getOffer().getSlaveId();
                    List<Protos.Offer> hostAcceptedOffers = r.getLeasesUsed().stream()
                            .map(VirtualMachineLease::getOffer).collect(Collectors.toList());
                    return r.getTasksAssigned().stream().findFirst().map(taskAssignmentResult ->  {
                        //TODO #ErrorHandling
                        logger.info("__ACCEPTED__ task to be scheduled on mesos, offers did match requirements, taskID={}, frameworkID={}", task.getId(), frameworkID);
                        TaskRequest request = taskAssignmentResult.getRequest();
                        List<String> usedOffers = hostAcceptedOffers.stream().map(Protos.Offer::getId).map(Protos.OfferID::getValue).collect(Collectors.toList());
                        String containerID = UUID.randomUUID().toString();
                        Map<String, String> mesosIDContainerID = task.getContainersList().stream().map(Container::getId).collect(Collectors.toMap(id -> containerID, Function.identity()));
                        Map<String, Pair<String,String>> taskHostname = task.getContainersList().stream().map(Container::getId).collect(Collectors.toMap(id -> containerID, t -> new Pair<>(request.getId(),r.getHostname())));

                        logger.debug("__ASSIGNING__ task to fenzo scheduler on a host, fenzoTaskID={}, hostname={}", request.getId(), r.getHostname());
                        taskScheduler.getTaskAssigner().call(request, r.getHostname());
                        assignedContainers.putAll(taskHostname);
                        rejectOffers(offers, usedOffers);


                        //TODO maybe we should just return a new assignment
                        return Assignment.newBuilder()
                                .setFrameworkId(frameworkID)
                                .setAgentId(slaveId.getValue())
                                .setRole(task.getRole())
                                .setPrincipal(principal)
                                .setHost(taskAssignmentResult.getHostname())
                                .setExtension(Any.pack(MesosAssignment.newBuilder()
                                        .clearTaskIDs()
                                        .putAllTaskIDs(mesosIDContainerID)
                                        .clearAcceptedOffers()
                                        .addAllAcceptedOffers(usedOffers).build()))
                                //TODO this does not know about container
                                .addDnsDomain(frameworkName + "." + frameworkDomain)
                                .setResources(
                                        Resources.newBuilder()
                                                .setCpus((float) request.getCPUs())
                                                .setMemory((float) request.getMemory())
                                                .addAllDisks(
                                                        Stream.concat(
                                                                task.getContainersList().stream().map(Container::getResources).map(Resources::getDisksList).flatMap(Collection::stream),
                                                                mesosTask.getExecutorResources().getDisksList().stream()
                                                        ).collect(Collectors.toList())
                                                )
                                                .addAllPorts(taskAssignmentResult.getAssignedPorts().stream().map(p -> Port.newBuilder().setPort(p).build()).collect(Collectors.toList()))
                                ).build();
                    });
                });
            }else{
                logger.info("Could __NOT__ schedule task, no offers are available or did not match asked resources on frameworkID={}, taskID={}", frameworkID, task.getId());
            }
            return result;
        }else{
            exceptions.forEach(x -> logger.error("_Could __NOT__ schedule tasks on frameworkID=" + frameworkID + ", taskID=" + task.getId(),x));
            return result;

        }
/*
        //TODO this belongs somewhere else
        if(Instant.now().isAfter(lastAction.plusMillis(suppressionThresholdInMs))){
            logger.debug("__SUPRESSING__ offers for framework, frameworkID={}", frameworkID);
            driverRef.get().suppressOffers();
            expireLeases();
            isSuppressed = true;
        }
        lastAction = Instant.now();
        if(isSuppressed) {
            logger.debug("__REVIVING__ offers for framework, frameworkID={}", frameworkID);
            driverRef.get().reviveOffers();
        }*/
    }
}
