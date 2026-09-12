package cn.mcxyd.hundredfloor.game.model;

import java.util.Objects;

public record ArenaBounds(
        String world,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ
) {

    public ArenaBounds {
        Objects.requireNonNull(world, "world");
        if (world.isBlank()
                || !Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                || minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("invalid arena bounds");
        }
    }

    public boolean contains(ArenaPoint point) {
        return point != null && world.equals(point.world())
                && point.x() >= minX && point.x() <= maxX
                && point.y() >= minY && point.y() <= maxY
                && point.z() >= minZ && point.z() <= maxZ;
    }

    public boolean contains(String worldName, double x, double y, double z) {
        return world.equals(worldName)
                && x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }
}
