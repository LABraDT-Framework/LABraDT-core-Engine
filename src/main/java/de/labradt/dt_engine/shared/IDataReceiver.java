package de.labradt.dt_engine.shared;

import de.labradt.dt_engine.engine.model.ConditionEvaluator;

/**
 * Implemented by AASController, the only implementer today. GatewayStreamClient
 * calls this in process (see its dataReceiver field) whenever the external
 * Gateway's SSE stream reports an update event, and AASController's own /engine/push and
 * /engine/push/{submodelId} REST endpoints call the same methods on
 * themselves for manual testing that does not depend on the Gateway.
 *
 * Two methods, neither static. There is no registry here: unlike
 * {@link ConditionEvaluator}, a new
 * consumer of this interface is wired in through Spring, not registered
 * at runtime. onEmergencyPush() exists as its own method so an emergency
 * push can one day be given different handling (skipping validation,
 * bypassing a cooldown), but today it does exactly what onDataPush()
 * does, with no priority logic yet.
 */
public interface IDataReceiver {
    void onDataPush(String submodelId, String elementId, String value);
    /** Initial/reconnect snapshots and periodic observations update telemetry without dispatching rules. */
    default void onDataSnapshot(String submodelId, String elementId, String value) {}
    void onEmergencyPush(String submodelId, String elementId, String value);
}
