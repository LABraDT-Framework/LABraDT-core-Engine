package de.labradt.dt_engine.shared.db;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

/**
 * Records one state transition that occurred in the engine.
 * Stored in the engine_state table.
 *
 * Written by DatabaseStorageService.storeStateTransition(), called from
 * StateChartExecutor.fireEvent() on every transition. Read back by
 * EngineStateRepository and exposed through AASController's
 * GET /engine/history endpoint.
 */
@Data
@Entity
@Table(name = "engine_state")
public class EngineStateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant timestamp;
    private String fromState;
    private String toState;
    private String trigger;

    /**
     * Builds a new row with the current time as its timestamp. The static
     * factory keeps callers from having to know about or set the id or
     * timestamp themselves.
     */
    public static EngineStateEntity of(String from, String to, String trigger) {
        EngineStateEntity e = new EngineStateEntity();
        e.setTimestamp(Instant.now());
        e.setFromState(from);
        e.setToState(to);
        e.setTrigger(trigger);
        return e;
    }
}