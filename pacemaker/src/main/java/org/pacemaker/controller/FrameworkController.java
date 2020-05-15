package org.pacemaker.controller;

import com.google.protobuf.Timestamp;
import org.pacemaker.framework.Framework;
import org.pacemaker.manager.FrameworkWrapper;
import org.pacemaker.proto.models.Error;
import org.pacemaker.proto.models.FrameworkInfo;
import org.pacemaker.proto.models.FrameworkStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;

public class FrameworkController implements Controller<FrameworkWrapper,FrameworkStatus>{


    private static final Logger logger = LoggerFactory.getLogger(FrameworkController.class);

    public FrameworkController() {

    }
    @Override
    public FrameworkWrapper updateStatus(FrameworkWrapper frameworkWrapper, FrameworkStatus status) {
        FrameworkInfo.Builder frameworkInfo = frameworkWrapper.getFrameworkInfo().toBuilder();
        Instant now = Instant.now();
        Timestamp.Builder timestamp = Timestamp.newBuilder()
                .setSeconds(now.getEpochSecond())
                .setNanos(now.getNano());
        FrameworkStatus.Builder newStatus = status.toBuilder().setLastProbeTime(timestamp);
        if(!frameworkInfo.getStatus().getState().equals(newStatus.getState())){
            newStatus.setLastTransition(timestamp);
        }
        if(frameworkInfo.getStatus().getState().equals(FrameworkStatus.State.Failed)
                && !newStatus.getState().equals(FrameworkStatus.State.Failed)){
            newStatus.setError(Error.newBuilder().build());
        }
        return new FrameworkWrapper(frameworkInfo.setStatus(newStatus).build(), frameworkWrapper.getFramework());
    }

    @Override
    public FrameworkStatus fetchStatus(FrameworkWrapper value) {
        return value.getFramework().status();
    }


    @Override
    public FrameworkWrapper control(FrameworkWrapper frameworkWrapper) {
        Framework framework = frameworkWrapper.getFramework();

        FrameworkInfo.Builder frameworkInfo = frameworkWrapper.getFrameworkInfo().toBuilder();
        FrameworkStatus.State desiredState = frameworkInfo.getDesiredState();
        FrameworkStatus.Builder status = frameworkInfo.getStatus().toBuilder();
        int attempts = frameworkInfo.getAttempt();

        if (!status.getState().equals(desiredState)) {
            logger.info("[Framework ControlLoop] - Detected a __NONE__ desired state for framework, desired state [{}] actual state [{}], frameworkID={}", desiredState, status.getState(), frameworkWrapper.getId());
            switch (status.getState()) {
                case Inactive:
                    switch (desiredState) {
                        case Active:
                            if (!status.getStarting()) {
                                framework.start();
                            }
                            break;
                        case Inactive:
                        case Failed:
                        case UNRECOGNIZED:
                            break;
                    }
                    break;
                case Active:
                    switch (desiredState) {
                        case Inactive:
                            if (!status.getStopping()) {
                                framework.stop();
                            }
                            break;
                        case Active:
                        case Failed:
                        case UNRECOGNIZED:
                            break;
                    }
                    break;
                case Failed:
                    Error error = status.getError();
                    int maxAttempts = frameworkInfo.getRetryPolicy().getMaxAttempts();
                    if (!error.getFatal() && attempts <= maxAttempts) {
                        logger.debug("Attempting to reach desired state for framework, attempts={}, maxAttempts={}, desiredState=[{}], frameworkID={}", attempts,maxAttempts, desiredState.name(), frameworkWrapper.getId());
                        switch (desiredState) {
                            case Inactive:
                                if (!status.getStopping()) {
                                    ++attempts;
                                    framework.stop();
                                }
                                break;
                            case Active:
                                if (!status.getStarting()) {
                                    ++attempts;
                                    framework.start();
                                }
                                break;
                            case Failed:
                            case UNRECOGNIZED:
                                break;
                        }
                    }
                    break;
                case UNRECOGNIZED:
                    break;
            }
        }
        return new FrameworkWrapper(frameworkInfo
                .setAttempt(attempts)
                .setStatus(status).build(), frameworkWrapper.getFramework());
    }
}
