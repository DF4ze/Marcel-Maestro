package fr.ses10doigts.mm.core.engine.support;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * {@link ChatModel} scripté pour les tests de la boucle <strong>sans LLM réel</strong>
 * (étape 3, contrainte de tests). On l'enveloppe dans un {@code ChatClient} via
 * {@code ChatClient.builder(model)} : c'est notre « MockChatClient ».
 *
 * <p>On empile des réponses JSON (done, running, blocked, trouble, KO, JSON invalide…),
 * éventuellement avec un {@code finishReason} (ex. {@code "length"} pour simuler une
 * troncature) ou un échec d'appel. Quand la pile est vide, la dernière réponse est
 * répétée — pratique pour piloter une boucle infinie jusqu'au garde-fou.</p>
 *
 * <p>Volontairement minimal : ne supporte que {@code call(Prompt)} ; le streaming n'est
 * pas utilisé par la boucle.</p>
 */
public final class ScriptedChatModel implements ChatModel {

    private record Step(String text, String finishReason, RuntimeException error) {}

    private final Deque<Step> steps = new ArrayDeque<>();
    private Step last;
    private int callCount;
    private Runnable onCall = () -> { };

    /** Empile une réponse texte avec {@code finishReason = "stop"}. */
    public ScriptedChatModel reply(String text) {
        return reply(text, "stop");
    }

    /** Empile une réponse texte avec un {@code finishReason} explicite (ex. "length"). */
    public ScriptedChatModel reply(String text, String finishReason) {
        steps.add(new Step(text, finishReason, null));
        return this;
    }

    /** Empile un échec d'appel (réseau/provider) levé au prochain appel. */
    public ScriptedChatModel fail(RuntimeException error) {
        steps.add(new Step(null, null, error));
        return this;
    }

    /** Hook exécuté au tout début de chaque {@code call} (ex. déclencher un STOP). */
    public ScriptedChatModel onCall(Runnable hook) {
        this.onCall = hook;
        return this;
    }

    /** Nombre d'appels LLM réellement reçus. */
    public int callCount() {
        return callCount;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        callCount++;
        onCall.run();
        Step step = steps.isEmpty() ? last : steps.poll();
        if (step == null) {
            step = new Step("", "stop", null);
        }
        last = step;
        if (step.error() != null) {
            throw step.error();
        }
        AssistantMessage message = new AssistantMessage(step.text() == null ? "" : step.text());
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason(step.finishReason())
                .build();
        return new ChatResponse(List.of(new Generation(message, metadata)));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        throw new UnsupportedOperationException("streaming non utilisé par la boucle");
    }
}
