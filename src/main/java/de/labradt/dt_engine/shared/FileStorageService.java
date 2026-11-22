package de.labradt.dt_engine.shared;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/**
 * File based implementation of IStorageService.
 *
 * Stores each value as a .json file on disk under a configurable root
 * directory. The key maps directly to the file path. For example, the
 * key "CPS_SM/Temperature" is stored as the file
 * storage/CPS_SM/Temperature.json.
 *
 * This implementation can be swapped for one backed by a database
 * without changing any engine logic. Just provide a different
 * IStorageService bean.
 *
 * Every key is resolved against an absolute storage root, and any key
 * that would resolve outside that root is rejected (see
 * resolveFilePath()), so a key coming from an HTTP request can never be
 * used to read, write or delete a file outside the storage directory.
 */
@Slf4j
@Component
public class FileStorageService implements IStorageService {

    @Value("${engine.storage.path:storage}")
    private String storagePath;

    /**
     * Stores a JSON value under the given key.
     * Creates parent directories if they don't exist.
     */
    @Override
    public void store(String key, String value) {
        try {
            Path filePath = resolveFilePath(key);
            Files.createDirectories(filePath.getParent());
            Files.writeString(filePath, value, StandardCharsets.UTF_8);
            log.info("[FileStorageService] Stored: {} → {}", key, filePath);
        } catch (IOException e) {
            log.error("[FileStorageService] Failed to store key {}: {}", key, e.getMessage());
        } catch (SecurityException e) {
            log.error("[FileStorageService] Rejected key {}: {}", key, e.getMessage());
        }
    }

    /**
     * Retrieves the JSON value stored under the given key.
     * Returns null if the key does not exist.
     */
    @Override
    public String retrieve(String key) {
        try {
            Path filePath = resolveFilePath(key);
            if (!Files.exists(filePath)) return null;
            String value = Files.readString(filePath, StandardCharsets.UTF_8);
            log.info("[FileStorageService] Retrieved: {}", key);
            return value;
        } catch (IOException e) {
            log.error("[FileStorageService] Failed to retrieve key {}: {}", key, e.getMessage());
            return null;
        } catch (SecurityException e) {
            log.error("[FileStorageService] Rejected key {}: {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Returns true if the key exists in storage.
     */
    @Override
    public boolean exists(String key) {
        try {
            return Files.exists(resolveFilePath(key));
        } catch (SecurityException e) {
            log.error("[FileStorageService] Rejected key {}: {}", key, e.getMessage());
            return false;
        }
    }

    /**
     * Deletes the file associated with the given key.
     */
    @Override
    public void delete(String key) {
        try {
            Path filePath = resolveFilePath(key);
            Files.deleteIfExists(filePath);
            log.info("[FileStorageService] Deleted: {}", key);
        } catch (IOException e) {
            log.error("[FileStorageService] Failed to delete key {}: {}", key, e.getMessage());
        } catch (SecurityException e) {
            log.error("[FileStorageService] Rejected key {}: {}", key, e.getMessage());
        }
    }

    /**
     * Lists all keys whose file paths start with the given prefix.
     * Example: listKeys("Rules_SM/") returns all rule keys.
     */
    @Override
    public List<String> listKeys(String prefix) {
        try {
            Path root = resolveRoot();
            Path prefixPath = root.resolve(prefix.replace("/", File.separator)).normalize();
            if (!prefixPath.startsWith(root)) {
                log.error("[FileStorageService] Rejected prefix escaping storage root: {}", prefix);
                return Collections.emptyList();
            }
            if (!Files.exists(prefixPath)) return Collections.emptyList();

            return Files.walk(prefixPath)
                .filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".json"))
                .map(p -> {
                    String relative = root.relativize(p).toString();
                    // Convert file path back to key format
                    return relative
                        .replace(File.separator, "/")
                        .replace(".json", "");
                })
                .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("[FileStorageService] Failed to list keys for prefix {}: {}",
                prefix, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Returns the full storage path for this service.
     * Useful for passing to the external Runtime component as a fileReference base path.
     */
    public String getStoragePath() {
        return storagePath;
    }

    /**
     * Absolute, normalized storage root. Every resolved path is checked
     * against this so a key can never escape the storage directory.
     */
    private Path resolveRoot() {
        return Paths.get(storagePath).toAbsolutePath().normalize();
    }

    /**
     * Resolves a storage key to an absolute file path.
     * Key "CPS_SM/Temperature" becomes storage/CPS_SM/Temperature.json
     *
     * Rejects any key that would resolve outside the storage root,
     * whether through "../" segments or through an absolute path of its
     * own. This matters because untrusted input (the storage endpoints
     * take a key straight from the request) could otherwise be used for
     * path traversal, to read, write or delete arbitrary files on disk.
     */
    private Path resolveFilePath(String key) {
        Path root = resolveRoot();
        String fileSafeKey = key.replace("/", File.separator);
        Path candidate = root.resolve(fileSafeKey + ".json").normalize();
        if (!candidate.startsWith(root)) {
            throw new SecurityException("Storage key escapes storage root: " + key);
        }
        return candidate;
    }
}
