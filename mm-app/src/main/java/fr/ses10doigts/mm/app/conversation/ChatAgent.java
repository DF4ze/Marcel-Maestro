package fr.ses10doigts.mm.app.conversation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
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
                .user(userMessage)
                .call()
                .content();

        long durationMs = System.currentTimeMillis() - startedAt;
        log.info("ChatAgent terminé — conversationId={}, durationMs={}", conversationId, durationMs);
        return content == null ? "" : content;
    }
}
