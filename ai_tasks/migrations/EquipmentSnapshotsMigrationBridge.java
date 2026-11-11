import java.nio.charset.StandardCharsets;
import kotlin.Unit;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonKt;
import work.art1st.javarailways.bukkit.config.model.SavedTrainPackageSnapshot;
import work.art1st.javarailways.bukkit.config.model.SavedCarModelTree;
import work.art1st.javarailways.bukkit.extension.train.SavedCarRenderState;
import work.art1st.javarailways.bukkit.extension.train.SavedTrainRenderState;
import work.art1st.javarailways.bukkit.persistence.SplitWorldSnapshotCodec;

/** Offline helper compiled against each exact source/candidate JAR, never included in the plugin. */
public final class EquipmentSnapshotsMigrationBridge {
    public static void main(String[] args) throws Exception {
        Json json = JsonKt.Json(Json.Default, builder -> {
            builder.setEncodeDefaults(true);
            builder.setExplicitNulls(false);
            builder.setIgnoreUnknownKeys(false);
            return Unit.INSTANCE;
        });
        String input = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        switch (args[0]) {
            case "package" -> {
                var source = json.decodeFromString(SavedTrainPackageSnapshot.Companion.serializer(), input);
                if (source.getSchemaVersion() != SavedTrainPackageSnapshot.SCHEMA_VERSION) throw new IllegalArgumentException("unsupported package schema");
                var current = SavedTrainPackageSnapshot.Companion.build(source.getPackageName(), source.getActions(),
                    source.getBehavior(), source.getControl(), source.getCarControls());
                System.out.print(json.encodeToString(SavedTrainPackageSnapshot.Companion.serializer(), current));
            }
            case "model" -> {
                var source = json.decodeFromString(SavedCarModelTree.Companion.serializer(), input);
                String hash = SavedCarModelTree.Companion.computeHash(source.getCar(), source.getParts());
                var current = new SavedCarModelTree(hash, source.getCar(), source.getParts());
                System.out.print(json.encodeToString(SavedCarModelTree.Companion.serializer(), current));
            }
            case "runtime-file" -> {
                SplitWorldSnapshotCodec.INSTANCE.decodeRuntime(input);
                System.out.print("{}");
            }
            case "car-ref" -> {
                json.decodeFromString(SavedCarRenderState.Companion.serializer(), input);
                System.out.print("{}");
            }
            case "train-ref" -> {
                json.decodeFromString(SavedTrainRenderState.Companion.serializer(), input);
                System.out.print("{}");
            }
            default -> throw new IllegalArgumentException("unknown offline operation");
        }
    }
}
