package org.pacemaker.watchers;

import org.pacemaker.Sensor;
import org.pacemaker.proto.models.ProbeResult;

import java.io.Closeable;

public interface Watcher extends Closeable, Sensor<ProbeResult> {

    void watch(String taskID);

    void stop(String taskID);

}
