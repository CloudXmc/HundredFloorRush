package cn.mcxyd.hundredfloor.game;

import cn.mcxyd.hundredfloor.game.model.ArenaPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FloorProgressCalculatorTest {

    private final FloorProgressCalculator calculator = new FloorProgressCalculator();
    private final List<ArenaPoint> gates = List.of(
            point(90, 0, 0),
            point(80, 6, 0),
            point(70, 6, 6)
    );

    @Test
    void detectsGateAtInterpolatedCrossingPoint() {
        MovementPoint previous = new MovementPoint(-2, 94, 0);
        MovementPoint current = new MovementPoint(2, 86, 0);

        assertEquals(1, calculator.advance(gates, 0, previous, current, 1.1));
    }

    @Test
    void doesNotCreditAPlayerWhoFallsOutsideTheGate() {
        MovementPoint previous = new MovementPoint(10, 94, 0);
        MovementPoint current = new MovementPoint(10, 86, 0);

        assertEquals(0, calculator.advance(gates, 0, previous, current, 2.0));
    }

    @Test
    void creditsMultipleAlignedGatesDuringOneLargeMovement() {
        List<ArenaPoint> aligned = List.of(point(90, 0, 0), point(80, 0, 0), point(70, 0, 0));

        assertEquals(3, calculator.advance(aligned, 0,
                new MovementPoint(0, 95, 0), new MovementPoint(0, 65, 0), 1.5));
    }

    @Test
    void cannotSkipTheNextRequiredGate() {
        MovementPoint previous = new MovementPoint(6, 94, 0);
        MovementPoint current = new MovementPoint(6, 76, 0);

        assertEquals(0, calculator.advance(gates, 0, previous, current, 1.5));
    }

    @Test
    void ignoresNonFiniteMovementOrRadius() {
        assertEquals(0, calculator.advance(gates, 0,
                new MovementPoint(0, Double.NaN, 0), new MovementPoint(0, 80, 0), 1.5));
        assertEquals(0, calculator.advance(gates, 0,
                new MovementPoint(0, 95, 0), new MovementPoint(0, 65, 0), -1));
    }

    @Test
    void ignoresOverflowingVerticalInterpolation() {
        List<ArenaPoint> extreme = List.of(point(-Double.MAX_VALUE / 2, 0, 0));

        assertEquals(0, calculator.advance(extreme, 0,
                new MovementPoint(0, Double.MAX_VALUE, 0),
                new MovementPoint(0, -Double.MAX_VALUE, 0), 1.5));
    }

    private static ArenaPoint point(double y, double x, double z) {
        return new ArenaPoint("world", x, y, z, 0, 0);
    }
}
