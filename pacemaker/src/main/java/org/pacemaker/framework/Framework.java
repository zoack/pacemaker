package org.pacemaker.framework;

import org.pacemaker.Sensor;
import org.pacemaker.proto.models.FrameworkStatus;
import org.pacemaker.proto.models.ScheduleAction;
import org.pacemaker.proto.models.TaskStatus;
import org.pacemaker.scheduler.assigner.Assigner;

public interface Framework extends Sensor<TaskStatus> {

    boolean start();
    boolean stop();
    void shutdown();

    boolean submit(ScheduleAction action);
    FrameworkStatus status();
    //TODO think this better, it does not fit here
    Assigner assigner();
}
