package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Service de generation d'un brief de conversation a partir de l'historique courant.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationBriefService {

    private final ChatClient chatClient;
    private final ConversationService conversationService;

    @Value("${mm.conversation.brief.prompt:Tu produis un brief operationnel de conversation. Reponds en francais, de facon concise et structuree. Donne : 1) objectif, 2) points decides, 3) actions/taches ouvertes, 4) risques ou zones floues. Si l'historique est trop maigre, dis-le explicitement.}")
    private String briefPrompt;

    @Value("${mm.conversation.brief.max-transcript-chars:12000}")
    private int maxTranscriptChars;

    /**
     * Genere un brief textuel de la conversation demandee.
     *
     * @param conversationId identifiant de la conversation a resumer
     * @return brief LLM ou message explicite si la conversation est vide
     */
    public String brief(String conversationId) {
        ConversationEntity conversation = conversationService.getConversation(conversationId);
        List<Message> messages = conversationService.getMessages(conversationId);
        if (messages == null || messages.isEmpty()) {
            log.info("Brief conversation vide - conversationId={}", conversationId);
            return "La conversation est vide. Envoie d'abord un message pour pouvoir produire un brief.";
        }

        String transcript = buildTranscript(conversation, messages);
        log.info("Generation brief conversation - conversationId={}, messageCount={}, transcriptLength={}",
                conversationId, messages.size(), transcript.length());

        String brief = chatClient.prompt()
                .system(briefPrompt)
                .user(transcript)
                .call()
                .content();
        return brief == null ? "" : brief.strip();
    }

    private String buildTranscript(ConversationEntity conversation, List<Message> messages) {
        StringBuilder builder = new StringBuilder();
        builder.append("ConversationId: ").append(conversation.getId()).append('\n');
        builder.append("ProjetId: ").append(conversation.getProjectId()).append('\n');
        builder.append("Titre: ").append(conversation.getTitle() == null ? "" : conversation.getTitle()).append("\n\n");
        for (Message message : messages) {
            builder.append(message.getMessageType().name())
                    .append(": ")
                    .append(message.getText() == null ? "" : message.getText())
                    .append("\n\n");
            if (builder.length() >= maxTranscriptChars) {
                log.info("Brief transcript tronque - conversationId={}, maxTranscriptChars={}",
                        conversation.getId(), maxTranscriptChars);
                return builder.substring(0, maxTranscriptChars) + "\n[... transcript tronque]";
            }
        }
        return builder.toString();
    }
}
