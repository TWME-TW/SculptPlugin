package dev.twme.sculpt.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SkinUploaderTest {

    @Test
    void blankApiUrlUsesOfficialEndpoint() {
        assertEquals(SkinUploader.DEFAULT_API_URL,
                SkinUploader.normalizeApiUrl(null));
        assertEquals(SkinUploader.DEFAULT_API_URL,
                SkinUploader.normalizeApiUrl("  "));
    }

    @Test
    void apiUrlSupportsTrustedProxyPathsAndRemovesTrailingSlashes() {
        assertEquals("https://skins.example.test/mineskin",
                SkinUploader.normalizeApiUrl(
                        " https://skins.example.test/mineskin/// "));
        assertEquals("http://127.0.0.1:8080",
                SkinUploader.normalizeApiUrl("http://127.0.0.1:8080/"));
    }

    @Test
    void apiUrlRejectsUnsafeOrNonBaseUrls() {
        assertThrows(IllegalArgumentException.class,
                () -> SkinUploader.normalizeApiUrl("/relative"));
        assertThrows(IllegalArgumentException.class,
                () -> SkinUploader.normalizeApiUrl("ftp://skins.example.test"));
        assertThrows(IllegalArgumentException.class,
                () -> SkinUploader.normalizeApiUrl(
                        "https://user:pass@skins.example.test"));
        assertThrows(IllegalArgumentException.class,
                () -> SkinUploader.normalizeApiUrl(
                        "https://skins.example.test?token=secret"));
        assertThrows(IllegalArgumentException.class,
                () -> SkinUploader.normalizeApiUrl(
                        "https://skins.example.test#fragment"));
    }
}
