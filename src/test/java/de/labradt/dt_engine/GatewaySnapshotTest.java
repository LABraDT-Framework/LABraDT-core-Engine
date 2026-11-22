package de.labradt.dt_engine;

import de.labradt.dt_engine.engine.GatewayStreamClient;
import de.labradt.dt_engine.shared.IDataReceiver;
import org.junit.Test;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

public class GatewaySnapshotTest {
    static class Receiver implements IDataReceiver {
        int updates, snapshots; String value;
        public void onDataPush(String submodel, String element, String value) { updates++; this.value = value; }
        public void onDataSnapshot(String submodel, String element, String value) { snapshots++; this.value = value; }
        public void onEmergencyPush(String submodel, String element, String value) {}
    }
    private void event(String type, String machine, String status, Receiver receiver) throws Exception {
        GatewayStreamClient client = new GatewayStreamClient();
        for (String name : new String[]{"dataReceiver", "machineId", "submodelId"}) {
            Field field = GatewayStreamClient.class.getDeclaredField(name); field.setAccessible(true);
            field.set(client, name.equals("dataReceiver") ? receiver : name.equals("machineId") ? "lab-machine" : "CPS_SM");
        }
        Method method = GatewayStreamClient.class.getDeclaredMethod("handleEvent", String.class, String.class); method.setAccessible(true);
        // A qualifier's value intentionally appears before the Property value.
        String json = "{\"key\":\"" + machine + ".Temperature\",\"property\":{\"idShort\":\"Temperature\",\"qualifiers\":[{\"type\":\"StatusCode\",\"value\":\"" + status + "\"}],\"value\":\"42.5\"}}";
        method.invoke(client, type, json);
    }
    @Test public void snapshotReadsPropertyValueRatherThanQualifierValue() throws Exception {
        Receiver receiver = new Receiver(); event("snapshot", "lab-machine", "OK", receiver);
        assertEquals(1, receiver.snapshots); assertEquals(0, receiver.updates); assertEquals("42.5", receiver.value);
    }
    @Test public void staleBadAndOtherMachineSnapshotsAreIgnored() throws Exception {
        Receiver receiver = new Receiver();
        event("snapshot", "other-machine", "OK", receiver);
        event("snapshot", "lab-machine", "STALE", receiver);
        event("snapshot", "lab-machine", "ERROR", receiver);
        assertEquals(0, receiver.snapshots); assertEquals(0, receiver.updates);
    }
    @Test public void updatesUseNormalDispatchPath() throws Exception {
        Receiver receiver = new Receiver(); event("update", "lab-machine", "OK", receiver);
        assertEquals(1, receiver.updates); assertEquals(0, receiver.snapshots); assertEquals("42.5", receiver.value);
    }
    @Test public void periodicObservationsRefreshWithoutDispatchAndRejectStaleData() throws Exception {
        Receiver receiver = new Receiver();
        event("keyframe", "lab-machine", "OK", receiver);
        event("keyframe", "lab-machine", "STALE", receiver);
        event("keyframe", "other-machine", "OK", receiver);
        assertEquals(1, receiver.snapshots); assertEquals(0, receiver.updates); assertEquals("42.5", receiver.value);
    }
}
