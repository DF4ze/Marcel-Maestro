package fr.ses10doigts.mm.core.hitl;

/**
 * Décision de consentement humain face à une demande HITL (ADR-005).
 *
 * <p>La décision se lit sur deux axes orthogonaux :</p>
 * <ul>
 *   <li><strong>Scope</strong> — granularité de l'autorisation :
 *     <ul>
 *       <li>Stricte — chemin exact ou commande complète</li>
 *       <li>Local — répertoire parent ou nom du programme</li>
 *       <li>Large — outil entier (tous chemins, toutes commandes)</li>
 *     </ul>
 *   </li>
 *   <li><strong>Persistance</strong> — durée de l'autorisation :
 *     <ul>
 *       <li>Session — en mémoire uniquement, disparaît au redémarrage</li>
 *       <li>Projet — persisté en DB, limité au projet courant</li>
 *       <li>Toujours — persisté en DB, global tous projets</li>
 *     </ul>
 *   </li>
 * </ul>
 *
 * <p>{@code ALLOW_ONCE} et {@code DENY} sont des cas spéciaux hors de la matrice.</p>
 */
public enum ConsentDecision {

    /** Autorise cet appel uniquement — aucune mise en cache. */
    ALLOW_ONCE,

    // ── Stricte (chemin exact / commande complète) ────────────────────────────

    /** Stricte — session courante. */
    ALLOW_STRICT_SESSION,
    /** Stricte — persisté pour le projet courant. */
    ALLOW_STRICT_PROJECT,
    /** Stricte — persisté globalement (tous projets). */
    ALLOW_STRICT_ALWAYS,

    // ── Local (répertoire parent / nom du programme) ──────────────────────────

    /** Local — session courante. */
    ALLOW_LOCAL_SESSION,
    /** Local — persisté pour le projet courant. */
    ALLOW_LOCAL_PROJECT,
    /** Local — persisté globalement (tous projets). */
    ALLOW_LOCAL_ALWAYS,

    // ── Large (outil entier, tous chemins / toutes commandes) ─────────────────

    /** Large — session courante. */
    ALLOW_LARGE_SESSION,
    /** Large — persisté pour le projet courant. */
    ALLOW_LARGE_PROJECT,
    /** Large — persisté globalement (tous projets). */
    ALLOW_LARGE_ALWAYS,

    /** Refuse l'action. */
    DENY
}
