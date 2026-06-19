package fr.ses10doigts.mm.core.hitl;

/**
 * Gravité d'une {@link AgentNotification}.
 *
 * <p>Taxonomie générique, agnostique du métier (zéro magic string). Le mapping vers
 * un canal concret (couleur, son, priorité Telegram) appartient à l'adaptateur.</p>
 */
public enum NotificationLevel {
    INFO,
    SUCCESS,
    WARNING,
    ERROR
}
