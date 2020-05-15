package org.pacemaker.controller;

import org.pacemaker.manager.Manager;
import org.pacemaker.utils.FutureUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Callable;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

public class ControlLoop<T,K> implements Callable<Boolean> {

    private volatile boolean running = true;
    private static final Logger logger = LoggerFactory.getLogger(ControlLoop.class);

    private Manager<T> manager;
    private Predicate<T> filter;
    private Controller<T,K> controller;

    public ControlLoop(Manager<T> manager, Predicate<T> filter, Controller<T,K> controller) {
        this.manager = manager;
        this.filter = filter;
        this.controller = controller;
    }


    @Override
    public Boolean call() {
        while(running){
            try {
                Thread.sleep(3000);
                UnaryOperator<T> control = x -> controller.control(controller.updateStatus(x, controller.fetchStatus(x)));
                //TODO #Evaluate should we wait until all are done or handle those that are being treated differently?
                // if we keep waiting until all are finished we can have a case where a controlled resources blocks all the others..
                FutureUtils.all(manager.loadAll()
                    .entrySet()
                    .stream()
                    .filter( x -> filter.test(x.getValue()))
                    .map(x -> manager.loadAndUpdate(x.getKey(), control))
                    .collect(Collectors.toList())).join();
                //TODO #setup Dynamic sleep depending on workload
            } catch (InterruptedException e) {
                logger.error("Thread was interrupted");
                Thread.currentThread().interrupt();
            }catch (Exception e){
                //TODO #Error handling
                logger.error("Unknown error on control loop",e);
            }
        }
        return running;
    }


}
