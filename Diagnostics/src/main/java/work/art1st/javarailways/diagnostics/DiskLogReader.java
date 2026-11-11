package work.art1st.javarailways.diagnostics;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.regex.Pattern;

/** Reads only constructor-registered files, on an HTTP worker. No world/runtime access. */
public final class DiskLogReader {
    public record Source(String id, String label, boolean available) {}
    public record Entry(String text, String module, String level, String time, boolean partial) {}
    public record Page(String source, List<Entry> entries, String nextCursor, boolean reset,
                       int scannedBytes, String retention) {}
    public record Location(String label, Path path) {}
    private record FileState(Path path, long size, String identity) {}
    private static final int PAGE_BYTES = 512 * 1024;
    private static final String RETENTION = "Current file and one retained rotation; records may be split at the page boundary.";
    private static final Pattern HEADER = Pattern.compile("(?m)^\\[([^\\r\\n]+)] ([A-Z]+) module=([^ \\r\\n]+)[^\\r\\n]*\\r?\\n");
    private final Map<String, Location> locations;

    public DiskLogReader(Map<String, Location> locations) {
        this.locations = Collections.unmodifiableMap(new LinkedHashMap<>(locations));
    }

    public List<Source> sources() throws IOException {
        List<Source> result = new ArrayList<>();
        for (var entry : locations.entrySet()) {
            Path path = entry.getValue().path();
            result.add(new Source(entry.getKey(), entry.getValue().label(), state(path).size > 0 || state(rotation(path)).size > 0));
        }
        return List.copyOf(result);
    }

    public Page read(String source, String query, String cursor) throws IOException {
        Location location = locations.get(source);
        if (location == null) throw new IllegalArgumentException("Unknown log source");
        String needle = query == null ? "" : query;
        if (needle.length() > 200) throw new IllegalArgumentException("Log query is too long");
        FileState old = state(rotation(location.path())), current = state(location.path());
        String identity = digest(source + "|" + old.identity + "|" + current.identity + "|" + needle);
        long oldSize = old.size, currentSize = current.size;
        long end = Math.addExact(oldSize, currentSize);
        if (cursor != null && !cursor.isBlank()) {
            if (cursor.length() > 512) throw new IllegalArgumentException("Invalid log cursor");
            String[] parts;
            try { parts = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8).split(":", -1); }
            catch (IllegalArgumentException failure) { throw new IllegalArgumentException("Invalid log cursor"); }
            if (parts.length != 4) throw new IllegalArgumentException("Invalid log cursor");
            try {
                oldSize = Long.parseLong(parts[1]); currentSize = Long.parseLong(parts[2]); end = Long.parseLong(parts[3]);
                if (oldSize < 0 || currentSize < 0 || end < 0 || end > Math.addExact(oldSize, currentSize))
                    throw new IllegalArgumentException("Invalid log cursor");
            } catch (NumberFormatException | ArithmeticException failure) { throw new IllegalArgumentException("Invalid log cursor"); }
            if (!identity.equals(parts[0]) || oldSize > old.size || currentSize > current.size) return reset(source);
        }
        long start = Math.max(0, end - PAGE_BYTES);
        ByteBuffer bytes = ByteBuffer.allocate((int) (end - start));
        readRange(old, start, Math.min(end, oldSize), bytes);
        readRange(current, Math.max(0, start - oldSize), end - oldSize, bytes);
        if (bytes.hasRemaining() || !same(old, state(old.path)) || !same(current, state(current.path))) return reset(source);
        byte[] data = bytes.array();
        // Leading UTF-8 continuation bytes belong to the next older page.
        int skip = 0;
        while (skip < data.length && (data[skip] & 0xc0) == 0x80) skip++;
        start += skip;
        String text = new String(data, skip, data.length - skip, StandardCharsets.UTF_8);
        // Keep ordinary multi-line records intact between pages. Oversized/unstructured records
        // use explicitly partial chunks, still bounded by PAGE_BYTES.
        var boundary = HEADER.matcher(text);
        if (start > 0 && boundary.find() && boundary.start() > 0) {
            int offset = boundary.start();
            start += text.substring(0, offset).getBytes(StandardCharsets.UTF_8).length;
            text = text.substring(offset);
        }
        var matcher = HEADER.matcher(text);
        List<Entry> entries = new ArrayList<>();
        int position = 0;
        String module = null, level = null, time = null;
        boolean partial = start > 0;
        while (matcher.find()) {
            if (matcher.start() > position) entries.add(new Entry(text.substring(position, matcher.start()), module, level, time, partial));
            position = matcher.start(); module = matcher.group(3); level = matcher.group(2); time = matcher.group(1); partial = false;
        }
        if (position < text.length()) entries.add(new Entry(text.substring(position), module, level, time,
            partial || !text.endsWith("\n\n")));
        Collections.reverse(entries);
        String lowerNeedle = needle.toLowerCase(Locale.ROOT);
        entries.removeIf(entry -> !entry.text.toLowerCase(Locale.ROOT).contains(lowerNeedle));
        String next = start == 0 ? null : Base64.getUrlEncoder().withoutPadding().encodeToString(
            (identity + ":" + oldSize + ":" + currentSize + ":" + start).getBytes(StandardCharsets.UTF_8));
        return new Page(source, List.copyOf(entries), next, false, data.length, RETENTION);
    }

    private static Page reset(String source) { return new Page(source, List.of(), null, true, 0, RETENTION); }
    private static Path rotation(Path path) { return path.resolveSibling(path.getFileName() + ".1"); }
    private static boolean same(FileState before, FileState after) { return before.identity.equals(after.identity) && after.size >= before.size; }
    private static FileState state(Path path) throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile()) throw new IOException("Registered log is not a regular file");
            return new FileState(path, attrs.size(), attrs.fileKey() + "/" + attrs.creationTime());
        } catch (NoSuchFileException missing) { return new FileState(path, 0, "missing"); }
    }
    private static void readRange(FileState file, long start, long end, ByteBuffer output) throws IOException {
        if (end <= start) return;
        try (SeekableByteChannel channel = Files.newByteChannel(file.path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            channel.position(start);
            int limit = output.limit();
            output.limit(output.position() + (int) (end - start));
            while (output.hasRemaining() && channel.read(output) > 0) { /* bounded by page byte budget */ }
            output.limit(limit);
        } catch (NoSuchFileException rotated) { /* remaining bytes signal reset */ }
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
}
