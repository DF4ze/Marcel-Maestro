package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.app.project.ProjectNotFoundException;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.conversation.ConversationStatus;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service applicatif gérant le cycle de vie des conversations et la mémoire chat (E2-M2).
 *
 * <p>Une conversation est liée à un projet (1 projet → N conversations). Son UUID est la
 * clé d'isolation mémoire passée à {@link ChatMemory} : chaque conversation dispose de sa
 * propre partition dans {@code SPRING_AI_CHAT_MEMORY}, persistée en SQLite via
 * {@code JdbcChatMemoryRepository}.</p>
 *
 * <p>Règles métier :</p>
 * <ul>
 *   <li>Seul un projet {@code ACTIVE} peut recevoir une nouvelle conversation (E2-M2).</li>
 *   <li>La mémoire survit aux redémarrages (JDBC-backed, ADR-014).</li>
 *   <li>L'isolation est garantie par le {@code conversationId} : deux conversations
 *       différentes ne partagent jamais de messages, même si elles appartiennent
 *       au même projet.</li>
 * </ul>
 *
 * <p>Logging (coding rules) :</p>
 * <ul>
 *   <li>{@code log.info} sur : création de conversation, premier message soumis,
 *       basculement de contexte mémoire.</li>
 *   <li>{@code log.debug} sur : conversationId injecté, clé ChatMemory.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;
    private final ChatMemory chatMemory;
    private final ChatAgent chatAgent;
    private final AgentContextHolder agentContextHolder;

    /**
     * Service de génération de titre (E2-M5). Injecté via {@link ObjectProvider}
     * pour rester optionnel : absent en test ou si le LLM n'est pas configuré,
     * le titre reste simplement null sans bloquer les autres fonctionnalités.
     */
    private final ObjectProvider<ConversationTitleService> titleServiceProvider;

    // ─────────────────────────────────────────────────────────────────────────
    // Création
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Démarre une nouvelle conversation pour un projet {@code ACTIVE}.
     *
     * <p>Vérifie que le projet existe et n'est pas archivé, génère un UUID v4,
     * insère en DB et loggue la création.</p>
     *
     * @param projectId l'ID du projet cible
     * @return l'entité conversation créée et persistée
     * @throws ProjectNotFoundException            si le projet n'existe pas
     * @throws ProjectArchivedConversationException si le projet est archivé
     */
    @Transactional
    public ConversationEntity startConversation(String projectId) {
        ProjectEntity project = findProjectOrThrow(projectId);
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new ProjectArchivedConversationException(projectId);
        }

        String conversationId = UUID.randomUUID().toString();
        ConversationEntity conv = ConversationEntity.builder()
                .id(conversationId)
                .projectId(projectId)
                .startedAt(Instant.now().toString())
                .status(ConversationStatus.OPEN)
                .build();

        ConversationEntity saved = conversationRepository.save(conv);
        log.info("Conversation créée — conversationId={}, projectId={}", conversationId, projectId);
        return saved;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lectures
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne toutes les conversations d'un projet.
     *
     * @param projectId l'ID du projet
     * @return liste des conversations (peut être vide)
     * @throws ProjectNotFoundException si le projet n'existe pas
     */
    @Transactional(readOnly = true)
    public List<ConversationEntity> listByProject(String projectId) {
        findProjectOrThrow(projectId);
        return conversationRepository.findAllByProjectId(projectId);
    }

    /**
     * Retourne une conversation par son ID.
     *
     * @param conversationId l'ID de la conversation
     * @return l'entité conversation
     * @throws ConversationNotFoundException si la conversation n'existe pas
     */
    @Transactional(readOnly = true)
    public ConversationEntity getConversation(String conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    /**
     * Retourne les messages d'une conversation depuis la mémoire JDBC.
     *
     * <p>La clé utilisée est le {@code conversationId} (UUID de {@link ConversationEntity}),
     * garantissant l'isolation entre conversations.</p>
     *
     * @param conversationId l'ID de la conversation
     * @return liste des messages en mémoire (peut être vide)
     * @throws ConversationNotFoundException si la conversation n'existe pas
     */
    @Transactional(readOnly = true)
    public List<Message> getMessages(String conversationId) {
        getConversation(conversationId);
        log.debug("Chargement mémoire — ChatMemory key=conversationId={}", conversationId);
        return chatMemory.get(conversationId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Mémoire
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ajoute un message utilisateur à la mémoire JDBC d'une conversation.
     *
     * <p>Le message est inséré dans {@code SPRING_AI_CHAT_MEMORY} avec le
     * {@code conversationId} comme clé d'isolation. Le timestamp est géré par
     * {@code SqliteChatMemoryRepositoryDialect} (epoch millis).</p>
     *
     * @param conversationId l'ID de la conversation cible
     * @param content        le contenu du message utilisateur
     * @throws ConversationNotFoundException si la conversation n'existe pas
     */
    public void addMessage(String conversationId, String content) {
        getConversation(conversationId);

        log.debug("addMessage — conversationId injecté={}", conversationId);
        log.debug("ChatMemory key — conversationId={}", conversationId);

        List<Message> existing = chatMemory.get(conversationId);
        boolean first = existing == null || existing.isEmpty();

        chatMemory.add(conversationId, new UserMessage(content));

        if (first) {
            log.info("Premier message soumis — conversationId={}", conversationId);
            // E2-M5 : déclencher la génération de titre de façon asynchrone (@Async, non-bloquant)
            ConversationTitleService titleService = titleServiceProvider.getIfAvailable();
            if (titleService != null) {
                titleService.generateTitle(conversationId, content);
                log.debug("Génération de titre déclenchée — conversationId={}", conversationId);
            }
        } else {
            log.info("Message soumis — conversationId={}, total messages={}",
                    conversationId, existing.size() + 1);
        }
    }

    /**
     * Envoie un message utilisateur au LLM et retourne la réponse assistant.
     *
     * <p>Le premier message déclenche aussi la génération asynchrone du titre de conversation.
     * La persistance USER / ASSISTANT est assurée par l'advisor Spring AI, sans écriture
     * manuelle dans {@link ChatMemory}.</p>
     *
     * @param conversationId l'ID de la conversation cible
     * @param content        le contenu du message utilisateur
     * @return contenu textuel de la réponse assistant
     * @throws ConversationNotFoundException si la conversation n'existe pas
     */
    @Transactional
    public String chat(String conversationId, String content) {
        ConversationEntity conversation = getConversation(conversationId);

        log.debug("chat — conversationId injecté={}", conversationId);
        log.debug("ChatMemory key — conversationId={}", conversationId);

        List<Message> existing = chatMemory.get(conversationId);
        boolean first = existing == null || existing.isEmpty();

        AgentContext context = AgentContext.of(
                "default",
                conversation.getProjectId(),
                conversationId,
                "chat-" + UUID.randomUUID());

        String response;
        agentContextHolder.bind(context);
        try {
            response = chatAgent.chat(conversationId, content);
        } finally {
            agentContextHolder.clear();
        }

        if (first) {
            log.info("Premier message conversationnel soumis — conversationId={}", conversationId);
            ConversationTitleService titleService = titleServiceProvider.getIfAvailable();
            if (titleService != null) {
                titleService.generateTitle(conversationId, content);
                log.debug("Génération de titre déclenchée — conversationId={}", conversationId);
            }
        } else {
            log.info("Réponse conversationnelle générée — conversationId={}, messagesAvantAppel={}",
                    conversationId, existing.size());
        }

        return response;
    }

    /**
     * Bascule le contexte mémoire : ferme la conversation courante et en crée une
     * nouvelle pour le même projet. Logge le changement de clé ChatMemory.
     *
     * <p>Utilisé pour démarrer une nouvelle session tout en conservant l'historique
     * de la conversation précédente en DB.</p>
     *
     * @param currentConversationId ID de la conversation courante
     * @param projectId             ID du projet (doit être ACTIVE)
     * @return la nouvelle conversation créée
     * @throws ConversationNotFoundException       si la conversation courante est introuvable
     * @throws ProjectArchivedConversationException si le projet est archivé
     */
    @Transactional
    public ConversationEntity switchContext(String currentConversationId, String projectId) {
        getConversation(currentConversationId);
        ConversationEntity newConv = startConversation(projectId);
        log.info("Basculement de contexte mémoire — {} → {}", currentConversationId, newConv.getId());
        return newConv;
    }

    /**
     * Supprime une conversation et purge sa mémoire Spring AI associée avant suppression DB.
     *
     * @param conversationId l'ID de la conversation à supprimer
     * @throws ConversationNotFoundException si la conversation n'existe pas
     */
    @Transactional
    public void delete(String conversationId) {
        ConversationEntity conversation = getConversation(conversationId);
        log.info("Suppression conversation — conversationId={}, projectId={}",
                conversationId, conversation.getProjectId());
        log.debug("Purge ChatMemory avant suppression — conversationId={}", conversationId);
        chatMemory.clear(conversationId);
        conversationRepository.delete(conversation);
        log.info("Conversation supprimée — conversationId={}", conversationId);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper
    // ─────────────────────────────────────────────────────────────────────────

    private ProjectEntity findProjectOrThrow(String projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }
}
