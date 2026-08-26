package dev.twme.sculpt.core;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VariantKeyTest {

    @Test
    void fullKeyMatchesWhenPresent() {
        Set<String> candidates = Set.of("axis=y", "axis=x", "axis=z");
        assertEquals("axis=x", VariantKey.pickMatching("axis=x", candidates));
    }

    @Test
    void dropsNuisancePropertyFirst() {
        // waterlogged=true is the canonical nuisance; an oak_fence on water
        // renders the same as the dry variant. The bakes only have the
        // dry key, so dropping waterlogged should hit.
        Set<String> candidates = Set.of("east=true,north=false,south=false,west=true");
        assertEquals(
                "east=true,north=false,south=false,west=true",
                VariantKey.pickMatching(
                        "east=true,north=false,south=false,waterlogged=true,west=true",
                        candidates));
    }

    @Test
    void dropsMultipleNuisancesProgressively() {
        Set<String> candidates = Set.of("facing=north,lit=false");
        // Both powered=true AND waterlogged=true nuisance; only "facing=north,lit=false" is baked.
        String result = VariantKey.pickMatching(
                "facing=north,lit=false,powered=true,waterlogged=true", candidates);
        assertEquals("facing=north,lit=false", result);
    }

    @Test
    void dropsRemainingPropertyWhenNuisanceDropIsNotEnough() {
        // No waterlogged-style nuisance here, but "shape=inner" isn't baked
        // — we have to drop it to hit "facing=north".
        Set<String> candidates = Set.of("facing=north");
        String result = VariantKey.pickMatching(
                "facing=north,shape=inner", candidates);
        assertEquals("facing=north", result);
    }

    @Test
    void returnsEmptyStringOnNoMatch() {
        Set<String> candidates = Set.of("axis=y");
        assertEquals("", VariantKey.pickMatching("type=something_else", candidates));
    }

    @Test
    void emptyCandidatesReturnEmpty() {
        assertEquals("", VariantKey.pickMatching("axis=y", Set.of()));
    }

    @Test
    void nullFullKeyReturnsEmpty() {
        assertEquals("", VariantKey.pickMatching(null, Set.of("axis=y")));
    }

    @Test
    void dropsLaterAlphabeticalPropertiesFirst() {
        // "shape" > "facing" alphabetically, so "shape" is dropped first
        // when both need to go.
        Set<String> candidates = Set.of("facing=north");
        String result = VariantKey.pickMatching(
                "facing=north,shape=inner,waterlogged=true", candidates);
        assertEquals("facing=north", result);
    }

    @Test
    void parseAndSerializeRoundtrip() {
        String key = "axis=y,waterlogged=true";
        TreeMap<String, String> kv = VariantKey.parseKey(key);
        assertEquals("axis=y,waterlogged=true", VariantKey.serialize(kv));
    }

    @Test
    void parseKeyIsAlphabeticallySorted() {
        TreeMap<String, String> kv = VariantKey.parseKey("z=1,a=2,m=3");
        assertEquals("a=2,m=3,z=1", VariantKey.serialize(kv));
    }

    @Test
    void parseKeyEmptyInputYieldsEmptyMap() {
        assertTrue(VariantKey.parseKey("").isEmpty());
        assertTrue(VariantKey.parseKey(null).isEmpty());
        assertEquals("", VariantKey.serialize(new TreeMap<>()));
    }

    @Test
    void emptyFullKeyMatchesEmptyCandidate() {
        // A block with no properties ("minecraft:air" etc.) should match
        // the empty candidate key.
        assertEquals("", VariantKey.pickMatching("", Set.of("")));
    }
}
