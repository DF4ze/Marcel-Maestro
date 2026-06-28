package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.AgentResponse;
import fr.ses10doigts.mm.core.agent.AgentStatus;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.engine.AgentOutcome;
import fr.ses10doigts.mm.core.orchestration.TaskOutcomeListener;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskRepository;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskStatus;
import java.time.Instant;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

/**
 * Ferme la boucle conversation ↔ tâche à la fin d'une exécution.
 *
 * <p>Deux effets complémentaires, déclenchés par le {@link fr.ses10doigts.mm.core.orchestration.Dispatcher}
 * via {@link TaskOutcomeListener} :</p>
 * <ol>
 *   <li><strong>Enregistrement persistant</strong> : met à jour le {@code conversation_task}
 *       correspondant (statut final, résumé du résultat, horodatage de fin). C'est le journal
 *       requêtable des actions réalisées et de leur résultat.</li>
 *   <li><strong>Réinjection</strong> : ajoute un message assistant concis dans la mémoire de la
 *       conversation source, pour que le tour suivant dispose du résultat sans le redemander.</li>
 * </ol>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ConversationTaskOutcomeListener implements TaskOutcomeListener {

    private static final int MAX_SUMMARY_CHARS = 2000;
    private static final String NO_CONTEXT = "none";

    private final ConversationTaskRepository conversationTaskRepository;
    private final ChatMemory chatMemory;

    /**
     * Traite la fin d'une tâche utilisateur : enregistrement du résultat puis réinjection.
     *
     * @param task tâche utilisateur d'origine
     * @param outcome résultat terminal de l'exécution
     */
    @Override
    public void onUserTaskCompleted(TaskMessage task, AgentOutcome outcome) {
        String taskId = task.taskId();
        ConversationTaskStatus status = mapStatus(outcome.finalStatus());
        String summary = buildSummary(outcome);

        recordResult(taskId, status, summary);
        reinjectIntoConversation(task.ctx(), status, summary);

        log.info("Boucle fermée — taskId={}, status={}, conversationId={}",
                taskId, status, task.ctx() != null ? task.ctx().conversationId() : "null");
    }

    /**
     * Met à jour le lien conversation/tâche persisté avec le résultat final.
     *
     * @param taskId identifiant de la tâche moteur
     * @param status statut persisté final
     * @param summary résumé du résultat
     */
    private void recordResult(String taskId, ConversationTaskStatus status, String summary) {
        Optional<ConversationTaskEntity> found = conversationTaskRepository.findByTaskId(taskId);
        if (found.isEmpty()) {
            log.debug("recordResult — aucun conversation_task pour taskId={}, enregistrement ignoré", taskId);
            return;
        }
        ConversationTaskEntity entity = found.get();
        entity.setStatus(status);
        entity.setResultSummary(summary);
        entity.setCompletedAt(Instant.now().toString());
        conversationTaskRepository.save(entity);
    }

    /**
     * Réinjecte un message assistant de résultat dans la mémoire de la conversation source.
     *
     * @param ctx contexte d'exécution de la tâche
     * @param status statut persisté final
     * @param summary résumé du résultat
     */
    private void reinjectIntoConversation(AgentContext ctx, ConversationTaskStatus status, String summary) {
        if (ctx == null) {
            return;
        }
        String conversationId = ctx.conversationId();
        if (conversationId == null || conversationId.isBlank() || NO_CONTEXT.equals(conversationId)) {
            log.debug("reinjectIntoConversation — pas de conversation exploitable, réinjection ignorée");
            return;
        }
        String marker = "[Tâche " + status + "] " + summary;
        chatMemory.add(conversationId, new AssistantMessage(marker));
        log.debug("reinjectIntoConversation — résultat réinjecté dans conversationId={}", conversationId);
    }

    /**
     * Mappe le statut moteur terminal vers le statut persisté du lien conversation/tâche.
     *
     * @param finalStatus statut terminal de la boucle
     * @return statut persisté correspondant
     */
    private ConversationTaskStatus mapStatus(AgentStatus finalStatus) {
        if (finalStatus == null) {
            return ConversationTaskStatus.KO;
        }
        return switch (finalStatus) {
            case DONE -> ConversationTaskStatus.DONE;
            case BLOCKED -> ConversationTaskStatus.BLOCKED;
            default -> ConversationTaskStatus.KO;
        };
    }

    /**
     * Construit un résumé lisible et borné du résultat de tâche.
     *
     * @param outcome résultat terminal
     * @return résumé non vide, tronqué si nécessaire
     */
    private String buildSummary(AgentOutcome outcome) {
        AgentResponse last = outcome.lastResponse();
        String candidate = null;
        if (last != null && last.output() != null && !last.output().isBlank()) {
            candidate = last.output().trim();
        } else if (outcome.terminationReason() != null && !outcome.terminationReason().isBlank()) {
            candidate = outcome.terminationReason().trim();
        }
        if (candidate == null || candidate.isBlank()) {
            candidate = "Terminé avec le statut " + outcome.finalStatus().json();
        }
        return candidate.length() <= MAX_SUMMARY_CHARS
                ? candidate
                : candidate.substring(0, MAX_SUMMARY_CHARS) + "…";
    }
}
