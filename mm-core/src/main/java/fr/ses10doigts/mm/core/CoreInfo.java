package fr.ses10doigts.mm.core;

/**
 * Métadonnées d'identité du noyau. Marqueur minimal de l'étape 1
 * (les vrais types pivots arrivent à l'étape 2).
 */
public final class CoreInfo {

    /** Nom lisible du noyau. */
    public static final String NAME = "Marcel Maestro Core";

    /** Version du noyau (alignée sur le projet). */
    public static final String VERSION = "0.0.1-SNAPSHOT";

    private CoreInfo() {
        // Classe utilitaire : pas d'instanciation.
    }
}
