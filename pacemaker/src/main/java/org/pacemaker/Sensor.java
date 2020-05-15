package org.pacemaker;

import java.util.Map;

public interface Sensor<T> {

    Map<String,T> world();
}
