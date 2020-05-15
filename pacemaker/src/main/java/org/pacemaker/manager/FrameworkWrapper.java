package org.pacemaker.manager;

import org.pacemaker.framework.Framework;
import org.pacemaker.proto.models.FrameworkInfo;

public class FrameworkWrapper {

    private FrameworkInfo frameworkInfo;
    private Framework framework;

    public FrameworkWrapper(FrameworkInfo frameworkInfo, Framework framework) {
        this.frameworkInfo = frameworkInfo;
        this.framework = framework;
    }

    public String getId(){
        return frameworkInfo.getId();
    }

    public FrameworkInfo getFrameworkInfo() {
        return frameworkInfo;
    }

    public Framework getFramework() {
        return framework;
    }
}
