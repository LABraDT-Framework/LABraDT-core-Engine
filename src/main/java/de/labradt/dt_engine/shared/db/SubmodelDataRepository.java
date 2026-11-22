package de.labradt.dt_engine.shared.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.List;

/**
 * Spring Data JPA repository for {@link SubmodelDataEntity}. Extending
 * JpaRepository is what generates the standard save, find and delete
 * methods, none of them written here by hand. Both methods declared
 * below are derived queries, their implementation is generated from the
 * method name alone, following Spring Data's naming convention, not
 * written anywhere in this codebase.
 *
 * Used only by {@link DatabaseStorageService}, which is the sole place
 * that talks to this repository directly.
 */
@Repository
public interface SubmodelDataRepository
        extends JpaRepository<SubmodelDataEntity, Long> {

    /**
     * The single latest value for one element, if any exists. Backs the
     * database first half of StateChartExecutor.resolveValue(), which is
     * what rule evaluation actually reads.
     */
    Optional<SubmodelDataEntity> findTopBySubmodelIdAndElementIdOrderByTimestampDesc(
        String submodelId, String elementId);

    /** The 8 most recent data writes across every element, newest first. Backs GET /engine/submodel-data. */
    List<SubmodelDataEntity> findTop8ByOrderByTimestampDesc();
}