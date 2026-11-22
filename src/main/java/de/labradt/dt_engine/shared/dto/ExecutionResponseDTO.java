package de.labradt.dt_engine.shared.dto;

import lombok.Data;
import java.time.Instant;

/**
 * Result of a service execution, sent to the engine either through a direct
 * method call (see IEngineStatusListener.UpdateSystemState) or as the JSON
 * body of the POST /engine/result callback.
 *
 * This is a manually kept copy of the DTO defined in the Runtime
 * component's core-api module. The two are not shared as a library on
 * purpose, so that the two components stay independent. Because of that,
 * this copy has to be updated by hand whenever a field is added or changed
 * on the other side.
 */
@Data
public class ExecutionResponseDTO {
    private String serviceId;

    // Identifies the specific dispatch this response belongs to, so a result
    // can be matched against the attempt that triggered it instead of
    // whatever rule happens to be at the head of the action queue.
    private String executionId;

    private ExecutionStatus status;
    private boolean success;
    private String outputData;
    private String errorMessage;
    private Instant completedAt;
    private Long executionDurationMs;
}