package dev.twme.sculpt.skin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.mineskin.QueueOptions;

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

    @Test
    void queueOptionsAreFixedRatherThanGrantRefreshing() {
        final QueueOptions options = SkinUploader.queueOptions();

        // AutoGenerateQueueOptions re-reads the account's grants over the
        // network whenever its values are read, which is what logged a stack
        // trace for a timeout that only affected queue sizing. Returning the
        // final QueueOptions type rules it out at compile time; this pins the
        // concrete type so a future change to the factory is visible here.
        assertEquals(QueueOptions.class, options.getClass());
        assertEquals(1000, options.intervalMillis());
        assertEquals(2, options.concurrency());

        // Reading the values must stay cheap: the auto options would issue a
        // request here once five minutes had passed.
        for (int i = 0; i < 100; i++) {
            assertEquals(1000, options.intervalMillis());
            assertEquals(2, options.concurrency());
        }
    }
}
