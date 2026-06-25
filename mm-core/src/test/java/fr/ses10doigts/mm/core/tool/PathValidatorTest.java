package fr.ses10doigts.mm.core.tool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import fr.ses10doigts.mm.core.agent.AgentContext;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires de {@link PathValidator} : acceptation des chemins valides dans
 * le workspace interne, rejet des traversals, rejet des chemins hors workspace,
 * tolérance des paramètres non-chemin ou null, et comportement avec workspace externe
 * déclaré (ADR-023, E2-M3).
 *
 * <p>{@link WorkspaceRegistry} est stubbé via un lambda — zéro accès DB dans mm-core.</p>
 */
class PathValidatorTest {

    @TempDir
    Path workspace;

    @TempDir
    Path externalWorkspace;

    private static final AgentContext CTX = AgentContext.of("default", "proj-1", "conv-1", "task-1");

    // ──────────────────────────────────────────────────────────────────────────────────
    // Workspace interne — comportement inchangé
    // ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void cheminRelatifDansWorkspace_accepte() {
        PathValidator validator = new PathValidator(workspace);

        assertDoesNotThrow(() -> validator.validatePath("src/Main.java", null));
    }

    @Test
    void traversalVersParent_rejete() {
        PathValidator validator = new PathValidator(workspace);

        assertThrows(ToolException.class,
                () -> validator.validatePath("../../etc/passwd", null));
    }

    @Test
    void cheminAbsoluHorsWorkspace_rejete() {
        PathValidator validator = new PathValidator(workspace);

        assertThrows(ToolException.class,
                () -> validator.validatePath("/etc/passwd", null));
    }

    @Test
    void parametreNonChemin_ignore() {
        PathValidator validator = new PathValidator(workspace);
        Map<String, Object> params = Map.of(
                "message", "hello world",
                "count", 42);

        assertDoesNotThrow(() -> validator.validateParams(params, CTX));
    }

    @Test
    void parametresNull_gereSansErreur() {
        PathValidator validator = new PathValidator(workspace);

        assertDoesNotThrow(() -> validator.validateParams(null, CTX));
    }

    @Test
    void validateParamsAvecCheminValide_accepte() {
        PathValidator validator = new PathValidator(workspace);
        Map<String, Object> params = Map.of("file", "src/Main.java");

        assertDoesNotThrow(() -> validator.validateParams(params, CTX));
    }

    @Test
    void validateParamsAvecCheminTraversal_rejete() {
        PathValidator validator = new PathValidator(workspace);
        Map<String, Object> params = Map.of("file", "../../etc/shadow");

        assertThrows(ToolException.class, () -> validator.validateParams(params, CTX));
    }

    // ──────────────────────────────────────────────────────────────────────────────────
    // Workspace externe déclaré (ADR-023, E2-M3) — WorkspaceRegistry stubbé
    // ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void cheminDansWorkspaceExterneDeclaré_accepte() {
        // Le registre confirme que le chemin appartient à un dossier déclaré
        WorkspaceRegistry registry = (path, projectId) -> path.startsWith(
                externalWorkspace.toAbsolutePath().toString());
        PathValidator validator = new PathValidator(workspace, registry);

        String externalFile = externalWorkspace.resolve("src/Foo.java").toString();

        assertDoesNotThrow(() -> validator.validatePath(externalFile, "proj-1"));
    }

    @Test
    void pathTraversalDansWorkspaceExterne_rejete() {
        // Le registre simule la normalisation : /external/../etc/passwd → /etc/passwd
        // qui n'est PAS sous externalWorkspace, donc retourne false
        WorkspaceRegistry registry = (path, projectId) -> {
            Path normalized = Path.of(path).normalize();
            return normalized.startsWith(externalWorkspace.toAbsolutePath().normalize());
        };
        PathValidator validator = new PathValidator(workspace, registry);

        // Traversal : tente de sortir du dossier externe déclaré
        String traversal = externalWorkspace.resolve("../etc/passwd").toString();

        assertThrows(ToolException.class, () -> validator.validatePath(traversal, "proj-1"));
    }

    @Test
    void sansProjectId_workspaceExterneIgnore() {
        // Sans projectId, le registre ne doit pas être consulté → rejet
        WorkspaceRegistry registry = (path, projectId) -> true; // ne doit pas être appelé
        PathValidator validator = new PathValidator(workspace, registry);

        String externalFile = externalWorkspace.resolve("src/Foo.java").toString();

        assertThrows(ToolException.class, () -> validator.validatePath(externalFile, null));
    }

    @Test
    void sansRegistre_cheminExterneRejete() {
        // Pas de WorkspaceRegistry → seul le workspace interne est autorisé (mode strict)
        PathValidator validator = new PathValidator(workspace);

        String externalFile = externalWorkspace.resolve("src/Foo.java").toString();

        assertThrows(ToolException.class, () -> validator.validatePath(externalFile, "proj-1"));
    }

    // ──────────────────────────────────────────────────────────────────────────────────
    // Mode hitlApproved (E2-M3+) — approbation HITL lève la restriction hors-workspace
    // ──────────────────────────────────────────────────────────────────────────────────

    @Test
    void hitlApprouve_cheminAbsoluNonSysteme_autorise() {
        // Avec hitlApproved=true, un chemin absolu non-système hors workspace doit être accepté
        PathValidator validator = new PathValidator(workspace);

        String externalFile = externalWorkspace.resolve("docs/readme.md")
                .toAbsolutePath().toString();

        assertDoesNotThrow(() -> validator.validatePath(externalFile, null, true),
                "Un chemin absolu non-système doit être autorisé quand HITL a approuvé");
    }

    @Test
    void hitlApprouve_sansProjectId_cheminAbsoluAutorise() {
        // hitlApproved=true lève la restriction même sans projectId
        PathValidator validator = new PathValidator(workspace);

        String externalFile = externalWorkspace.resolve("output/result.txt")
                .toAbsolutePath().toString();

        assertDoesNotThrow(() -> validator.validatePath(externalFile, null, true));
    }

    @Test
    void hitlApprouve_cheminSysteme_rejeteQuandMeme() {
        // Les chemins système sont TOUJOURS rejetés, même avec hitlApproved=true
        PathValidator validator = new PathValidator(workspace);

        String systemPath = platformSystemPath();

        assertThrows(ToolException.class,
                () -> validator.validatePath(systemPath, null, true),
                "Un chemin système doit être rejeté même si HITL a approuvé");
    }

    @Test
    void hitlApprouve_traversalRelatif_rejeteQuandMeme() {
        // Les path traversals relatifs sont TOUJOURS rejetés, même avec hitlApproved=true
        PathValidator validator = new PathValidator(workspace);

        assertThrows(ToolException.class,
                () -> validator.validatePath("../../etc/shadow", null, true),
                "Un traversal relatif doit être rejeté même si HITL a approuvé");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    private static String platformSystemPath() {
        if (WINDOWS) {
            String sysRoot = System.getenv("SystemRoot");
            if (sysRoot == null || sysRoot.isBlank()) sysRoot = "C:\\Windows";
            return sysRoot + "\\System32\\drivers\\etc\\hosts";
        } else {
            return "/etc/passwd";
        }
    }
}
