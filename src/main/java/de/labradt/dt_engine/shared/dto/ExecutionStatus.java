package de.labradt.dt_engine.shared.dto;

/**
 * Status of a service execution, mirrored from the Runtime
 * component's own enum. Kept as a separate, manually updated copy for the
 * same reason as ExecutionResponseDTO: the two components stay independent
 * instead of sharing a library.
 */
public enum ExecutionStatus {
    ACCEPTED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}