package cn.mcxyd.hundredfloor.game;

import cn.mcxyd.hundredfloor.game.model.ArenaBounds;
import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import cn.mcxyd.hundredfloor.game.model.ArenaPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArenaValidatorTest {

    private final ArenaValidator validator = new ArenaValidator();

    @Test
    void acceptsCompleteDescendingCourse() {
        ArenaDefinition arena = arena(List.of(point(90), point(80), point(70)));

        assertTrue(validator.validate(arena, 3).valid());
    }

    @Test
    void rejectsWrongFloorCountAndAscendingMarkers() {
        assertFalse(validator.validate(arena(List.of(point(90), point(80))), 3).valid());
        assertFalse(validator.validate(arena(List.of(point(90), point(95), point(70))), 3).valid());
    }

    @Test
    void rejectsPointsFromAnotherWorldOrOutsideBounds() {
        ArenaDefinition otherWorld = new ArenaDefinition("summer", point(100), point(95),
                new ArenaPoint("nether", 0, 60, 0, 0, 0),
                new ArenaBounds("world", -20, 50, -20, 20, 110, 20),
                List.of(point(90), point(80), point(70)));
        ArenaDefinition outside = new ArenaDefinition("summer", point(100), point(95), point(60),
                new ArenaBounds("world", -5, 50, -5, 5, 110, 5),
                List.of(point(90), new ArenaPoint("world", 8, 80, 0, 0, 0), point(70)));

        assertFalse(validator.validate(otherWorld, 3).valid());
        assertFalse(validator.validate(outside, 3).valid());
    }

    @Test
    void rejectsWaitingSpawnOutsideBounds() {
        ArenaDefinition outsideWaiting = new ArenaDefinition("summer", new ArenaPoint("world", 30, 100, 0, 0, 0), point(95), point(60),
                new ArenaBounds("world", -20, 50, -20, 20, 110, 20),
                List.of(point(90), point(80), point(70)));

        assertFalse(validator.validate(outsideWaiting, 3).valid());
    }

    private static ArenaDefinition arena(List<ArenaPoint> floors) {
        return new ArenaDefinition("summer", point(100), point(95), point(60),
                new ArenaBounds("world", -20, 50, -20, 20, 110, 20), floors);
    }

    private static ArenaPoint point(double y) {
        return new ArenaPoint("world", 0, y, 0, 0, 0);
    }
}
