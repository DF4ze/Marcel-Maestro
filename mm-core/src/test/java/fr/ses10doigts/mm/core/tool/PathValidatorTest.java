package fr.ses10doigts.mm.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires de {@link PathValidator} : acceptation des chemins valides dans
 * le workspace, rejet des traversals et des chemins absolus hors workspace,
 * et tolerance des parametres non-chemin ou null.
 */
class PathValidatorTest {

    @TempDir
    Path workspace;

    @Test
    void cheminRelatifDansWorkspace_accepte() {
        PathValidator validator = new PathValidator(workspace);

        assertDoesNotThrow(() -> validator.validatePath("src/Main.java"));
    }

    @Test
    void traversalVersParent_rejete() {
        PathValidator validator = new PathValidator(workspace);

        assertThrows(ToolException.class,
                () -> validator.validatePath("../../etc/passwd"));
    }

    @Test
    void cheminAbsoluHorsWorkspace_rejete() {
        PathValidator validator = new PathValidator(workspace);

        assertThrows(ToolException.class,
                () -> validator.validatePath("/etc/passwd"));
    }

    @Test
    void parametreNonChemin_ignore() {
        PathValidator validator = new PathValidator(workspace);
        Map<String, Object> params = Map.of(
                "message", "hello world",
                "count", 42);

        assertDoesNotThrow(() -> validator.validateParams(params));
    }

    @Test
    void parametresNull_gereSansErreur() {
        PathValidator validator = new PathValidator(workspace);

        assertDoesNotThrow(() -> validator.validateParams(null));
    }

    @Test
    void validateParamsAvecCheminValide_accepte() {
        PathValidator validator = new PathValidator(workspace);
        Map<String, Object> params = Map.of("file", "workspace/src/Main.java");

        assertDoesNotThrow(() -> validator.validateParams(params));
    }

    @Test
    void validateParamsAvecCheminTraversal_rejete() {
        PathValidator validator = new PathValidator(workspace);
        Map<String, Object> params = Map.of("file", "../../etc/shadow");

        assertThrows(ToolException.class, () -> validator.validateParams(params));
    }
}
