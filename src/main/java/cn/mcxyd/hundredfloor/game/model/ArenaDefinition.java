package cn.mcxyd.hundredfloor.game.model;

import java.util.List;
import java.util.Objects;

public record ArenaDefinition(
        String name,
        ArenaPoint waitingSpawn,
        ArenaPoint startSpawn,
        ArenaPoint finishPoint,
        ArenaBounds bounds,
        List<ArenaPoint> floorGates
) {

    public ArenaDefinition {
        Objects.requireNonNull(name, "name");
        floorGates = floorGates == null ? List.of() : List.copyOf(floorGates);
    }
}
