package cn.mcxyd.hundredfloor.game.generator;

import java.util.Objects;

/** Immutable block instruction. Bukkit objects are deliberately not retained in the plan. */
public record BlockPlacement(int x, int y, int z, String materialName) {

    public BlockPlacement {
        Objects.requireNonNull(materialName, "materialName");
        if (materialName.isBlank()) {
            throw new IllegalArgumentException("materialName must not be blank");
        }
    }
}
