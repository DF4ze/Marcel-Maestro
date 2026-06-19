package fr.ses10doigts.mm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

/**
 * Test de pureté de base : mm-core compile et teste SEUL, sans aucun consommateur
 * dans le classpath (litmus SRP, étape 1).
 */
class CoreInfoTest {

    @Test
    void exposeUneIdentiteDeNoyau() {
        assertNotNull(CoreInfo.NAME);
        assertEquals("Marcel Maestro Core", CoreInfo.NAME);
        assertNotNull(CoreInfo.VERSION);
    }
}
