package dev.twme.sculpt;

import java.util.Arrays;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.junit.jupiter.api.Test;

import dev.twme.sculpt.gui.HeadBrowserListener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SculptListenerArchitectureTest {

    @Test
    void mainPluginClassDoesNotOwnEventHandlers() {
        assertFalse(Listener.class.isAssignableFrom(Sculpt.class));
        assertFalse(hasEventHandler(Sculpt.class));
    }

    @Test
    void dedicatedClassesOwnLifecycleAndGuiEvents() {
        assertListenerWithHandlers(SculptChunkListener.class);
        assertListenerWithHandlers(SculptPlayerListener.class);
        assertListenerWithHandlers(HeadBrowserListener.class);
    }

    private static void assertListenerWithHandlers(final Class<?> type) {
        assertTrue(Listener.class.isAssignableFrom(type));
        assertTrue(hasEventHandler(type));
    }

    private static boolean hasEventHandler(final Class<?> type) {
        return Arrays.stream(type.getDeclaredMethods())
            .anyMatch(method -> method.isAnnotationPresent(EventHandler.class));
    }
}
