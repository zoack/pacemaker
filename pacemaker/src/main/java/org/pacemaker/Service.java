package org.pacemaker;

import org.pacemaker.api.GRPCServer;
import org.pacemaker.controller.ControlLoop;
import org.pacemaker.manager.FrameworkWrapper;
import org.pacemaker.scheduler.Scheduler;
import org.pacemaker.proto.models.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class Service implements Runnable{
    private static final Logger logger = LoggerFactory.getLogger(Service.class);

    private final ExecutorService executorService = Executors.newScheduledThreadPool(5);
    private volatile boolean running = true;

    //TODO #multi-tenant
    private GRPCServer server;
    private final Scheduler scheduler;
    private final ControlLoop<FrameworkWrapper, FrameworkStatus> frameworkControlLoop;
    private final ControlLoop<Task, TaskStatus> taskControlLoop;
    private final ControlLoop<Container, ProbeResult> containerControlLoop;


    public Service(GRPCServer server, Scheduler scheduler, ControlLoop<FrameworkWrapper, FrameworkStatus> frameworkControlLoop, ControlLoop<Task, TaskStatus> taskControlLoop, ControlLoop<Container, ProbeResult> containerControlLoop) {
        this.server = server;
        this.scheduler = scheduler;
        this.frameworkControlLoop = frameworkControlLoop;
        this.taskControlLoop = taskControlLoop;
        this.containerControlLoop = containerControlLoop;
    }

    public void run() {
        //TODO #errorHandling
        logger.info("Service running...");
        try {
            //TODO #resilience
            executorService.submit(server);
            executorService.submit(scheduler);
            executorService.submit(frameworkControlLoop);
            executorService.submit(taskControlLoop);
            executorService.submit(containerControlLoop);
            while (running) {
                try {
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }catch (Exception e){
            throw new RuntimeException(e);
        }
    }


    //TODO shutdown gracefully
    public void stop(){
        executorService.shutdown();
        running = false;
    }
}
