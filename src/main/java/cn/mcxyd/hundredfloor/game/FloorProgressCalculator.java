package cn.mcxyd.hundredfloor.game;

import cn.mcxyd.hundredfloor.game.model.ArenaPoint;

import java.util.List;

public final class FloorProgressCalculator {

    public int advance(List<ArenaPoint> gates, int completedFloors, MovementPoint previous,
                       MovementPoint current, double gateRadius) {
        if (gates == null || previous == null || current == null
                || !Double.isFinite(gateRadius) || gateRadius < 0
                || !finite(previous) || !finite(current)) {
            return clamp(completedFloors, gates == null ? 0 : gates.size());
        }
        if (completedFloors < 0 || completedFloors >= gates.size() || current.y() >= previous.y()) {
            return clamp(completedFloors, gates.size());
        }

        int progress = completedFloors;
        while (progress < gates.size()) {
            ArenaPoint gate = gates.get(progress);
            if (gate == null || !Double.isFinite(gate.x()) || !Double.isFinite(gate.y())
                    || !Double.isFinite(gate.z())) {
                break;
            }
            if (previous.y() < gate.y() || current.y() > gate.y()) {
                break;
            }
            // 先单独验证垂直位移；极端但合法的 double 坐标相减可能上溢/下溢，
            // 直接参与插值会产生 NaN/Infinity，进而错误地跳过或授予楼层。
            double verticalDistance = previous.y() - current.y();
            if (!Double.isFinite(verticalDistance) || verticalDistance <= 0.0) {
                break;
            }
            double ratio = (previous.y() - gate.y()) / verticalDistance;
            if (!Double.isFinite(ratio) || ratio < 0.0 || ratio > 1.0) {
                break;
            }
            double crossX = previous.x() + (current.x() - previous.x()) * ratio;
            double crossZ = previous.z() + (current.z() - previous.z()) * ratio;
            if (!Double.isFinite(crossX) || !Double.isFinite(crossZ)) {
                break;
            }
            double deltaX = crossX - gate.x();
            double deltaZ = crossZ - gate.z();
            if (!Double.isFinite(deltaX) || !Double.isFinite(deltaZ)
                    || Math.hypot(deltaX, deltaZ) > gateRadius) {
                break;
            }
            progress++;
        }
        return progress;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(value, size));
    }

    private static boolean finite(MovementPoint point) {
        return Double.isFinite(point.x()) && Double.isFinite(point.y()) && Double.isFinite(point.z());
    }
}
