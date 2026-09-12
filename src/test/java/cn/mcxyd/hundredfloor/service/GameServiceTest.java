package cn.mcxyd.hundredfloor.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameServiceTest {

    @Test
    void convertsFiveSecondDisplayToOneHundredTicks() {
        assertEquals(100L, GameService.resultDisplayDelayTicks(5));
    }

    @Test
    void clampsNonPositiveDisplayDelayToOneTick() {
        assertEquals(1L, GameService.resultDisplayDelayTicks(0));
        assertEquals(1L, GameService.resultDisplayDelayTicks(-1));
    }
}
