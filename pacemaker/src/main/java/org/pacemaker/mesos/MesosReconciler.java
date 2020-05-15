package org.pacemaker.mesos;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.apache.mesos.Protos;
import org.apache.mesos.SchedulerDriver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

//http://mesos.apache.org/documentation/latest/reconciliation/
public class MesosReconciler {

    private static final Logger logger = LoggerFactory.getLogger(MesosReconciler.class);

    private ScheduledExecutorService reconciliationScheduler = Executors.newSingleThreadScheduledExecutor();
    private ConcurrentLinkedQueue<Protos.TaskStatus> reconcilingMesosTasks = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Protos.TaskStatus> toBeReconciled = new ConcurrentLinkedQueue<>();
    private final String frameworkID;
    private final AtomicReference<SchedulerDriver> driverRef;
    private Retry retry;

    public MesosReconciler(String frameworkID, AtomicReference<SchedulerDriver> driverRef) {
        this.frameworkID = frameworkID;
        this.driverRef = driverRef;
        //TODO #setup
        IntervalFunction exponentialBackoff = IntervalFunction.ofExponentialBackoff(50000, 2);
        RetryRegistry retryRegistry = RetryRegistry.of(RetryConfig.<Boolean>custom()
                .maxAttempts(5)
                .waitDuration(Duration.ofMillis(3000))
                .retryOnResult(needsReconciliation -> needsReconciliation)
                .intervalFunction(exponentialBackoff)
                .build());
        retry = retryRegistry.retry("mesos-task-reconciliation");
    }

    private void explicitReconciliation(Collection<Protos.TaskStatus> mesosTaskToBeReconciled){
        if(!mesosTaskToBeReconciled.isEmpty()) {
            if(logger.isDebugEnabled()) {
                logger.debug("Asking for mesos explicit reconciliation for frameworkID={}, mesos tasks to be reconciled=[{}]", frameworkID,
                        String.join(", ", mesosTaskToBeReconciled.stream().map(Protos.TaskStatus::getTaskId).map(Protos.TaskID::getValue).collect(Collectors.joining(", "))));
            }
            this.driverRef.get().reconcileTasks(mesosTaskToBeReconciled);
        }
    }

    private void implicitReconciliation(){
        logger.debug("Asking for mesos implicit reconciliation for frameworkID={}", frameworkID);
        this.driverRef.get().reconcileTasks(Collections.emptyList());
    }

    public boolean isReconciled(){
        return reconcilingMesosTasks.isEmpty();
    }

    public void start(Collection<String> mesosTaskToBeReconciled){
        if(reconciliationScheduler != null && !reconciliationScheduler.isShutdown()){
            reconciliationScheduler.shutdown();
        }
        reconciliationScheduler = Executors.newSingleThreadScheduledExecutor();
        reconcilingMesosTasks = new ConcurrentLinkedQueue<>();
        toBeReconciled = new ConcurrentLinkedQueue<>();
        mesosTaskToBeReconciled.forEach(this::launching);
        implicitReconciliation();


        //TODO #Setup
        int delay = 5;
        int periodExplicit = 15;
        int periodImplicit = 35;

        logger.debug("Scheduling explicit reconciliation with a delay of {}m and a period of {}m", delay,periodExplicit);
        reconciliationScheduler.scheduleAtFixedRate(() -> explicitReconciliation(toBeReconciled), delay, periodExplicit, TimeUnit.MINUTES);
        logger.debug("Scheduling implicit reconciliation with a delay of {}m and a period of {}m", delay,periodImplicit);
        reconciliationScheduler.scheduleAtFixedRate(this::implicitReconciliation, delay, periodImplicit, TimeUnit.MINUTES);
    }

    public void shutdown(){
        reconciliationScheduler.shutdown();
        reconcilingMesosTasks.clear();
        toBeReconciled.clear();
    }

    public void update(Protos.TaskStatus mesosTaskStatus) {
        Protos.TaskID taskId = mesosTaskStatus.getTaskId();
        logger.trace("Removing if present mesos task id from reconciling list, mesosTaskID={}", taskId.getValue());
        reconcilingMesosTasks.removeIf(x -> !x.getState().equals(mesosTaskStatus.getState()) && x.getTaskId().equals(taskId));
        toBeReconciled.removeIf(x -> !x.getState().equals(mesosTaskStatus.getState()) && x.getTaskId().equals(taskId));
        toBeReconciled.add(mesosTaskStatus);
    }

    private Supplier<Boolean> reconcileIfNeeded(String mesosTaskID, Protos.TaskStatus taskStatus){
        return () -> {
            boolean needsReconciliation = reconcilingMesosTasks.stream().anyMatch(x -> x.getTaskId().getValue().equals(mesosTaskID));
            if (needsReconciliation) {
                logger.debug("Mesos task __NEEDS__ reconciliation, we have not acknowledge the status of the task, mesosTaskID={}", mesosTaskID);
                explicitReconciliation(Collections.singleton(taskStatus));
            } else {
                logger.trace("__NO__ need to send reconciliation for mesos task because its status was acknowledge, mesosTaskID={}", mesosTaskID);
            }
            return needsReconciliation;
        };
    }

    public void launching(String mesosTaskID){
        logger.debug("__ADDING__ mesos task to be reconciled, mesosTaskID={}", mesosTaskID);
        Protos.TaskStatus taskStatus = Protos.TaskStatus.newBuilder()
                .setTaskId(
                        Protos.TaskID.newBuilder().setValue(mesosTaskID))
                //TODO #Optimization
                /*.setSlaveId(
                        Protos.SlaveID.newBuilder().setValue(agentID).build())*/
                .setState(Protos.TaskState.TASK_STAGING)
                .build();
        toBeReconciled.add(taskStatus);
        reconcilingMesosTasks.add(taskStatus);
        reconciliationScheduler.schedule(Retry.decorateCallable(retry,() -> reconcileIfNeeded(mesosTaskID,taskStatus)) , 30, TimeUnit.SECONDS);
    }

    public void killing(String mesosTaskID){
        logger.debug("__REMOVING__ mesos task from being reconciled, mesosTaskID={}", mesosTaskID);

        Protos.TaskStatus taskStatus = Protos.TaskStatus.newBuilder()
                .setTaskId(
                        Protos.TaskID.newBuilder().setValue(mesosTaskID))
                //TODO #Optimization
                /*.setSlaveId(
                        Protos.SlaveID.newBuilder().setValue(agentID).build())*/
                .setState(Protos.TaskState.TASK_KILLING)
                .build();
        toBeReconciled.add(taskStatus);
        reconcilingMesosTasks.add(taskStatus);
        reconciliationScheduler.schedule(Retry.decorateCallable(retry,() -> reconcileIfNeeded(mesosTaskID,taskStatus)) , 30, TimeUnit.SECONDS);
    }

    public void remove(String mesosTaskID){
        toBeReconciled.removeIf(x -> x.getTaskId().getValue().equals(mesosTaskID));
        reconcilingMesosTasks.removeIf(x -> x.getTaskId().getValue().equals(mesosTaskID));
    }
}
