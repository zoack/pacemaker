package org.pacemaker.scheduler.assigner;

import org.pacemaker.proto.models.Assignment;
import org.pacemaker.proto.models.Task;

import java.util.Optional;

//TODO think this better
public interface Assigner {

    Optional<Assignment> assign(Task task);
}
