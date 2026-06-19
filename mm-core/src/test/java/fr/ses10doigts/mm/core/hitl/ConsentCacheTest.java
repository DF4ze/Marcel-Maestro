package fr.ses10doigts.mm.core.hitl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ConsentCacheTest {

    @Test
    void allowOnce_pasCache() {
        ConsentCache cache = new ConsentCache();

        cache.record("tool_a", ConsentDecision.ALLOW_ONCE);

        assertTrue(cache.lookup("tool_a").isEmpty());
    }

    @Test
    void allowSession_cache() {
        ConsentCache cache = new ConsentCache();

        cache.record("tool_a", ConsentDecision.ALLOW_SESSION);

        assertEquals(ConsentDecision.ALLOW_SESSION, cache.lookup("tool_a").orElse(null));
    }

    @Test
    void allowProject_cacheComeSession() {
        ConsentCache cache = new ConsentCache();

        cache.record("tool_a", ConsentDecision.ALLOW_PROJECT);

        assertEquals(ConsentDecision.ALLOW_PROJECT, cache.lookup("tool_a").orElse(null));
    }

    @Test
    void allowAlways_cacheComeSession() {
        ConsentCache cache = new ConsentCache();

        cache.record("tool_a", ConsentDecision.ALLOW_ALWAYS);

        assertEquals(ConsentDecision.ALLOW_ALWAYS, cache.lookup("tool_a").orElse(null));
    }

    @Test
    void deny_pasCache() {
        ConsentCache cache = new ConsentCache();

        cache.record("tool_a", ConsentDecision.DENY);

        assertTrue(cache.lookup("tool_a").isEmpty());
    }

    @Test
    void clearSession_videLeTout() {
        ConsentCache cache = new ConsentCache();
        cache.record("tool_a", ConsentDecision.ALLOW_SESSION);
        cache.record("tool_b", ConsentDecision.ALLOW_ALWAYS);

        cache.clearSession();

        assertTrue(cache.lookup("tool_a").isEmpty());
        assertTrue(cache.lookup("tool_b").isEmpty());
    }

    @Test
    void lookupOutilInconnu_vide() {
        assertTrue(new ConsentCache().lookup("inexistant").isEmpty());
    }

    @Test
    void lookupNull_vide() {
        assertTrue(new ConsentCache().lookup(null).isEmpty());
    }

    @Test
    void recordNull_pasDeNPE() {
        ConsentCache cache = new ConsentCache();
        cache.record(null, ConsentDecision.ALLOW_SESSION);
        cache.record("tool_a", null);
        assertEquals(0, cache.size());
    }
}
