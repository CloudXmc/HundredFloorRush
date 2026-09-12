package cn.mcxyd.hundredfloor.game.model;

import java.util.Objects;

public record ArenaPoint(String world, double x, double y, double z, float yaw, float pitch) {

    public ArenaPoint {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()
                || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("invalid arena point");
        }
    }
}
