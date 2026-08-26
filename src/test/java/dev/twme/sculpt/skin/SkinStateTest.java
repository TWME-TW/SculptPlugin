package dev.twme.sculpt.skin;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkinStateTest {

    @Test
    void hasFourStates() {
        // Lifecycle: PENDING -> SUBMITTED -> COMPLETED on success,
        // PENDING -> SUBMITTED -> ERRORED on rejection.
        SkinState[] all = SkinState.values();
        assertEquals(4, all.length);
        assertNotNull(SkinState.PENDING);
        assertNotNull(SkinState.SUBMITTED);
        assertNotNull(SkinState.COMPLETED);
        assertNotNull(SkinState.ERRORED);
    }

    @Test
    void valueOfRoundtrips() {
        for (SkinState s : SkinState.values()) {
            assertEquals(s, SkinState.valueOf(s.name()));
        }
    }
}
