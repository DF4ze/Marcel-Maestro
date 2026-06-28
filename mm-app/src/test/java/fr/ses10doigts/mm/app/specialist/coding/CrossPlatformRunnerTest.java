package fr.ses10doigts.mm.app.specialist.coding;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires rapides de {@link CrossPlatformRunner}.
 */
class CrossPlatformRunnerTest {

    private final CrossPlatformRunner runner = new CrossPlatformRunner();

    @Test
    @DisplayName("Resout un binaire standard disponible sur l OS courant")
    void resolveBinary_withJava_returnsResolvedPath() {
        assertThat(runner.resolveBinary("java")).isPresent();
    }

    @Test
    @DisplayName("Retourne vide si le binaire est introuvable")
    void resolveBinary_withUnknownBinary_returnsEmpty() {
        assertThat(runner.resolveBinary("binary-that-does-not-exist-mm")).isEmpty();
    }
}
