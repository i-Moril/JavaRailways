package work.art1st.javarailways.bukkit.api.model;

import org.bukkit.Material;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Complete item appearance. Null model/CMD/brightness removes that override; null brightness means
 * natural client light, not fixed darkness. The host checks key syntax, not client asset existence.
 */
public record ItemAppearance(Material material, String itemModelKey, Integer customModelData, Brightness brightness) {
    private static final Pattern MODEL_KEY = Pattern.compile("[a-z0-9._-]+:[a-z0-9/._-]+");

    public ItemAppearance {
        Objects.requireNonNull(material, "material");
        if (material.isLegacy() || (material != Material.AIR && !material.isItem())) {
            throw new IllegalArgumentException("Material is not a current ordinary model item: " + material);
        }
        if (itemModelKey != null && !MODEL_KEY.matcher(itemModelKey).matches()) {
            throw new IllegalArgumentException("Item model key must be a full namespaced resource key: " + itemModelKey);
        }
        if (customModelData != null &&
            (customModelData < 0 || (double) customModelData.floatValue() != customModelData.doubleValue())) {
            throw new IllegalArgumentException("CustomModelData must be non-negative and exactly representable as float");
        }
    }

    public record Brightness(int block, int sky) {
        public Brightness {
            if (block < 0 || block > 15 || sky < 0 || sky > 15) {
                throw new IllegalArgumentException("Item brightness must be between 0 and 15");
            }
        }
    }
}
