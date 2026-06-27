package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.core.agent.TaskMessage;
import fr.ses10doigts.mm.core.agent.TaskType;
import fr.ses10doigts.mm.core.orchestration.Dispatcher;
import fr.ses10doigts.mm.core.queue.TaskQueue;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Agent conversationnel de Marcel pour les échanges libres en mode chat.
 *
 * <p>Ce composant encapsule l'appel LLM Spring AI, recharge l'historique via
 * {@link MessageChatMemoryAdvisor} et persiste automatiquement les messages USER /
 * ASSISTANT dans {@link ChatMemory}.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatAgent {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final MarcelChatPromptComposer promptComposer;
    private final TaskQueue taskQueue;
    private final AgentContextHolder agentContextHolder;
    private final ObjectProvider<Dispatcher> dispatcherProvider;

    /**
     * Envoie un message utilisateur au LLM dans le contexte d'une conversation persistée.
     *
     * @param conversationId identifiant de la conversation, utilisé comme clé mémoire
     * @param userMessage    message utilisateur à traiter
     * @return contenu textuel de la réponse assistant
     */
    public String chat(String conversationId, String userMessage) {
        long startedAt = System.currentTimeMillis();
        log.info("ChatAgent démarré — conversationId={}", conversationId);

        String content = chatClient.prompt()
                .advisors(spec -> spec
                        .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                        .param(ChatMemory.CONVERSATION_ID, conversationId))
                .system(promptComposer.compose())
                .tools(this)
                .user(userMessage)
                .call()
                .content();

        long durationMs = System.currentTimeMillis() - startedAt;
        log.info("ChatAgent terminé — conversationId={}, durationMs={}", conversationId, durationMs);
        return content == null ? "" : content;
    }

    /**
     * Soumet une tâche au moteur agentique Marcel pour exécution asynchrone.
     *
     * @param description description de la tâche à exécuter
     * @return accusé de soumission contenant le taskId généré
     */
    @Tool(name = "submit_task",
            description = "Soumet une tâche au moteur agentique Marcel pour exécution en arrière-plan. "
            + "À utiliser quand la demande requiert une action concrète : lire/écrire des fichiers, "
            + "lancer un build Maven, déployer sur le VPS. "
            + "Ne pas utiliser pour une simple question ou discussion. "
            + "Retourne l'identifiant de la tâche soumise.")
    public String submitTask(String description) {
        AgentContext currentContext = agentContextHolder.get();
        if (currentContext == null) {
            throw new IllegalStateException("Aucun AgentContext lié pour submit_task");
        }
        Dispatcher dispatcher = dispatcherProvider.getIfAvailable();
        if (dispatcher == null || !dispatcher.isRunning()) {
            throw new IllegalStateException("Dispatcher indisponible pour submit_task");
        }

        String taskId = UUID.randomUUID().toString();
        TaskMessage taskMessage = new TaskMessage(
                taskId,
                TaskType.USER_REQUEST,
                "cortex",
                description,
                AgentContext.of(
                        currentContext.tenant(),
                        currentContext.projectId(),
                        currentContext.conversationId(),
                        taskId));

        taskQueue.submit(taskMessage);
        log.info("submit_task exécuté — taskId={}, projectId={}, conversationId={}, description='{}'",
                taskId,
                currentContext.projectId(),
                currentContext.conversationId(),
                truncate(description, 80));
        return "Tâche soumise — id: " + taskId;
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "null";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }
}
