package fr.ses10doigts.mm.core.tool;

/**
 * Port : registre des dossiers de travail déclarés par projet (ADR-023, E2-M3).
 *
 * <p>Permet à {@link PathValidator} et {@link ToolExecutionGuard} de savoir si un chemin
 * absolu appartient à un dossier externe déclaré explicitement pour un projet donné.</p>
 *
 * <p><strong>Règle de pureté (ADR-002 / ADR-003)</strong> : cette interface vit dans
 * {@code mm-core} et ne dépend d'aucune infrastructure. L'implémentation JPA réside dans
 * {@code mm-spring-boot-starter} ; les tests unitaires utilisent un stub ou un lambda.</p>
 *
 * <p><strong>Contrat de normalisation</strong> : l'implémentation est responsable de
 * normaliser {@code absolutePath} (résolution des {@code ..}, séparateurs OS) avant la
 * comparaison par préfixe. Un chemin traversal comme {@code /declared/../etc/passwd}
 * normalise en {@code /etc/passwd}, qui n'est pas sous le dossier déclaré : la méthode
 * retourne {@code false}. La protection anti path-traversal est donc embarquée dans
 * ce contrat.</p>
 *
 * <p><strong>Comportement par défaut</strong> : si aucune implémentation n'est disponible
 * (bean absent), {@link ToolExecutionGuard} ne bypasse jamais le HITL — sécurité par défaut.</p>
 */
public interface WorkspaceRegistry {

    /**
     * Indique si {@code absolutePath} est sous l'un des dossiers externes déclarés
     * pour le projet {@code projectId}.
     *
     * <p>L'implémentation doit :</p>
     * <ul>
     *   <li>normaliser {@code absolutePath} via {@code Path.of(absolutePath).toAbsolutePath().normalize()}
     *       avant de comparer ;</li>
     *   <li>comparer par préfixe de composants de chemin (via {@code Path.startsWith()},
     *       OS-aware : case-insensitive sur Windows, case-sensitive sur Linux) ;</li>
     *   <li>retourner {@code false} si {@code projectId} est {@code null}, vide, ou si le
     *       projet n'a aucun dossier externe déclaré.</li>
     * </ul>
     *
     * @param absolutePath chemin absolu à tester (peut contenir {@code ..} ou {@code \})
     * @param projectId    identifiant du projet ; {@code null} → retourne {@code false}
     * @return {@code true} si le chemin résolu est sous un dossier déclaré du projet
     */
    boolean isInDeclaredWorkspace(String absolutePath, String projectId);
}
