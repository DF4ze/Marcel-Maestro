package fr.ses10doigts.mm.app.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Tests unitaires du service de brief de conversation.
 */
class ConversationBriefServiceTest {

    @Test
    @DisplayName("brief retourne un resume LLM quand la conversation contient des messages")
    void brief_returnsSummaryForConversation() {
        ChatClient chatClient = mock(ChatClient.class);
        ConversationService conversationService = mock(ConversationService.class);
        ConversationBriefService briefService = new ConversationBriefService(chatClient, conversationService);
        ReflectionTestUtils.setField(briefService, "briefPrompt", "prompt brief");
        ReflectionTestUtils.setField(briefService, "maxTranscriptChars", 5000);

        ConversationEntity conversation = ConversationEntity.builder()
                .id("conv-1")
                .projectId("project-1")
                .title("Titre")
                .build();
        List<Message> messages = List.of(
                new UserMessage("Bonjour"),
                new AssistantMessage("Salut, on parle du projet."));

        ChatClientRequestSpec requestSpec = mock(ChatClientRequestSpec.class);
        CallResponseSpec callSpec = mock(CallResponseSpec.class);
        when(conversationService.getConversation("conv-1")).thenReturn(conversation);
        when(conversationService.getMessages("conv-1")).thenReturn(messages);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.user(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("Objectif: cadrer le projet");

        String brief = briefService.brief("conv-1");

        assertThat(brief).isEqualTo("Objectif: cadrer le projet");
    }

    @Test
    @DisplayName("brief retourne un message explicite quand la conversation est vide")
    void brief_emptyConversation_returnsReadableMessage() {
        ChatClient chatClient = mock(ChatClient.class);
        ConversationService conversationService = mock(ConversationService.class);
        ConversationBriefService briefService = new ConversationBriefService(chatClient, conversationService);

        when(conversationService.getConversation("conv-2")).thenReturn(ConversationEntity.builder()
                .id("conv-2")
                .projectId("project-2")
                .build());
        when(conversationService.getMessages("conv-2")).thenReturn(List.of());

        String brief = briefService.brief("conv-2");

        assertThat(brief).contains("conversation est vide");
    }
}
