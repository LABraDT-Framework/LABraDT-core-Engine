package de.labradt.dt_engine;

import de.labradt.dt_engine.shared.FileStorageService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.Assert.*;

/**
 * Verifies FileStorageService rejects a key that would resolve outside
 * the configured storage root, instead of following it (for example
 * through "../" segments or an absolute path) to read, write or delete
 * an arbitrary file on disk.
 *
 * The storage endpoints in AASController (upload, retrieve, delete) pass
 * a key supplied by the caller straight into this service, so this is
 * the one place that needs to enforce the boundary.
 */
public class FileStorageServicePathTraversalTest {

    private Path tempRoot;
    private FileStorageService storage;

    @Before
    public void setUp() throws Exception {
        tempRoot = Files.createTempDirectory("fss-traversal-test");
        storage = new FileStorageService();
        Field f = FileStorageService.class.getDeclaredField("storagePath");
        f.setAccessible(true);
        f.set(storage, tempRoot.toString());
    }

    @After
    public void tearDown() throws Exception {
        if (tempRoot != null && Files.exists(tempRoot)) {
            Files.walk(tempRoot)
                .sorted(Comparator.reverseOrder())
                .forEach(p -> p.toFile().delete());
        }
    }

    @Test
    public void store_withDotDotKey_doesNotEscapeRoot() throws Exception {
        // A sibling file this attack would try to create or overwrite.
        Path outside = tempRoot.resolveSibling("escaped-by-test.json");
        Files.deleteIfExists(outside);

        storage.store("../escaped-by-test", "malicious content");

        assertFalse("traversal key must not create a file outside the storage root",
            Files.exists(outside));
    }

    @Test
    public void retrieve_withDotDotKey_returnsNullInsteadOfEscapedFile() throws Exception {
        // A real secret living just outside the storage root.
        Path secret = tempRoot.resolveSibling("secret.json");
        Files.writeString(secret, "top secret");
        try {
            String result = storage.retrieve("../secret");
            assertNull("must not read a file outside the storage root", result);
        } finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    public void delete_withDotDotKey_doesNotDeleteOutsideFile() throws Exception {
        Path victim = tempRoot.resolveSibling("victim.json");
        Files.writeString(victim, "do not delete me");
        try {
            storage.delete("../victim");
            assertTrue("must not delete a file outside the storage root",
                Files.exists(victim));
        } finally {
            Files.deleteIfExists(victim);
        }
    }

    @Test
    public void exists_withDotDotKey_returnsFalseEvenIfOutsideFileExists() throws Exception {
        Path outside = tempRoot.resolveSibling("present.json");
        Files.writeString(outside, "i exist, but outside the root");
        try {
            assertFalse("must not report a file outside the storage root as existing",
                storage.exists("../present"));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    public void store_withAbsolutePathKey_doesNotEscapeRoot() throws Exception {
        Path outside = tempRoot.getParent().resolve("absolute-escape.json");
        Files.deleteIfExists(outside);

        String absoluteKey = outside.toString().replace(".json", "");
        storage.store(absoluteKey, "malicious content");

        assertFalse("an absolute path key must not escape the storage root",
            Files.exists(outside));
    }

    @Test
    public void store_withNormalKey_stillWorksInsideRoot() {
        storage.store("CPS_SM/Temperature", "{\"value\":\"90.0\"}");
        assertTrue(storage.exists("CPS_SM/Temperature"));
        assertEquals("{\"value\":\"90.0\"}", storage.retrieve("CPS_SM/Temperature"));
    }
}
