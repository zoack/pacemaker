package org.pacemaker.scheduler;

import org.pacemaker.manager.FrameworkManager;
import org.pacemaker.manager.FrameworkWrapper;
import org.pacemaker.proto.models.ScheduleAction;
import org.pacemaker.utils.LogUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

public class SchedulerQueue implements Scheduler{

    private static final Logger logger = LoggerFactory.getLogger(SchedulerQueue.class);

    private volatile boolean running = true;

    private BlockingQueue<ScheduleAction> queue = new LinkedBlockingQueue<>();
    private final FrameworkManager frameworkManager;

    public SchedulerQueue(FrameworkManager frameworkManager) {
        this.frameworkManager = frameworkManager;
    }

    @Override
    public boolean schedule(ScheduleAction actions) {
        return queue.offer(actions);
    }


    @Override
    public Boolean call() throws Exception {
        while (running){
            try {
                ScheduleAction action = queue.take();
                String taskID = action.getTask().getId();
                String frameworkId = action.getTask().getAssignment().getFrameworkId();
                String actions = action.getActionsList().stream().map(Enum::name).collect(Collectors.joining(", "));
                boolean submittedAction = frameworkManager.load(frameworkId)
                        .map(FrameworkWrapper::getFramework)
                        .map(x -> LogUtils.peekAndDebugLog(x, logger, "__SUBMITTING__ task actions to framework, taskID={}, actions=[{}], frameworkID={}", taskID, actions, frameworkId))
                        .map(f -> f.submit(action)).orElse(false);
                String stringActions = action.getActionsList().stream().map(Enum::name).collect(Collectors.joining(","));
                if(submittedAction) {
                    logger.info("Actions __SUBMITTED__ to framework successfully, taskID={}, frameworkID={}, actions=[{}]", taskID, frameworkId, stringActions);
                }else{
                    logger.info("Action could __NOT__ be submitted to be scheduled on framework, taskID={}, frameworkID={}, actions=[{}]", taskID, frameworkId, stringActions);
                }
            } catch (InterruptedException e) {
                logger.error("Cannot scheduled actions",e);
            }
        }
        return true;
    }
}
