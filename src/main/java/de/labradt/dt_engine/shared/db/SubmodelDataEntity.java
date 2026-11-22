package de.labradt.dt_engine.shared.db;

import lombok.Data;
import javax.persistence.*;
import java.time.Instant;

/**
 * Records one value received for a submodel element, including values
 * that were rejected at the validation boundary (see
 * StateChartExecutor.validateAndNormalize()), which are stored with a
 * "[REJECTED:reason]" prefix on the value rather than being dropped.
 * Stored in the submodel_data table.
 *
 * Written by DatabaseStorageService.storeSubmodelData(), called from
 * StateChartExecutor.onDataPush() for every push, accepted or rejected.
 * Read back by SubmodelDataRepository and exposed through
 * AASController's GET /engine/submodel-data endpoint, and through
 * StateChartExecutor.resolveValue() as the database first lookup for
 * rule evaluation.
 */
@Data
@Entity
@Table(name = "submodel_data")
public class SubmodelDataEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Instant timestamp;
    private String submodelId;
    private String elementId;
    @Column(name = "element_value")
    private String value;

    /**
     * Builds a new row with the current time as its timestamp. The static
     * factory keeps callers from having to know about or set the id or
     * timestamp themselves.
     */
    public static SubmodelDataEntity of(String submodelId,
                                         String elementId,
                                         String value) {
        SubmodelDataEntity e = new SubmodelDataEntity();
        e.setTimestamp(Instant.now());
        e.setSubmodelId(submodelId);
        e.setElementId(elementId);
        e.setValue(value);
        return e;
    }
}