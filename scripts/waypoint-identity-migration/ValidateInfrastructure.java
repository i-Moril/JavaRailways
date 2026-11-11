import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.ByteArrayInputStream;
import java.util.zip.GZIPInputStream;

/** Offline validation through the actual current codecs, without Bukkit startup or writes. */
public final class ValidateInfrastructure {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ValidateInfrastructure <candidate-worlds-directory>");
        }
        Class<?> codecClass = Class.forName(
            "work.art1st.javarailways.bukkit.persistence.SplitWorldSnapshotCodec");
        Object codec = codecClass.getField("INSTANCE").get(null);
        var decode = codecClass.getMethod("decodeInfrastructure", String.class);
        int files = 0;
        try (var paths = Files.list(Path.of(args[0]))) {
            for (Path file : paths.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("migration-report.json") && !Files.isSymbolicLink(file) && Files.isRegularFile(file)) {
                    continue;
                }
                if (!name.matches(".+-infrastructure\\.json(?:\\.bak)?") ||
                        Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("Unexpected candidate file: " + name);
                }
                byte[] raw = Files.readAllBytes(file);
                if (raw.length >= 2 && raw[0] == (byte) 0x1f && raw[1] == (byte) 0x8b) {
                    try (var gzip = new GZIPInputStream(new ByteArrayInputStream(raw))) {
                        raw = gzip.readAllBytes();
                    }
                }
                try {
                    Object infrastructure = decode.invoke(codec, new String(raw, StandardCharsets.UTF_8));
                    for (String part : new String[] {"core", "basic", "simpleAto"}) {
                        infrastructure.getClass().getMethod(part).invoke(infrastructure);
                    }
                } catch (InvocationTargetException failure) {
                    throw new IllegalArgumentException("Current codec rejected " + name, failure.getCause());
                }
                files++;
            }
        }
        if (files == 0) {
            throw new IllegalArgumentException("No infrastructure files; verify the candidate directory");
        }
        System.out.println("CURRENT_INFRASTRUCTURE_OK files=" + files);
    }
}
