package de.labradt.dt_engine.shared.db;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;

/**
 * Database backed storage service.
 * Provides one function to store current state and data into H2.
 * Called by StateChartExecutor on every transition and data write.
 */
@Slf4j
@Component
public class DatabaseStorageService {

    @Autowired
    private EngineStateRepository engineStateRepository;

    @Autowired
    private SubmodelDataRepository submodelDataRepository;

    /**
     * Stores a state transition into the engine_state table.
     * Called by StateChartExecutor on every transition.
     */
    public void storeStateTransition(String fromState,
                                      String toState,
                                      String trigger) {
        EngineStateEntity entity =
            EngineStateEntity.of(fromState, toState, trigger);
        engineStateRepository.save(entity);
        log.info("[DatabaseStorageService] Stored transition: {} --{}--> {}",
            fromState, trigger, toState);
    }

    /**
     * Stores a submodel element value into the submodel_data table.
     * Called by AASManager when data arrives or is written.
     */
    public void storeSubmodelData(String submodelId,
                                   String elementId,
                                   String value) {
        SubmodelDataEntity entity =
            SubmodelDataEntity.of(submodelId, elementId, value);
        submodelDataRepository.save(entity);
        log.info("[DatabaseStorageService] Stored data: {}.{} = {}",
            submodelId, elementId, value);
    }

    /**
     * Reads the latest value of a submodel element from the database.
     * Called by StateChartExecutor during rule evaluation.
     */
    public String readLatestValue(String submodelId, String elementId) {
        return submodelDataRepository
            .findTopBySubmodelIdAndElementIdOrderByTimestampDesc(
                submodelId, elementId)
            .map(SubmodelDataEntity::getValue)
            .orElse(null);
    }

    public List<EngineStateEntity> getRecentTransitions() {
        return engineStateRepository.findTop10ByOrderByTimestampDesc();
    }

    public List<SubmodelDataEntity> getRecentDataWrites() {
        return submodelDataRepository.findTop8ByOrderByTimestampDesc();
    }
}