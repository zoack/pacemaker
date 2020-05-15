package org.pacemaker.controller;

import com.google.protobuf.Timestamp;
import org.pacemaker.Preprocessor;
import org.pacemaker.framework.Framework;
import org.pacemaker.manager.FrameworkManager;
import org.pacemaker.manager.FrameworkWrapper;
import org.pacemaker.proto.models.Error;
import org.pacemaker.proto.models.*;
import org.pacemaker.scheduler.Scheduler;
import org.pacemaker.watchers.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TaskController implements Controller<Task,TaskStatus>{

    private static final Logger logger = LoggerFactory.getLogger(TaskController.class);
    private final Scheduler scheduler;
    private final Collection<Preprocessor<Task>> preprocessors;

    private final Watcher watcher;
    private final FrameworkManager frameworkManager;

    public TaskController(Scheduler scheduler, FrameworkManager frameworkManager, Collection<Preprocessor<Task>> preprocessors, Watcher watcher) {
        this.scheduler = scheduler;
        this.frameworkManager = frameworkManager;
        this.preprocessors  = preprocessors;
        this.watcher = watcher;
    }

    private ScheduleAction generateScheduleActions(Task task) {
        return ScheduleAction.newBuilder()
                .setTask(task)
                .addAllActions(
                        Stream.concat(
                                task.getWorkload().equals(Task.Workload.STATEFUL) ? Stream.of(ScheduleAction.Action.CreateVolume) : Stream.empty(),
                                Stream.of(ScheduleAction.Action.Launch)
                        ).collect(Collectors.toList())
                ).build();
    }


    private ScheduleAction generateUnassignedActions(Task task){
        return ScheduleAction.newBuilder()
                .setTask(task)
                .addActions(ScheduleAction.Action.DestroyVolume)
                .build();
    }



    private ScheduleAction generateKillActions(Task task){
        return ScheduleAction.newBuilder()
                .setTask(task)
                .addActions(ScheduleAction.Action.Kill)
                .build();
    }

    private Task stopTask(Task task){
        if (!task.getStatus().getState().equals(TaskStatus.State.Killing)) {
            Task newTask = task.toBuilder().setStatus(task.getStatus()
                    .toBuilder()
                    .setState(TaskStatus.State.Killing)
            ).build();
            ScheduleAction killAction = generateKillActions(newTask);
            if (scheduler.schedule(killAction)) {

                return newTask;
            } else {
                return updateStatus(task, task.getStatus().toBuilder()
                        .setState(TaskStatus.State.Failed)
                        .setError(task.getStatus().getError().toBuilder()
                                .setFatal(false)
                                .setReason("Task could not be schedule for be killed"))
                                .build());
            }
        }else {
            return task;
        }
    }

    private Task unassignTask(Task task){
        Task newTask = task.toBuilder().setStatus(
                task.getStatus()
                        .toBuilder()
                        .setState(TaskStatus.State.Unassigning))
                .build();
        ScheduleAction action = generateUnassignedActions(newTask);
        if (scheduler.schedule(action)) {
            return newTask;
        } else {
            return updateStatus(task,
                    task.getStatus().toBuilder()
                            .setState(TaskStatus.State.Failed)
                            .setError(task.getStatus().getError().toBuilder()
                                    .setFatal(false)
                                    .setReason("Could not schedule unassigning tasks"))
                            .build());
        }
    }

    private Task startTask(Task task){
        if (!task.getStatus().getState().equals(TaskStatus.State.Scheduling)) {
            CompletableFuture<Task> processedTask = preprocessors.stream().reduce(
                    CompletableFuture.completedFuture(task),
                    (a, b) -> a.thenCompose(b::process),
                    (a, b) -> {
                        throw new RuntimeException("Parallel execution not supported");
                    }
            );
            return processedTask.thenApply(newTask -> {
                    ScheduleAction scheduleAction = generateScheduleActions(newTask);
                    if (scheduler.schedule(scheduleAction)) {
                        watcher.watch(task.getId());
                        return newTask;
                    } else return task;
            }).join();
        }else{
            return task;
        }
    }

    @Override
    public TaskStatus fetchStatus(Task task) {
        return frameworkManager.loadActive().stream()
                .map(FrameworkWrapper::getFramework)
                .map(Framework::world)
                //TODO #MultipleWorlds algorithm that select the fittest world for any given task
                .findAny()
                .flatMap(w -> Optional.ofNullable(w.get(task.getId())))
                .orElseGet(task::getStatus);
    }

    @Override
    public Task updateStatus(Task task, TaskStatus status){
        Task.Builder builder = task.toBuilder();

        TaskStatus.Builder newStatus = status.toBuilder();
        int newAttempts = task.getAttempt();
        TaskStatus oldStatus = builder.getStatus();
        boolean transitState = oldStatus.getState().equals(status.getState());
        if(!transitState){
            Instant now = Instant.now();
            Timestamp.Builder timestamp = Timestamp.newBuilder()
                    .setSeconds(now.getEpochSecond())
                    .setNanos(now.getNano());
            newStatus.setLastTransition(timestamp);
            logger.info("Task has __CHANGED__ its status from [{}] to [{}], taskID={}", oldStatus.getState(), status.getState(), builder.getId());
        }

        if(newStatus.getState().equals(TaskStatus.State.Failed) || newStatus.getState().equals(TaskStatus.State.Finished)){
            watcher.stop(task.getId());
        }

        boolean transitFromFailureToFatal = newStatus.getState().equals(TaskStatus.State.Failed) && newStatus.getError().getFatal() && !oldStatus.getError().getFatal();
        if(transitFromFailureToFatal)
        {
            logger.info("Detected a fatal error, task wont try to recover, taskID={}, error={}", task.getId(), newStatus.getError().getReason());
        }

        //TODO
        if(Objects.equals(newStatus.getState(), TaskStatus.State.Running) && newStatus.isInitialized()){
            newStatus.setError(Error.newBuilder().build());
            newAttempts = 0;
        }

        return builder
                .setAttempt(newAttempts)
                .setStatus(newStatus).build();
    }

    @Override
    public Task control(Task task) {
        TaskStatus status = task.getStatus();
        TaskStatus.State desiredState = task.getDesiredState();
        TaskStatus.State actualState = status.getState();
        if (!actualState.equals(desiredState)) {
            logger.trace("[Task ControlLoop] - Detected a __NONE__ desired state for task, desired state [{}] actual state [{}], taskID={}", desiredState, actualState, task.getId());
            switch (actualState){
                case Pending:
                    switch (desiredState){
                        case Running:
                            return startTask(task);
                        case Finished:
                            //TODO should we kill just in case or just set the finished status?
                            break;
                        default:
                            throw new IllegalStateException("Unexpected value: " + desiredState);
                    }
                    break;
                //TODO
                case Scheduling:
                    break;
                case Running:
                    if (desiredState == TaskStatus.State.Finished) {
                        return stopTask(task);
                    } else {
                        throw new IllegalStateException("Unexpected value: " + desiredState);
                    }
                //TODO
                case Unassigning:
                    break;
                //TODO
                case Killing:
                    break;
                case Finished:
                    if (desiredState == TaskStatus.State.Running) {
                        return startTask(task);
                    } else {
                        throw new IllegalStateException("Unexpected value: " + desiredState);
                    }
                case Failed:
                    int attempts = task.getAttempt();
                    RetryPolicy retryPolicy = task.getRetryPolicy();
                    int maxAttempts = retryPolicy.getMaxAttempts();
                    if (!status.getError().getFatal() && attempts < maxAttempts) {
                        Timestamp lastTransitionToFailed = status.getLastTransition();
                        Instant failedInstant = Instant.ofEpochSecond(lastTransitionToFailed.getSeconds(), lastTransitionToFailed.getNanos());
                        Instant now = Instant.now();

                        long exponentialBackoff = retryPolicy.getIntervalInMs() * (long) Math.pow(retryPolicy.getMultiplier(),attempts);
                        if(now.isAfter(failedInstant.plusMillis(exponentialBackoff))) {

                            int newAttempt = attempts + 1;
                            TaskStatus.Builder newTaskStatus = task.getStatus().toBuilder().setError(task.getStatus().getError().toBuilder());
                            logger.debug("Attempting to reach desired state for task after failure, attempts={}, maxAttempts={}, desiredState=[{}], taskID={}", newAttempt, maxAttempts, desiredState, task.getId());
                            Task newAttemptedTask = task.toBuilder()
                                    .setAttempt(newAttempt)
                                    .setStatus(newTaskStatus).build();
                            switch (desiredState) {
                                case Running:
                                    return startTask(newAttemptedTask);
                                case Finished:
                                    return stopTask(newAttemptedTask);
                                default:
                                    throw new IllegalStateException("Unexpected value: " + desiredState);
                            }
                        }else{
                            //TODO trace
                            logger.trace("Task will not be attempted to reach a desired state because the wait has not reached the exponential backoff, desiredState=[{}], taskID={}", desiredState, task.getId());
                            return task;
                        }
                    }else {
                        logger.trace("Task will not be attempted to reach a desired state because it has reached a maximum number of attempts, attempts={}, maxAttempts={}, desiredState=[{}], taskID={}", attempts, maxAttempts, desiredState, task.getId());
                        return task;
                    }
                    //TODO what to do when the task is lost
                case Lost:
                    break;
                case UNRECOGNIZED:
                default:
                    throw new IllegalStateException("Unexpected value: " + desiredState);
            }
        }else{
            if(task.getWorkload().equals(Task.Workload.STATEFUL) && task.getStatus().getState().equals(TaskStatus.State.Finished) &&
                    !task.getStatus().getUnassigned()) {
                logger.info("[Task ControlLoop] - Detected that task is on {} state but it needs to be unassigned, taskID={}", task.getStatus().getState(), task.getId());
                return unassignTask(task);
            }else {
                logger.trace("[Task ControlLoop] - Detected a __DESIRED__ state for task __NOT__ taking any action , desiredState={}, taskID={}", desiredState, task.getId());
            }
        }
        return task.toBuilder().build();
    }


}


