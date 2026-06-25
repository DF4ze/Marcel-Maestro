package fr.ses10doigts.mm.core.tool;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * Détecte les chemins système dangereux — rejet inconditionnel, aucun bypass possible (ADR-023).
 *
 * <p><strong>Windows</strong> : les racines système sont lues depuis les variables d'environnement
 * ({@code SystemRoot} → C:\Windows, {@code ProgramFiles}, {@code ProgramFiles(x86)},
 * {@code ProgramData}). Cela reste correct si Windows est installé sur un lecteur autre que
 * {@code C:}.</p>
 *
 * <p><strong>Linux / macOS</strong> : chemins système Unix classiques codés en dur
 * ({@code /etc}, {@code /usr}, {@code /bin}, {@code /sbin}, {@code /lib}, {@code /lib64},
 * {@code /boot}, {@code /dev}, {@code /proc}, {@code /sys}, {@code /run}, {@code /root}).
 * Note : {@code /var} et {@code /tmp} ne sont pas considérés comme système — des agents
 * légitimes peuvent avoir besoin d'y écrire ; le HITL gate les protège.</p>
 *
 * <p>Les comparaisons utilisent {@link Path#startsWith(Path)}, qui est insensible à la casse
 * sur Windows et sensible sur Linux.</p>
 *
 * <p>Classe utilitaire — pas d'état, pas d'instance publique.</p>
 */
@Slf4j
public final class SystemPathGuard {

    /** Chemins système Unix absolus protégés. */
    private static final List<String> UNIX_SYSTEM_ROOTS = List.of(
            "/etc", "/usr", "/bin", "/sbin",
            "/lib", "/lib64", "/lib32", "/libx32",
            "/boot", "/dev", "/proc", "/sys", "/run", "/root"
    );

    /**
     * Racines système résolues à l'initialisation de la JVM.
     * Immuable après construction.
     */
    private static final List<Path> SYSTEM_ROOTS = buildSystemRoots();

    private SystemPathGuard() {}

    // ── API publique ────────────────────────────────────────────────────────────

    /**
     * Retourne {@code true} si le chemin normalisé pointe vers un répertoire système protégé.
     *
     * <p>Le chemin doit être passé <em>après</em> {@code toAbsolutePath().normalize()} pour
     * que la détection soit fiable (absence de segments {@code ..}).</p>
     *
     * @param absoluteNormalized chemin absolu normalisé ; {@code null} → {@code false}
     * @return {@code true} si le chemin démarre par une racine système connue
     */
    public static boolean isDangerous(Path absoluteNormalized) {
        if (absoluteNormalized == null) return false;
        return SYSTEM_ROOTS.stream().anyMatch(absoluteNormalized::startsWith);
    }

    /**
     * Surcharge pour les chemins {@code String} — normalise en interne.
     *
     * @param absoluteNormalized chemin absolu normalisé ; {@code null} ou vide → {@code false}
     * @return {@code true} si dangereux, {@code false} si chemin invalide
     */
    public static boolean isDangerous(String absoluteNormalized) {
        if (absoluteNormalized == null || absoluteNormalized.isBlank()) return false;
        try {
            return isDangerous(Path.of(absoluteNormalized).toAbsolutePath().normalize());
        } catch (InvalidPathException e) {
            return false;
        }
    }

    // ── Initialisation ─────────────────────────────────────────────────────────

    private static List<Path> buildSystemRoots() {
        List<Path> roots = new ArrayList<>();
        if (isWindows()) {
            addEnvPath(roots, "SystemRoot");           // C:\Windows
            addEnvPath(roots, "ProgramFiles");         // C:\Program Files
            addEnvPath(roots, "ProgramFiles(x86)");    // C:\Program Files (x86)
            addEnvPath(roots, "ProgramData");          // C:\ProgramData
        } else {
            for (String p : UNIX_SYSTEM_ROOTS) {
                try {
                    roots.add(Path.of(p).normalize());
                } catch (InvalidPathException e) {
                    log.warn("SystemPathGuard : chemin système Unix invalide ignoré : {}", p);
                }
            }
        }
        log.debug("SystemPathGuard initialisé avec {} racine(s) système (os={})",
                roots.size(), System.getProperty("os.name", "?"));
        return List.copyOf(roots);
    }

    private static void addEnvPath(List<Path> roots, String envVar) {
        String value = System.getenv(envVar);
        if (value != null && !value.isBlank()) {
            try {
                Path p = Path.of(value).toAbsolutePath().normalize();
                roots.add(p);
                log.debug("SystemPathGuard : racine depuis ${{}} = {}", envVar, p);
            } catch (InvalidPathException e) {
                log.warn("SystemPathGuard : variable '{}' = '{}' invalide, ignorée", envVar, value);
            }
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
