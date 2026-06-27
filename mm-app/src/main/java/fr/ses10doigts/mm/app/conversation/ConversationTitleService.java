package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de génération automatique de titre pour les conversations (E2-M5, ADR-025).
 *
 * <p>Le titre est généré de façon <strong>asynchrone</strong> ({@link Async}) après la
 * soumission du premier message de la conversation. L'appel LLM est non-bloquant : la
 * tâche part immédiatement et le champ {@code title} de {@link fr.ses10doigts.mm.starter.conversation.ConversationEntity}
 * est mis à jour dès réception de la réponse (quelques secondes).</p>
 *
 * <p>Stratégie d'erreur : si l'appel LLM échoue, l'erreur est loguée et {@code title}
 * reste {@code null}. Aucun retry (KISS — le titre n'est pas critique, ADR-025).</p>
 *
 * <p>Le modèle utilisé est celui configuré dans {@code spring.ai.openai.chat.options.model} —
 * même provider que Cortex, aucune double configuration LLM nécessaire.</p>
 *
 * <p>Logging (coding rules) :</p>
 * <ul>
 *   <li>{@code log.info} : titre généré et persisté.</li>
 *   <li>{@code log.debug} : prompt envoyé au LLM, modèle utilisé.</li>
 *   <li>{@code log.error} : échec LLM (title reste null).</li>
 * </ul>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ConversationTitleService {

    private final ChatClient chatClient;
    private final ConversationRepository conversationRepository;

    /**
     * Prompt de génération de titre. Configurable via {@code mm.conversation.title.prompt}.
     * La valeur par défaut est un prompt minimal (~15 tokens de contexte + le message).
     */
    @Value("${mm.conversation.title.prompt:Génère un titre court (5 mots maximum) pour une conversation démarrée par ce message. Réponds uniquement avec le titre, sans ponctuation finale ni guillemets. Message : }")
    private String titlePrompt;

    /**
     * Longueur maximale (en caractères) de l'extrait du premier message envoyé au LLM.
     *
     * <p>Un premier message peut être très long (copier-coller d'un fichier entier).
     * On tronque à {@value} chars pour éviter de consommer des tokens inutiles :
     * les premiers mots suffisent largement à générer un titre pertinent.</p>
     */
    static final int MAX_EXCERPT_LENGTH = 500;

    /**
     * Génère un titre de conversation via le LLM configuré, de façon asynchrone.
     *
     * <p>Déclenché par {@link ConversationService#addMessage} après le premier message.
     * Le titre est persisté en DB dès réception de la réponse. Si la conversation
     * n'existe plus au moment de la mise à jour (suppression concurrente), la mise à
     * jour est silencieusement ignorée.</p>
     *
     * <p>Le message est tronqué à {@value MAX_EXCERPT_LENGTH} caractères avant envoi
     * au LLM : les premiers mots sont suffisants pour générer un titre pertinent.</p>
     *
     * <p>En cas d'échec LLM : {@code log.error} + {@code title} reste {@code null}.
     * Pas de retry (ADR-025 — KISS).</p>
     *
     * @param conversationId UUID de la conversation cible
     * @param firstMessage   premier message de l'utilisateur (source du titre)
     */
    @Async
    @Transactional
    public void generateTitle(String conversationId, String firstMessage) {
        String excerpt = firstMessage.length() > MAX_EXCERPT_LENGTH
                ? firstMessage.substring(0, MAX_EXCERPT_LENGTH) + "…"
                : firstMessage;

        log.debug("Génération de titre — conversationId={}, excerptLength={}",
                conversationId, excerpt.length());
        try {
            String title = chatClient.prompt()
                    .user(titlePrompt + excerpt)
                    .call()
                    .content();

            if (title != null) {
                title = title.strip();
                // Supprimer les guillemets éventuels que certains modèles ajoutent
                title = title.replaceAll("^[\"']|[\"']$", "");
            }

            final String finalTitle = title;
            conversationRepository.findById(conversationId).ifPresent(conv -> {
                conv.setTitle(finalTitle);
                conversationRepository.save(conv);
                log.info("Titre généré et persisté — conversationId={}, title='{}'",
                        conversationId, finalTitle);
            });

        } catch (Exception e) {
            log.error("Génération de titre échouée — conversationId={} : {}",
                    conversationId, e.getMessage());
            // Pas de retry — title reste null (ADR-025)
        }
    }
}
