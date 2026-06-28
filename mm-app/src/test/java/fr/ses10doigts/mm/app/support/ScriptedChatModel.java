package fr.ses10doigts.mm.app.support;

import java.util.ArrayDeque;
import java.util.ArrayList;
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
 * ChatModel scripté local à mm-app pour piloter les tests E3 sans LLM réel.
 */
public final class ScriptedChatModel implements ChatModel {

    private record Step(String text, String finishReason, RuntimeException error) {}

    private final Deque<Step> steps = new ArrayDeque<>();
    private final List<Prompt> prompts = new ArrayList<>();
    private Step last;
    private int callCount;
    private Runnable onCall = () -> { };

    /**
     * Empile une réponse texte avec finishReason "stop".
     *
     * @param text texte à renvoyer
     * @return le modèle courant
     */
    public ScriptedChatModel reply(String text) {
        return reply(text, "stop");
    }

    /**
     * Empile une réponse texte avec un finishReason explicite.
     *
     * @param text texte à renvoyer
     * @param finishReason raison de terminaison simulée
     * @return le modèle courant
     */
    public ScriptedChatModel reply(String text, String finishReason) {
        steps.add(new Step(text, finishReason, null));
        return this;
    }

    /**
     * Empile une erreur simulée pour le prochain appel.
     *
     * @param error exception à lever
     * @return le modèle courant
     */
    public ScriptedChatModel fail(RuntimeException error) {
        steps.add(new Step(null, null, error));
        return this;
    }

    /**
     * Hook déclenché au début de chaque appel.
     *
     * @param hook action à exécuter
     * @return le modèle courant
     */
    public ScriptedChatModel onCall(Runnable hook) {
        this.onCall = hook;
        return this;
    }

    /**
     * Retourne le nombre d'appels réellement reçus.
     *
     * @return nombre d'appels
     */
    public int callCount() {
        return callCount;
    }

    /**
     * Retourne les prompts effectivement passés au modèle.
     *
     * @return historique des prompts
     */
    public List<Prompt> prompts() {
        return List.copyOf(prompts);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        Step step = nextStep(prompt);
        if (step.error() != null) {
            throw step.error();
        }
        return toResponse(step.text() == null ? "" : step.text(), step.finishReason());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        Step step = nextStep(prompt);
        if (step.error() != null) {
            return Flux.error(step.error());
        }

        String text = step.text() == null ? "" : step.text();
        if (text.isEmpty()) {
            return Flux.just(toResponse("", step.finishReason()));
        }

        return Flux.range(0, text.length())
                .map(index -> toResponse(String.valueOf(text.charAt(index)), step.finishReason()));
    }

    private Step nextStep(Prompt prompt) {
        callCount++;
        prompts.add(prompt);
        onCall.run();
        Step step = steps.isEmpty() ? last : steps.poll();
        if (step == null) {
            step = new Step("", "stop", null);
        }
        last = step;
        return step;
    }

    private ChatResponse toResponse(String text, String finishReason) {
        AssistantMessage message = new AssistantMessage(text);
        ChatGenerationMetadata metadata = ChatGenerationMetadata.builder()
                .finishReason(finishReason)
                .build();
        return new ChatResponse(List.of(new Generation(message, metadata)));
    }
}
