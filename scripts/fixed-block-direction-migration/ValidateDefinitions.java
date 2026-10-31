import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Offline entry to the actual current codec. No plugin or Paper bootstrap, and no writes. */
public final class ValidateDefinitions {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: ValidateDefinitions <candidate-worlds-directory>");
        }
        Class<?> codecClass = Class.forName(
            "work.art1st.javarailways.simplesignals.persistence.SimpleSignalsWorldDefinitionsCodec");
        Object codec = codecClass.getField("INSTANCE").get(null);
        var decode = codecClass.getMethod("decode", String.class, UUID.class);
        int files = 0, blocks = 0, signals = 0;
        try (var paths = Files.list(Path.of(args[0]))) {
            for (Path file : paths.sorted().toList()) {
                String name = file.getFileName().toString();
                if (name.equals("migration-report.json") && !Files.isSymbolicLink(file) && Files.isRegularFile(file)) {
                    continue; // Candidate metadata is never passed to the definitions decoder.
                }
                if (!name.matches("[0-9a-f-]{36}-definitions\\.json(?:\\.bak)?") ||
                    Files.isSymbolicLink(file) || !Files.isRegularFile(file)) {
                    throw new IllegalArgumentException("Unexpected candidate file: " + name);
                }
                UUID world = UUID.fromString(name.substring(0, 36));
                if (!name.startsWith(world.toString())) {
                    throw new IllegalArgumentException("Non-canonical filename UUID: " + name);
                }
                Object definitions;
                try {
                    definitions = decode.invoke(codec, Files.readString(file), world);
                } catch (InvocationTargetException failure) {
                    throw new IllegalArgumentException("Current codec rejected " + name, failure.getCause());
                }
                Class<?> type = definitions.getClass();
                blocks += ((List<?>) type.getMethod("getFixedBlocks").invoke(definitions)).size();
                signals += ((List<?>) type.getMethod("getFixedBlockSignals").invoke(definitions)).size();
                files++;
            }
        }
        if (files == 0) {
            throw new IllegalArgumentException("No definition sidecars; verify the candidate directory");
        }
        System.out.println("CURRENT_DEFINITIONS_OK files=" + files + " blocks=" + blocks + " fixedBlockSignals=" + signals);
    }
}
