package dev.twme.sculpt.util;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashingTest {

    @Test
    void sha256OfStringsIsDeterministic() {
        String a = Hashing.sha256OfStrings(List.of("hello", "world"));
        String b = Hashing.sha256OfStrings(List.of("hello", "world"));
        assertEquals(a, b);
    }

    @Test
    void sha256OfStringsIsOrderSensitive() {
        String ab = Hashing.sha256OfStrings(List.of("a", "b"));
        String ba = Hashing.sha256OfStrings(List.of("b", "a"));
        assertNotEquals(ab, ba);
    }

    @Test
    void sha256OfStringsProduces64HexChars() {
        String h = Hashing.sha256OfStrings(List.of("x"));
        assertEquals(64, h.length());
        assertTrue(h.matches("[0-9a-f]{64}"));
    }

    @Test
    void sha256OfBytesMatchesKnownVector() {
        // SHA-256 of "" is e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855
        String h = Hashing.sha256OfBytes(new byte[0]);
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", h);
    }

    @Test
    void sha256OfBytesMatchesKnownVectorForAbc() {
        // SHA-256 of "abc" is ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        String h = Hashing.sha256OfBytes("abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", h);
    }

    @Test
    void sha256OfImageIsDeterministic() {
        BufferedImage img = new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0xFF000000);
        img.setRGB(1, 1, 0xFFFFFFFF);
        String a = Hashing.sha256OfImage(img);
        String b = Hashing.sha256OfImage(img);
        assertEquals(a, b);
        assertEquals(64, a.length());
    }

    @Test
    void sha256OfImageChangesWithPixels() {
        BufferedImage a = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        BufferedImage b = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        a.setRGB(0, 0, 0xFFFF0000);
        b.setRGB(0, 0, 0xFF00FF00);
        assertNotEquals(Hashing.sha256OfImage(a), Hashing.sha256OfImage(b));
    }
}
