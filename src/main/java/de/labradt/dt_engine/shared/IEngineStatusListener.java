package de.labradt.dt_engine.shared;

import de.labradt.dt_engine.engine.model.ConditionEvaluator;
import de.labradt.dt_engine.shared.dto.ExecutionStatus;

/**
 * Implemented by AASController, the only implementer today. The external
 * Service Manager calls this when a service changes state, either
 * directly if it ever runs in the same JVM, or indirectly through the
 * POST /engine/result HTTP endpoint, which deserializes the request
 * body into the same four values and calls this same method.
 *
 * There is exactly one method, so nothing here is static, and it is a
 * plain interface with no default methods. It is expandable in the
 * ordinary Java sense (a second implementer could be added and wired in
 * through Spring), but there is no registry or extension point built
 * around it the way there is for {@link ConditionEvaluator}
 * or {@link IStorageService}. Adding a field to what a status update
 * carries means changing this method's signature and every call site,
 * not registering a new implementation.
 *
 * @param serviceId   the service whose state changed
 * @param executionId the specific dispatch this update belongs to, so the
 *                     engine can tell it apart from any other run of the
 *                     same service
 * @param status      the new status of the service
 * @param info        output data on success, or an error message on failure
 */
public interface IEngineStatusListener {
    void UpdateSystemState(String serviceId, String executionId, ExecutionStatus status, String info);
}