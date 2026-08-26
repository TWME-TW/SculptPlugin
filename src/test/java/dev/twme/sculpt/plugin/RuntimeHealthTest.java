package dev.twme.sculpt.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RuntimeHealthTest {

    @Test
    void emptyRegistryWithoutBakerIsDegraded() {
        RuntimeHealth health = RuntimeHealth.evaluate(2, 0, 0, false);
        assertEquals(RuntimeHealth.Status.DEGRADED, health.status());
    }

    @Test
    void indexedConfiguredGridIsReady() {
        RuntimeHealth health = RuntimeHealth.evaluate(2, 42, 50, false);
        assertEquals(RuntimeHealth.Status.READY, health.status());
    }

    @Test
    void runtimeBakerCanServeAnEmptyRegistry() {
        RuntimeHealth health = RuntimeHealth.evaluate(4, 0, 0, true);
        assertEquals(RuntimeHealth.Status.READY, health.status());
    }

    @Test
    void wholeBlockResolutionNeedsNeitherTexturesNorBaker() {
        RuntimeHealth health = RuntimeHealth.evaluate(1, 0, 0, false);
        assertEquals(RuntimeHealth.Status.READY, health.status());
    }

    @Test
    void failureRetainsDiagnostic() {
        RuntimeHealth health = RuntimeHealth.failed(2, new IllegalStateException("broken pack"));
        assertEquals(RuntimeHealth.Status.FAILED, health.status());
        assertEquals("broken pack", health.failure());
    }
}
