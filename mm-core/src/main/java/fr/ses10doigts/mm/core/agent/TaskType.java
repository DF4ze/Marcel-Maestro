package fr.ses10doigts.mm.core.agent;

/**
 * Type d'un {@link TaskMessage} transitant par la file de tâches (ADR-008).
 *
 * <p>Enum fermé volontairement (zéro magic string) : le routage est une affaire de
 * noyau, pas de l'hôte. Jeu minimal en étape 2 ; de nouveaux types génériques
 * pourront être ajoutés à l'étape 7 (orchestrateur) si la topologie l'exige.</p>
 */
public enum TaskType {
    /** Requête initiale d'un utilisateur (console, Telegram…). */
    USER_REQUEST,
    /** Sous-tâche assignée par le Cortex à un spécialiste. */
    SPECIALIST_REQUEST,
    /** Rapport d'un spécialiste remontant vers le Cortex. */
    SPECIALIST_REPORT
}
