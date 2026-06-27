package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.core.prompt.SystemPromptExtension;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Compose le system prompt conversationnel de Marcel.
 *
 * <p>Contrairement à {@code SystemPromptComposer}, dont le prompt de base est spécifique
 * au mode Cortex JSON structuré, ce composeur assemble un prompt de base orienté
 * conversation libre avec les mêmes {@link SystemPromptExtension} dynamiques
 * déjà utilisées par l'application.</p>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MarcelChatPromptComposer {

    private final List<SystemPromptExtension> extensions;

    @Value("${mm.chat.system-prompt:Tu es Marcel, un assistant de développement Java et Spring. Tu réponds en français, de façon concise et directe. Tu disposes de l'outil submit_task pour déléguer une action concrète au moteur agentique Marcel en arrière-plan. Utilise submit_task quand la demande nécessite une action réelle sur le filesystem, un build Maven, ou un déploiement sur le VPS. N'utilise pas submit_task pour une simple question, une analyse de code, une discussion d'architecture ou une explication. Quand tu soumets une tâche, réponds immédiatement de façon naturelle sans attendre le résultat, en précisant que l'utilisateur recevra une notification Telegram à la fin.}")
    private String basePrompt;

    /**
     * Compose le prompt complet à partir du prompt Marcel statique et des extensions dynamiques.
     *
     * @return prompt complet prêt à être envoyé au LLM
     */
    public String compose() {
        StringBuilder builder = new StringBuilder(basePrompt.strip());
        for (SystemPromptExtension extension : extensions) {
            if (extension == null) {
                continue;
            }
            String contribution = extension.contribution();
            if (contribution != null && !contribution.isBlank()) {
                builder.append("\n\n").append(contribution.strip());
            }
        }
        log.debug("Prompt Marcel composé — {} extension(s) appliquée(s)", extensions.size());
        return builder.toString();
    }
}
