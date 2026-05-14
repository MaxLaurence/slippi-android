package org.dolphinemu.dolphinemu.features.input.model;

import android.view.InputDevice;

public final class DolphinSensorEventListener {
    public DolphinSensorEventListener() {}

    public DolphinSensorEventListener(InputDevice inputDevice) {}

    public void setDeviceQualifier(String qualifier) {}

    public void requestUnsuspendSensor(String axisName) {}

    public String[] getAxisNames() {
        return new String[0];
    }

    public boolean[] getNegativeAxes() {
        return new boolean[0];
    }
}
