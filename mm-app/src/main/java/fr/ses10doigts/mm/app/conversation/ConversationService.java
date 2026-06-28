package fr.ses10doigts.mm.app.conversation;

import fr.ses10doigts.mm.app.project.ProjectBootstrapService;
import fr.ses10doigts.mm.app.project.ProjectNotFoundException;
import fr.ses10doigts.mm.core.agent.AgentContext;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationRepository;
import fr.ses10doigts.mm.starter.conversation.ConversationStatus;
import fr.ses10doigts.mm.starter.hitl.AgentContextHolder;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectRepository;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import java.time.Instant;
import java.util.Comparator;
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
import reactor.core.publisher.Flux;

/**
 * Service applicatif gerant le cycle de vie des conversations et la memoire chat.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationService {

    private static final Comparator<ConversationEntity> ACTIVITY_COMPARATOR =
            Comparator.comparing(ConversationEntity::getLastMessageAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(ConversationEntity::getStartedAt, Comparator.reverseOrder());

    private final ConversationRepository conversationRepository;
    private final ProjectRepository projectRepository;
    private final ChatMemory chatMemory;
    private final ChatAgent chatAgent;
    private final AgentContextHolder agentContextHolder;
    private final ProjectBootstrapService projectBootstrapService;

    /**
     * Service de generation de titre. Optionnel pour ne pas bloquer les tests
     * ou les environnements sans LLM.
     */
    private final ObjectProvider<ConversationTitleService> titleServiceProvider;

    /**
     * Cree une nouvelle conversation pour un projet actif.
     *
     * @param projectId l'ID du projet
     * @return la conversation persistée
     */
    @Transactional
    public ConversationEntity startConversation(String projectId) {
        ProjectEntity project = findProjectOrThrow(projectId);
        if (project.getStatus() == ProjectStatus.ARCHIVED) {
            throw new ProjectArchivedConversationException(projectId);
        }
        boolean firstConversationForProject = conversationRepository.findAllByProjectId(projectId).isEmpty();

        String conversationId = UUID.randomUUID().toString();
        ConversationEntity conv = ConversationEntity.builder()
                .id(conversationId)
                .projectId(projectId)
                .startedAt(Instant.now().toString())
                .status(ConversationStatus.OPEN)
                .messageCount(0)
                .build();
        if (firstConversationForProject) {
            conv.setTitle(ProjectBootstrapService.BOOTSTRAP_CONVERSATION_TITLE);
        }

        ConversationEntity saved = conversationRepository.save(conv);
        if (firstConversationForProject) {
            projectBootstrapService.initializeBootstrapConversation(project, saved.getId());
        }
        log.info("Conversation creee - conversationId={}, projectId={}", conversationId, projectId);
        return saved;
    }

    /**
     * Liste toutes les conversations d'un projet.
     *
     * @param projectId l'ID du projet
     * @return conversations du projet
     */
    @Transactional(readOnly = true)
    public List<ConversationEntity> listByProject(String projectId) {
        findProjectOrThrow(projectId);
        return conversationRepository.findAllByProjectId(projectId);
    }

    /**
     * Liste les conversations d'un projet pour un filtre de statut.
     *
     * @param projectId l'ID du projet
     * @param statusFilter {@code OPEN}, {@code ARCHIVED} ou {@code ALL}
     * @return conversations triees par activite recente puis date de creation
     */
    @Transactional(readOnly = true)
    public List<ConversationEntity> listByProject(String projectId, String statusFilter) {
        findProjectOrThrow(projectId);
        List<ConversationEntity> conversations = switch (normalizeStatusFilter(statusFilter)) {
            case "ALL" -> conversationRepository.findAllByProjectId(projectId);
            case "ARCHIVED" -> conversationRepository.findAllByProjectIdAndStatus(
                    projectId, ConversationStatus.ARCHIVED);
            default -> conversationRepository.findAllByProjectIdAndStatus(projectId, ConversationStatus.OPEN);
        };
        return conversations.stream()
                .sorted(ACTIVITY_COMPARATOR)
                .toList();
    }

    /**
     * Charge une conversation par son ID.
     *
     * @param conversationId l'ID de la conversation
     * @return la conversation
     */
    @Transactional(readOnly = true)
    public ConversationEntity getConversation(String conversationId) {
        return conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ConversationNotFoundException(conversationId));
    }

    /**
     * Retourne les messages persistés pour une conversation.
     *
     * @param conversationId l'ID de la conversation
     * @return historique mémoire
     */
    @Transactional(readOnly = true)
    public List<Message> getMessages(String conversationId) {
        getConversation(conversationId);
        log.debug("Chargement memoire - ChatMemory key=conversationId={}", conversationId);
        return chatMemory.get(conversationId);
    }

    /**
     * Ajoute un message utilisateur brut dans la memoire.
     *
     * @param conversationId l'ID de la conversation
     * @param content le contenu utilisateur
     */
    @Transactional
    public void addMessage(String conversationId, String content) {
        ConversationEntity conversation = getConversation(conversationId);
        ensureConversationWritable(conversation);

        log.debug("addMessage - conversationId injecte={}", conversationId);
        log.debug("ChatMemory key - conversationId={}", conversationId);

        List<Message> existing = chatMemory.get(conversationId);
        int existingCount = existing == null ? 0 : existing.size();
        boolean first = existingCount == 0;

        chatMemory.add(conversationId, new UserMessage(content));
        touchConversationActivity(conversation, existingCount + 1, Instant.now());

        if (first) {
            log.info("Premier message soumis - conversationId={}", conversationId);
            ConversationTitleService titleService = titleServiceProvider.getIfAvailable();
            if (titleService != null) {
                titleService.generateTitle(conversationId, content);
                log.debug("Generation de titre declenchee - conversationId={}", conversationId);
            }
        } else {
            log.info("Message soumis - conversationId={}, totalMessages={}",
                    conversationId, existingCount + 1);
        }
    }

    /**
     * Envoie un message a l'agent et retourne la reponse.
     *
     * @param conversationId l'ID de la conversation
     * @param content le message utilisateur
     * @return la reponse assistant
     */
    @Transactional
    public String chat(String conversationId, String content) {
        ConversationEntity conversation = getConversation(conversationId);
        ensureConversationWritable(conversation);
        ChatExecutionContext execution = prepareChatExecution(conversation, content);

        String response;
        agentContextHolder.bind(execution.context());
        try {
            response = chatAgent.chat(conversationId, content);
        } finally {
            agentContextHolder.clear();
        }

        finalizeChatExecution(conversation, content, execution.existingCount(), execution.firstMessage(), false);
        return response;
    }

    /**
     * Envoie un message a l'agent et retourne un flux SSE-compatible de tokens.
     *
     * @param conversationId l'ID de la conversation
     * @param content le message utilisateur
     * @return flux de tokens assistant
     */
    public Flux<String> chatStream(String conversationId, String content) {
        ConversationEntity conversation = getConversation(conversationId);
        ensureConversationWritable(conversation);
        ChatExecutionContext execution = prepareChatExecution(conversation, content);

        return Flux.defer(() -> {
            agentContextHolder.bind(execution.context());
            return chatAgent.stream(conversationId, content)
                    .doOnComplete(() -> finalizeChatExecution(
                            conversation, content, execution.existingCount(), execution.firstMessage(), true))
                    .doOnError(error -> log.error(
                            "Flux conversationnel en erreur - conversationId={}", conversationId, error))
                    .doFinally(signalType -> {
                        agentContextHolder.clear();
                        log.info("Flux conversationnel ferme - conversationId={}, signal={}",
                                conversationId, signalType);
                    });
        });
    }

    /**
     * Cree une nouvelle conversation pour changer de contexte.
     *
     * @param currentConversationId conversation courante
     * @param projectId projet cible
     * @return nouvelle conversation
     */
    @Transactional
    public ConversationEntity switchContext(String currentConversationId, String projectId) {
        getConversation(currentConversationId);
        ConversationEntity newConv = startConversation(projectId);
        log.info("Basculement de contexte memoire - {} -> {}", currentConversationId, newConv.getId());
        return newConv;
    }

    /**
     * Renomme une conversation existante.
     *
     * @param conversationId l'ID de la conversation
     * @param newTitle le nouveau titre non vide
     * @return l'entite mise a jour
     */
    @Transactional
    public ConversationEntity rename(String conversationId, String newTitle) {
        ConversationEntity conversation = getConversation(conversationId);
        String normalizedTitle = requireNonBlankTitle(newTitle);
        String previousTitle = conversation.getTitle();
        conversation.setTitle(normalizedTitle);
        ConversationEntity saved = conversationRepository.save(conversation);
        log.info("Conversation renommee - conversationId={}, ancienTitre='{}', nouveauTitre='{}'",
                conversationId, previousTitle, normalizedTitle);
        return saved;
    }

    /**
     * Archive une conversation ouverte sans purger sa memoire.
     *
     * @param conversationId l'ID de la conversation
     * @return l'entite mise a jour
     */
    @Transactional
    public ConversationEntity archive(String conversationId) {
        return archive(conversationId, null);
    }

    /**
     * Archive une conversation ouverte sans purger sa memoire.
     *
     * @param conversationId l'ID de la conversation
     * @param reason raison fonctionnelle d'archivage, optionnelle
     * @return l'entite mise a jour
     */
    @Transactional
    public ConversationEntity archive(String conversationId, String reason) {
        ConversationEntity conversation = getConversation(conversationId);
        if (conversation.getStatus() == ConversationStatus.ARCHIVED) {
            throw new ConversationAlreadyArchivedException(conversationId);
        }
        conversation.setStatus(ConversationStatus.ARCHIVED);
        ConversationEntity saved = conversationRepository.save(conversation);
        log.info("Conversation archivee - conversationId={}, projectId={}, reason='{}'",
                conversationId, conversation.getProjectId(), reason == null ? "" : reason);
        return saved;
    }

    /**
     * Supprime une conversation et purge sa memoire Spring AI.
     *
     * @param conversationId l'ID de la conversation
     */
    @Transactional
    public void delete(String conversationId) {
        ConversationEntity conversation = getConversation(conversationId);
        log.info("Suppression conversation - conversationId={}, projectId={}",
                conversationId, conversation.getProjectId());
        log.debug("Purge ChatMemory avant suppression - conversationId={}", conversationId);
        chatMemory.clear(conversationId);
        conversationRepository.delete(conversation);
        log.info("Conversation supprimee - conversationId={}", conversationId);
    }

    /**
     * Charge un projet ou leve une exception metier.
     *
     * @param projectId l'ID du projet
     * @return le projet
     */
    private ProjectEntity findProjectOrThrow(String projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private ChatExecutionContext prepareChatExecution(ConversationEntity conversation, String content) {
        String conversationId = conversation.getId();
        log.debug("chat - conversationId injecte={}", conversationId);
        log.debug("ChatMemory key - conversationId={}", conversationId);

        List<Message> existing = chatMemory.get(conversationId);
        int existingCount = existing == null ? 0 : existing.size();
        boolean first = existingCount == 0;

        projectBootstrapService.appendUserInputToProject(conversation.getProjectId(), conversationId, content);
        if (first) {
            ConversationTitleService titleService = titleServiceProvider.getIfAvailable();
            if (titleService != null) {
                titleService.generateTitle(conversationId, content);
                log.debug("Generation de titre declenchee - conversationId={}", conversationId);
            }
        }

        AgentContext context = AgentContext.of(
                "default",
                conversation.getProjectId(),
                conversationId,
                "chat-" + UUID.randomUUID());
        return new ChatExecutionContext(existingCount, first, context);
    }

    private void finalizeChatExecution(
            ConversationEntity conversation,
            String content,
            int existingCount,
            boolean firstMessage,
            boolean streamed) {
        List<Message> updated = chatMemory.get(conversation.getId());
        int updatedCount = updated == null ? 0 : updated.size();
        if (updatedCount != existingCount) {
            touchConversationActivity(conversation, updatedCount, Instant.now());
        }

        if (firstMessage) {
            log.info("{} premier message conversationnel soumis - conversationId={}, contentLength={}",
                    streamed ? "Flux" : "Chat",
                    conversation.getId(),
                    content == null ? 0 : content.length());
            return;
        }

        log.info("{} conversationnel genere - conversationId={}, messagesAvantAppel={}, messagesApresAppel={}",
                streamed ? "Flux" : "Reponse",
                conversation.getId(),
                existingCount,
                updatedCount);
    }

    /**
     * Met a jour l'activite persistée d'une conversation apres ecriture memoire.
     *
     * @param conversation conversation ciblee
     * @param messageCount nouveau nombre total de messages
     * @param lastMessageAt instant du dernier message persisté
     */
    private void touchConversationActivity(
            ConversationEntity conversation,
            int messageCount,
            Instant lastMessageAt) {
        conversation.setMessageCount(messageCount);
        conversation.setLastMessageAt(lastMessageAt.toString());
        conversationRepository.save(conversation);
        log.debug("Activite conversation mise a jour - conversationId={}, messageCount={}, lastMessageAt={}",
                conversation.getId(), messageCount, conversation.getLastMessageAt());
    }

    /**
     * Valide et normalise un filtre de statut conversation.
     *
     * @param statusFilter filtre recu du controller
     * @return filtre normalise
     */
    private String normalizeStatusFilter(String statusFilter) {
        if (statusFilter == null || statusFilter.isBlank()) {
            return "OPEN";
        }
        return statusFilter.trim().toUpperCase();
    }

    /**
     * Valide un titre manuel de conversation.
     *
     * @param title titre saisi
     * @return titre normalise
     */
    private String requireNonBlankTitle(String title) {
        if (title == null || title.isBlank()) {
            throw new IllegalArgumentException("Le champ 'title' est obligatoire et ne peut pas etre vide.");
        }
        return title.trim();
    }

    /**
     * Refuse toute ecriture sur une conversation archivee.
     *
     * @param conversation conversation ciblee
     */
    private void ensureConversationWritable(ConversationEntity conversation) {
        if (conversation.getStatus() == ConversationStatus.ARCHIVED) {
            log.info("Tentative d'ecriture refusee sur conversation archivee - conversationId={}, projectId={}",
                    conversation.getId(), conversation.getProjectId());
            throw new ArchivedConversationReadOnlyException(conversation.getId());
        }
    }

    private record ChatExecutionContext(int existingCount, boolean firstMessage, AgentContext context) { }
}
