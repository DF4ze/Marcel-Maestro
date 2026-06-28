package fr.ses10doigts.mm.app.specialist.coding;

/**
 * Origine d'une décision de qualification de tâche.
 *
 * <p>Permet de rendre le routage observable : on sait toujours si la catégorie a été
 * déterminée par les règles déterministes, par le repli LLM, ou par le défaut de sûreté.</p>
 */
public enum QualificationSource {

    /** Catégorie tranchée par les règles déterministes (mots-clés). */
    RULES,
    /** Catégorie tranchée par le petit LLM de repli sur cas ambigu. */
    LLM,
    /** Catégorie de repli de sûreté (aucune règle, LLM indisponible ou illisible). */
    FALLBACK_DEFAULT
}
