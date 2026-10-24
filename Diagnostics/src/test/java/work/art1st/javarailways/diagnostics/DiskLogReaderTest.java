package work.art1st.javarailways.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DiskLogReaderTest {
    @TempDir Path directory;
    private Path file() { return directory.resolve("errors.log"); }
    private DiskLogReader reader() { return new DiskLogReader(Map.of("errors", new DiskLogReader.Location("Errors", file()))); }
    private String event(String message) { return "[2026-10-23T00:00:00Z] SEVERE module=Core logger=test\n" + message + "\n\n"; }

    @Test void readsRetainedFilesOnRestartAndRejectsPaths() throws Exception {
        Files.writeString(file(), event("failure\nCaused by: original\nSuppressed: cleanup"));
        Files.writeString(directory.resolve("errors.log.1"), event("older"));
        var page = reader().read("errors", "", null);
        assertEquals(2, page.entries().size());
        assertEquals("Core", page.entries().get(0).module());
        assertTrue(page.entries().get(0).text().contains("Suppressed: cleanup"));
        assertEquals(1, reader().read("errors", "original", null).entries().size());
        assertThrows(IllegalArgumentException.class, () -> reader().read("../errors.log", "", null));
        assertThrows(IllegalArgumentException.class, () -> reader().read("errors", "", "bogus"));
        assertThrows(IllegalArgumentException.class, () -> reader().read("errors", "a".repeat(201), null));
    }

    @Test void paginationKeepsNormalStacksTogetherAndAppendDoesNotChangeSnapshot() throws Exception {
        String record = event("needle" + "x".repeat(200_000) + "\nCaused by: tail");
        Files.writeString(file(), record.repeat(4));
        var page = reader().read("errors", "needle", null);
        assertTrue(page.scannedBytes() <= 512 * 1024);
        assertEquals(2, page.entries().size());
        assertTrue(page.entries().stream().noneMatch(DiskLogReader.Entry::partial));
        assertTrue(page.entries().stream().allMatch(e -> e.text().contains("Caused by: tail")));
        Files.writeString(file(), event("new after snapshot"), StandardOpenOption.APPEND);
        var older = reader().read("errors", "needle", page.nextCursor());
        assertFalse(older.reset());
        assertEquals(2, older.entries().size());
        assertNull(older.nextCursor());
        assertFalse(older.entries().get(0).text().contains("new after snapshot"));
    }

    @Test void oversizedRecordsAreBoundedAndExplicitlyPartialAndRotationResetsCursor() throws Exception {
        Files.writeString(file(), event("x".repeat(900_000)));
        var page = reader().read("errors", "", null);
        assertEquals(512 * 1024, page.scannedBytes());
        assertTrue(page.entries().get(0).partial());
        assertNotNull(page.nextCursor());
        Files.move(file(), directory.resolve("errors.log.1"));
        Files.writeString(file(), event("replacement"));
        assertTrue(reader().read("errors", "", page.nextCursor()).reset());
    }

    @Test void missingFileIsEmptyAndSymlinkIsRefused() throws Exception {
        assertFalse(reader().sources().get(0).available());
        assertTrue(reader().read("errors", "", null).entries().isEmpty());
        Path target = directory.resolve("secret");
        Files.writeString(target, "secret");
        Files.createSymbolicLink(file(), target);
        assertThrows(java.io.IOException.class, () -> reader().read("errors", "", null));
    }
}
