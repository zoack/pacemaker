package org.pacemaker.scheduler;

import org.pacemaker.proto.models.ScheduleAction;

import java.util.concurrent.Callable;

public interface Scheduler extends Callable<Boolean> {
    boolean schedule(ScheduleAction action);
}
