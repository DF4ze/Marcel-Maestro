package fr.ses10doigts.mm.core.hitl;

import fr.ses10doigts.mm.core.agent.AgentContext;

/**
 * Notification poussée vers l'humain via {@link HumanInteraction#notify(AgentNotification)}.
 *
 * <p>Sens unique (pas de réponse attendue) : fin de tâche, blocage, KO, build/deploy
 * terminé… À distinguer de {@link HitlRequest} qui, elle, attend une décision.</p>
 *
 * @param title   titre court
 * @param message corps du message
 * @param level   gravité
 * @param ctx     contexte d'exécution courant
 */
public record AgentNotification(
        String title,
        String message,
        NotificationLevel level,
        AgentContext ctx) {
}
