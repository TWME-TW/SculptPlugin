package dev.twme.sculpt.blueprint;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BlueprintNetworkBoundaryTest {

    @Test
    void acceptsOnlyHttpsDefaultPortWithoutUserInfo() {
        assertEquals("example.com",
            BlueprintManager.normalizedHttpsHost(URI.create("https://EXAMPLE.com/share")));
        assertNull(BlueprintManager.normalizedHttpsHost(URI.create("http://example.com/share")));
        assertNull(BlueprintManager.normalizedHttpsHost(URI.create("https://user@example.com/share")));
        assertNull(BlueprintManager.normalizedHttpsHost(URI.create("https://example.com:8443/share")));
    }

    @Test
    void parsesEncodedTokenWithoutTreatingWholeQueryAsToken() {
        URI uri = URI.create("https://example.com/blueprint/id?other=x&token=a%2Bb%3D");
        assertEquals("a+b=", BlueprintManager.queryParameter(uri, "token"));
    }

    @Test
    void extractsIdsFromShareAndApiPaths() {
        UUID id = UUID.randomUUID();
        assertEquals(id, BlueprintManager.extractBlueprintId("/blueprint/" + id));
        assertEquals(id, BlueprintManager.extractBlueprintId("/api/blueprints/" + id + "/download"));
    }

    @Test
    void buildsSculptWebCollectionAndItemUrisWithoutCarryingQueries() {
        UUID id = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");

        URI collection = BlueprintManager.blueprintCollectionUri(
            "https://Sculpt-Web.twme.workers.dev/api/");

        assertEquals(
            URI.create("https://sculpt-web.twme.workers.dev/api/blueprints"), collection);
        assertEquals(
            URI.create("https://sculpt-web.twme.workers.dev/api/blueprints/" + id),
            BlueprintManager.blueprintItemUri(collection.toString(), id));
        assertNull(BlueprintManager.blueprintCollectionUri("http://example.com/api"));
        assertNull(BlueprintManager.blueprintItemUri(
            "https://example.com/api/blueprints?redirect=bad", id));
    }

    @Test
    void cappedReaderStopsOversizedBody() throws Exception {
        assertArrayEquals(new byte[]{1, 2, 3}, BlueprintManager.readCapped(
            new ByteArrayInputStream(new byte[]{1, 2, 3}), 3));
        assertThrows(IOException.class, () -> BlueprintManager.readCapped(
            new ByteArrayInputStream(new byte[]{1, 2, 3, 4}), 3));
    }

    @Test
    void publishMetadataUsesSubmitterFieldsOnly() {
        Map<String, Object> payload = new LinkedHashMap<>();
        UUID uuid = UUID.fromString("00000000-0000-4000-8000-000000000001");

        BlueprintManager.addSubmitterMetadata(payload, uuid, "PlayerNick");

        assertEquals("PlayerNick", payload.get("submitterName"));
        assertEquals(uuid.toString(), payload.get("submitterUUID"));
        assertTrue(payload.containsKey("submitterName"));
        assertFalse(payload.containsKey("authorName"));
        assertFalse(payload.containsKey("authorUUID"));
    }
}
