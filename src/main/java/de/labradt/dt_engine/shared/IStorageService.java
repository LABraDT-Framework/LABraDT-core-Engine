package de.labradt.dt_engine.shared;

/**
 * Interface for persistent storage of AAS submodel data.
 *
 * Abstracts the underlying storage mechanism so it can be swapped
 * between a file, a database or cloud storage without changing any
 * engine logic. Today only {@link FileStorageService}
 * implements it, and every caller (AASManager, StateChartExecutor, AASController)
 * depends on this interface rather than on that class directly, so a
 * different implementation could be swapped in as its own Spring bean
 * with no change anywhere else. None of the methods are static. Each
 * implementation is expected to be a normal Spring managed instance.
 *
 * Keys follow the pattern: "submodelIdShort/elementIdShort"
 * Example: "CPS_SM/Temperature", "Rules_SM/Rule_1"
 *
 * Values are JSON strings representing the stored data.
 */
public interface IStorageService {

    /**
     * Stores a value under the given key.
     * Overwrites if key already exists.
     *
     * @param key   storage key e.g. "CPS_SM/Temperature"
     * @param value JSON string value to store
     */
    void store(String key, String value);

    /**
     * Retrieves a value by key.
     *
     * @param key storage key
     * @return the stored JSON string, or null if not found
     */
    String retrieve(String key);

    /**
     * Checks if a key exists in storage.
     *
     * @param key storage key
     * @return true if the key exists
     */
    boolean exists(String key);

    /**
     * Deletes a key from storage.
     *
     * @param key storage key
     */
    void delete(String key);

    /**
     * Lists all keys that start with the given prefix.
     * Useful for retrieving all elements of a submodel.
     *
     * Example: listKeys("Rules_SM/") returns all rule keys
     *
     * @param prefix key prefix to filter by
     * @return list of matching keys
     */
    java.util.List<String> listKeys(String prefix);
}
