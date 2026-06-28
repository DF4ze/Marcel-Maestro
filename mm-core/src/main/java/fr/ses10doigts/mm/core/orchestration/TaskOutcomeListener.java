package fr.ses10doigts.mm.core.orchestration;

import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.engine.AgentOutcome;

/**
 * Observateur de fin de tâche utilisateur, notifié par le {@link Dispatcher}.
 *
 * <p>Permet à l'hôte de <strong>fermer la boucle</strong> sans coupler le noyau à la
 * persistance ou à la mémoire conversationnelle : le noyau publie l'événement, l'hôte
 * (mm-app/starter) décide quoi en faire (enregistrement du résultat, réinjection dans
 * la conversation source, etc.).</p>
 *
 * <p>Les implémentations doivent être tolérantes aux pannes : une exception levée par un
 * listener ne doit pas interrompre le routage. Le {@link Dispatcher} isole chaque appel.</p>
 */
public interface TaskOutcomeListener {

    /**
     * Appelé quand une tâche utilisateur ({@code USER_REQUEST}) atteint un état terminal.
     *
     * @param task tâche utilisateur d'origine (porte le contexte : projet, conversation, taskId)
     * @param outcome résultat terminal de l'exécution (statut, dernière réponse, raison)
     */
    void onUserTaskCompleted(TaskMessage task, AgentOutcome outcome);
}
