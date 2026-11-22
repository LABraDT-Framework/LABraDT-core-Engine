package de.labradt.dt_engine.shared.db;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

/**
 * Spring Data JPA repository for {@link EngineStateEntity}. Extending
 * JpaRepository is what generates the standard save, find and delete
 * methods, none of them written here by hand. The one method declared
 * below is a derived query, its implementation is generated from the
 * method name alone, following Spring Data's naming convention, not
 * written anywhere in this codebase.
 *
 * Used only by {@link DatabaseStorageService}, which is the sole place
 * that talks to this repository directly.
 */
@Repository
public interface EngineStateRepository
        extends JpaRepository<EngineStateEntity, Long> {

    /** The 10 most recent transitions, newest first. Backs GET /engine/history. */
    List<EngineStateEntity> findTop10ByOrderByTimestampDesc();
}