package fr.ses10doigts.mm.core.tool;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests unitaires de {@link SystemPathGuard}.
 *
 * <p>Les assertions sur les chemins système sont adaptées à la plateforme courante
 * (Windows vs Unix) pour garantir que les tests passent aussi bien en local que sur CI.</p>
 */
class SystemPathGuardTest {

    @TempDir
    Path tempDir;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("win");

    // ── Chemins système → isDangerous = true ───────────────────────────────────

    @Test
    void cheminSystème_détecté() {
        String systemPath = platformSystemPath();
        assertTrue(SystemPathGuard.isDangerous(systemPath),
                "isDangerous doit retourner true pour : " + systemPath);
    }

    @Test
    void cheminSystèmeViaNio_détecté() {
        Path systemPath = Path.of(platformSystemPath()).toAbsolutePath().normalize();
        assertTrue(SystemPathGuard.isDangerous(systemPath),
                "isDangerous(Path) doit retourner true pour : " + systemPath);
    }

    // ── Chemins non-système → isDangerous = false ─────────────────────────────

    @Test
    void répertoireTemp_nonDangereux() {
        // Un dossier TempDir JUnit n'est jamais un répertoire système
        assertFalse(SystemPathGuard.isDangerous(tempDir.toAbsolutePath().normalize()),
                "Un TempDir ne doit pas être considéré comme système");
    }

    @Test
    void sousRépertoireTempDir_nonDangereux() {
        Path sub = tempDir.resolve("workspace/src/Main.java").toAbsolutePath().normalize();
        assertFalse(SystemPathGuard.isDangerous(sub));
    }

    @Test
    void dossierDocuments_nonDangereux() {
        // Simulate a non-system user path
        String userPath = WINDOWS
                ? System.getProperty("user.home") + "\\Documents\\project\\file.txt"
                : System.getProperty("user.home") + "/Documents/project/file.txt";
        assertFalse(SystemPathGuard.isDangerous(userPath),
                "Un dossier utilisateur Documents ne doit pas être dangereux");
    }

    // ── Cas limites ────────────────────────────────────────────────────────────

    @Test
    void null_retourneFalse() {
        assertFalse(SystemPathGuard.isDangerous((String) null));
        assertFalse(SystemPathGuard.isDangerous((Path) null));
    }

    @Test
    void chaîneVide_retourneFalse() {
        assertFalse(SystemPathGuard.isDangerous(""));
        assertFalse(SystemPathGuard.isDangerous("   "));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Retourne un chemin système absolu existant sur la plateforme courante,
     * utilisable pour vérifier que {@link SystemPathGuard#isDangerous} le détecte.
     */
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
