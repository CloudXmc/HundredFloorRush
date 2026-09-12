package cn.mcxyd.hundredfloor.game.generator;

import cn.mcxyd.hundredfloor.game.model.ArenaDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProceduralArenaPlanTest {

    @Test
    void createsACompleteHundredFloorCourseWithoutAnExistingMap() {
        ProceduralArenaPlan plan = ProceduralArenaPlan.create("summer", "hfr_summer", 100, 20260911L);
        ArenaDefinition arena = plan.definition();

        assertEquals(100, arena.floorGates().size());
        assertEquals("hfr_summer", arena.waitingSpawn().world());
        assertTrue(arena.startSpawn().y() > arena.floorGates().getFirst().y());
        assertTrue(arena.floorGates().getFirst().y() > arena.floorGates().getLast().y());
        assertTrue(arena.finishPoint().y() < arena.floorGates().getLast().y());
        assertTrue(plan.placements().size() > 20_000);
        assertTrue(arena.bounds().contains(arena.startSpawn()));
        assertTrue(arena.bounds().contains(arena.finishPoint()));
    }

    @Test
    void sameSeedProducesSameCourseAndDifferentSeedChangesRoute() {
        ProceduralArenaPlan first = ProceduralArenaPlan.create("summer", "hfr_summer", 100, 7L);
        ProceduralArenaPlan same = ProceduralArenaPlan.create("summer", "hfr_summer", 100, 7L);
        ProceduralArenaPlan different = ProceduralArenaPlan.create("summer", "hfr_summer", 100, 8L);

        assertEquals(first.definition(), same.definition());
        assertEquals(first.placements(), same.placements());
        assertNotEquals(first.definition().floorGates(), different.definition().floorGates());
    }

    @Test
    void supportsShorterTestCoursesWhileKeepingTheSameRules() {
        ProceduralArenaPlan plan = ProceduralArenaPlan.create("test", "hfr_test", 3, 1L);

        assertEquals(3, plan.definition().floorGates().size());
        assertTrue(plan.placements().stream().anyMatch(placement -> placement.materialName().equals("GOLD_BLOCK")));
    }

    @Test
    void usesColoredStainedGlassForFloorPlatforms() {
        ProceduralArenaPlan plan = ProceduralArenaPlan.create("test", "hfr_test", 5, 1L);

        assertTrue(plan.placements().stream().anyMatch(placement -> placement.materialName().equals("CYAN_STAINED_GLASS")));
        assertTrue(plan.placements().stream().anyMatch(placement -> placement.materialName().equals("LIME_STAINED_GLASS")));
        assertTrue(plan.placements().stream().anyMatch(placement -> placement.materialName().equals("ORANGE_STAINED_GLASS")));
        assertTrue(plan.placements().stream().anyMatch(placement -> placement.materialName().equals("MAGENTA_STAINED_GLASS")));
        assertTrue(plan.placements().stream().anyMatch(placement -> placement.materialName().equals("BLUE_STAINED_GLASS")));
        assertTrue(plan.placements().stream().noneMatch(placement -> placement.materialName().endsWith("_CONCRETE")));
    }

    @Test
    void keepsTheStartDropOpenAndWaitingSpawnOnSolidGround() {
        ProceduralArenaPlan plan = ProceduralArenaPlan.create("summer", "hfr_summer", 100, 1L);
        ArenaDefinition arena = plan.definition();

        assertTrue(plan.placements().stream().noneMatch(block -> block.y() == 302
                && Math.abs(block.x()) <= 1 && Math.abs(block.z()) <= 1));
        assertTrue(plan.placements().stream().anyMatch(block -> block.y() == 302
                && block.x() == (int) Math.floor(arena.waitingSpawn().x())
                && block.z() == (int) Math.floor(arena.waitingSpawn().z())));
    }
}
