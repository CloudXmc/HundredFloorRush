package cn.mcxyd.hundredfloor.game;

import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import cn.mcxyd.hundredfloor.game.model.ArenaPoint;

import java.util.List;

public final class ArenaValidator {

    public ValidationResult validate(ArenaDefinition arena, int requiredFloors) {
        if (arena == null || arena.name() == null || arena.name().isBlank()) {
            return ValidationResult.invalid("竞技场名称为空");
        }
        if (requiredFloors < 1 || requiredFloors > 100) {
            return ValidationResult.invalid("要求的楼层数量必须在 1-100 之间");
        }
        if (arena.waitingSpawn() == null || arena.startSpawn() == null
                || arena.finishPoint() == null || arena.bounds() == null) {
            return ValidationResult.invalid("等待点、起点、终点或边界未设置");
        }
        if (arena.floorGates().size() != requiredFloors) {
            return ValidationResult.invalid("需要恰好标定 " + requiredFloors + " 个下落入口");
        }
        String world = arena.bounds().world();
        if (!sameWorld(world, arena.waitingSpawn(), arena.startSpawn(), arena.finishPoint())) {
            return ValidationResult.invalid("全部坐标必须位于同一世界");
        }
        if (!arena.bounds().contains(arena.waitingSpawn())
                || !arena.bounds().contains(arena.startSpawn())
                || !arena.bounds().contains(arena.finishPoint())) {
            return ValidationResult.invalid("等待点、起点或终点不在赛道边界内");
        }
        List<ArenaPoint> gates = arena.floorGates();
        double previousY = arena.startSpawn().y();
        for (int index = 0; index < gates.size(); index++) {
            ArenaPoint gate = gates.get(index);
            if (!world.equals(gate.world()) || !arena.bounds().contains(gate)) {
                return ValidationResult.invalid("第 " + (index + 1) + " 层入口不在赛道边界内");
            }
            if (gate.y() >= previousY) {
                return ValidationResult.invalid("楼层入口必须按 Y 坐标从高到低添加");
            }
            previousY = gate.y();
        }
        if (arena.finishPoint().y() >= previousY) {
            return ValidationResult.invalid("终点必须低于最后一个楼层入口");
        }
        return ValidationResult.validResult();
    }

    private static boolean sameWorld(String world, ArenaPoint... points) {
        for (ArenaPoint point : points) {
            if (!world.equals(point.world())) {
                return false;
            }
        }
        return true;
    }
}
