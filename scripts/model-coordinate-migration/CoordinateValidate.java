import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** One-time, offline validation through the installed current readers; never starts Bukkit. */
public final class CoordinateValidate {
    private static final String HOST = "work.art1st.javarailways.bukkit.";
    private static final String SIGNALS = "work.art1st.javarailways.simplesignals.";

    private static Object singleton(String name) throws Exception {
        return Class.forName(name).getField("INSTANCE").get(null);
    }

    private static Object invoke(Object receiver, String name, Class<?>[] types, Object... args) throws Exception {
        try {
            Method method = receiver.getClass().getDeclaredMethod(name, types);
            method.setAccessible(true);
            return method.invoke(receiver, args);
        } catch (InvocationTargetException failure) {
            throw new IllegalStateException("Current reader rejected candidate: " + name, failure.getCause());
        }
    }

    public static void main(String[] args) throws Exception {
        try { validate(args); } catch (Throwable failure) {
            try (var out = new java.io.PrintWriter("coordinate-reader-failure.txt")) { failure.printStackTrace(out); }
            System.exit(1);
        }
    }

    private static void validate(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2 || (args.length == 2 && !args[1].equals("--defer-render-reader")))
            throw new IllegalArgumentException("Usage: CoordinateValidate <candidate-server-root> [--defer-render-reader]");
        boolean deferRender = args.length == 2;
        Path root = Path.of(args[0]).toRealPath();
        // Same real Paper registry bootstrap as the repository's model tests.
        Class.forName("net.minecraft.SharedConstants").getMethod("tryDetectVersion").invoke(null);
        Class.forName("net.minecraft.server.Bootstrap").getMethod("bootStrap").invoke(null);
        int packages = 0, snapshots = 0, definitions = 0;
        Path host = root.resolve("plugins/JavaRailways");
        Path signals = root.resolve("plugins/SimpleSignals");
        Class<?> loader = Class.forName(HOST + "config.model.PackageLoader");
        try (var paths = Files.list(host.resolve("rails"))) {
            for (Path directory : paths.filter(Files::isDirectory).toList()) {
                if (!Files.exists(directory.resolve("manifest.yml"))) continue;
                Object packageLoader = loader.getConstructor(File.class).newInstance(directory.toFile());
                invoke(packageLoader, "loadRailPackage", new Class<?>[0]);
                packages++;
            }
        }
        Object renderStore = singleton(HOST + "persistence.RailRenderMetaStore");
        try (var paths = Files.list(host.resolve("worlds"))) {
            for (Path file : paths.filter(path -> path.getFileName().toString().matches(".*-rail-render\\.json(?:\\.bak)?")).toList()) {
                if (!deferRender) {
                    Object records = invoke(renderStore, "parseFile", new Class<?>[]{File.class}, file.toFile());
                    snapshots += ((List<?>) records).size();
                }
            }
        }
        Object signalCodec = singleton(SIGNALS + "persistence.SimpleSignalsWorldDefinitionsCodec");
        try (var paths = Files.list(signals.resolve("worlds"))) {
            for (Path file : paths.filter(path -> path.getFileName().toString().matches(".*-definitions\\.json(?:\\.bak)?")).toList()) {
                UUID world = UUID.fromString(file.getFileName().toString().substring(0, 36));
                invoke(signalCodec, "decode", new Class<?>[]{String.class, UUID.class}, Files.readString(file), world);
                definitions++;
            }
        }
        invoke(singleton(SIGNALS + "signalpack.SignalPackLoader"), "load", new Class<?>[]{Path.class}, signals.resolve("signal-packs"));
        Path models = signals.resolve("models");
        if (Files.isDirectory(models)) {
            try (var paths = Files.list(models)) {
                for (Path directory : paths.filter(Files::isDirectory).toList()) {
                    if (!Files.exists(directory.resolve("manifest.yml"))) continue;
                    invoke(singleton(SIGNALS + "presentation.ModelPackageLoader"), "load", new Class<?>[]{Path.class}, directory);
                }
            }
        }
        System.out.println("CURRENT_READERS_OK railPackages=" + packages + " renderedRails=" + (deferRender ? "DEFERRED_TO_REAL_STARTUP" : snapshots) + " definitionFiles=" + definitions);
    }
}
