package fr.ses10doigts.mm.app.rest;

import fr.ses10doigts.mm.app.conversation.ArchivedConversationReadOnlyException;
import fr.ses10doigts.mm.app.conversation.ConversationBriefService;
import fr.ses10doigts.mm.app.conversation.ConversationAlreadyArchivedException;
import fr.ses10doigts.mm.app.conversation.ConversationNotFoundException;
import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.app.conversation.ProjectArchivedConversationException;
import fr.ses10doigts.mm.app.project.ProjectNotFoundException;
import fr.ses10doigts.mm.app.rest.dto.AddMessageRequest;
import fr.ses10doigts.mm.app.rest.dto.ConversationBriefResponse;
import fr.ses10doigts.mm.app.rest.dto.ConversationTaskResponse;
import fr.ses10doigts.mm.app.rest.dto.ConversationResponse;
import fr.ses10doigts.mm.app.rest.dto.MessageResponse;
import fr.ses10doigts.mm.app.rest.dto.UpdateConversationRequest;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import fr.ses10doigts.mm.starter.conversation.ConversationTaskRepository;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Controleur REST de gestion des conversations Marcel Maestro.
 */
@RestController
@RequestMapping("/projects/{projectId}/conversations")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private final ConversationService conversationService;
    private final ConversationBriefService conversationBriefService;
    private final ConversationTaskRepository conversationTaskRepository;

    @Value("${mm.chat.sse.timeout-ms:180000}")
    private long sseTimeoutMs;

    /**
     * Demarre une nouvelle conversation pour un projet actif.
     *
     * @param projectId l'ID du projet
     * @return 201 Created avec la conversation creee
     */
    @PostMapping
    public ResponseEntity<ConversationResponse> start(@PathVariable String projectId) {
        ConversationEntity conv = conversationService.startConversation(projectId);
        log.info("POST /projects/{}/conversations - conversationId={}", projectId, conv.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationResponse.from(conv));
    }

    /**
     * Liste les conversations d'un projet avec filtre de statut optionnel.
     *
     * @param projectId l'ID du projet
     * @param status filtre {@code OPEN}, {@code ARCHIVED} ou {@code ALL}
     * @return 200 OK avec la liste triee
     */
    @GetMapping
    public List<ConversationResponse> list(
            @PathVariable String projectId,
            @RequestParam(name = "status", defaultValue = "OPEN") String status) {
        return conversationService.listByProject(projectId, status)
                .stream()
                .map(ConversationResponse::from)
                .toList();
    }

    /**
     * Retourne le detail d'une conversation.
     *
     * @param projectId l'ID du projet
     * @param conversationId l'ID de la conversation
     * @return 200 OK avec la conversation
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationResponse> get(
            @PathVariable String projectId,
            @PathVariable String conversationId) {
        ConversationEntity conv = conversationService.getConversation(conversationId);
        return ResponseEntity.ok(ConversationResponse.from(conv));
    }

    /**
     * Renomme manuellement une conversation existante.
     *
     * @param projectId l'ID du projet
     * @param conversationId l'ID de la conversation
     * @param request corps JSON {@code {"title": "..."}}
     * @return 200 OK avec la conversation mise a jour
     */
    @PatchMapping("/{conversationId}")
    public ResponseEntity<?> rename(
            @PathVariable String projectId,
            @PathVariable String conversationId,
            @RequestBody UpdateConversationRequest request) {
        if (request.title() == null || request.title().isBlank()) {
            return badRequest("Le champ 'title' est obligatoire et ne peut pas etre vide.");
        }
        ConversationEntity conversation = conversationService.rename(conversationId, request.title());
        log.info("PATCH /projects/{}/conversations/{} - titre mis a jour", projectId, conversationId);
        return ResponseEntity.ok(ConversationResponse.from(conversation));
    }

    /**
     * Archive une conversation sans purger sa memoire.
     *
     * @param projectId l'ID du projet
     * @param conversationId l'ID de la conversation
     * @return 200 OK avec la conversation archivee
     */
    @PostMapping("/{conversationId}/archive")
    public ResponseEntity<ConversationResponse> archive(
            @PathVariable String projectId,
            @PathVariable String conversationId) {
        ConversationEntity conversation = conversationService.archive(conversationId);
        log.info("POST /projects/{}/conversations/{}/archive - ok", projectId, conversationId);
        return ResponseEntity.ok(ConversationResponse.from(conversation));
    }

    /**
     * Retourne les messages en memoire d'une conversation.
     *
     * @param projectId l'ID du projet
     * @param conversationId l'ID de la conversation
     * @return 200 OK avec la liste des messages
     */
    @GetMapping("/{conversationId}/messages")
    public List<MessageResponse> messages(
            @PathVariable String projectId,
            @PathVariable String conversationId) {
        return conversationService.getMessages(conversationId)
                .stream()
                .map(MessageResponse::from)
                .toList();
    }

    /**
     * Retourne un brief textuel de la conversation courante.
     *
     * @param projectId l'ID du projet
     * @param conversationId l'ID de la conversation
     * @return 200 OK avec le brief genere
     */
    @GetMapping("/{conversationId}/brief")
    public ConversationBriefResponse brief(
            @PathVariable String projectId,
            @PathVariable String conversationId) {
        return new ConversationBriefResponse(
                conversationId,
                conversationBriefService.brief(conversationId));
    }

    /**
     * Retourne les taches deleguees depuis une conversation.
     *
     * @param projectId l'ID du projet
     * @param conversationId l'ID de la conversation
     * @return 200 OK avec les taches triees par soumission decroissante
     */
    @GetMapping("/{conversationId}/tasks")
    public List<ConversationTaskResponse> tasks(
            @PathVariable String projectId,
            @PathVariable String conversationId) {
        conversationService.getConversation(conversationId);
        return conversationTaskRepository.findAllByConversationIdOrderBySubmittedAtDesc(conversationId)
                .stream()
                .map(ConversationTaskResponse::from)
                .toList();
    }

    /**
     * Envoie un message utilisateur a Marcel et retourne la reponse assistant.
     *
     * @param projectId l'ID du projet
     * @param conversationId l'ID de la conversation
     * @param request corps JSON {@code {"content": "..."}}
     * @return 200 OK avec la reponse assistant
     */
    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> addMessage(
            @PathVariable String projectId,
            @PathVariable String conversationId,
            @RequestBody AddMessageRequest request) {

        if (request.content() == null || request.content().isBlank()) {
            return badRequest("Le champ 'content' est obligatoire et ne peut pas etre vide.");
        }

        String response = conversationService.chat(conversationId, request.content());
        log.info("POST /projects/{}/conversations/{}/messages - reponse assistant generee",
                projectId, conversationId);
        return ResponseEntity.ok(MessageResponse.assistant(response));
    }

    /**
     * Envoie un message utilisateur a Marcel et diffuse la reponse en SSE.
     *
     * @param projectId l'ID du projet
     * @param conversationId l'ID de la conversation
     * @param request corps JSON {@code {"content": "..."}}
     * @return flux SSE de tokens puis evenement {@code done}
     */
    @PostMapping(value = "/{conversationId}/messages", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(
            @PathVariable String projectId,
            @PathVariable String conversationId,
            @RequestBody AddMessageRequest request) {
        if (request.content() == null || request.content().isBlank()) {
            throw new IllegalArgumentException("Le champ 'content' est obligatoire et ne peut pas etre vide.");
        }

        SseEmitter emitter = new SseEmitter(sseTimeoutMs);
        log.info("SSE conversation ouverte - projectId={}, conversationId={}, timeoutMs={}",
                projectId, conversationId, sseTimeoutMs);

        Thread.startVirtualThread(() -> {
            try {
                conversationService.chatStream(conversationId, request.content())
                        .toStream()
                        .forEach(token -> sendToken(emitter, conversationId, token));
                emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                emitter.complete();
                log.info("SSE conversation terminee - projectId={}, conversationId={}",
                        projectId, conversationId);
            } catch (Exception e) {
                log.error("SSE stream error - conversationId={}", conversationId, e);
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    /**
     * Projet introuvable -> 404.
     *
     * @param ex l'exception
     * @return 404 avec le message d'erreur
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleProjectNotFound(ProjectNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(ex.getMessage()));
    }

    /**
     * Conversation introuvable -> 404.
     *
     * @param ex l'exception
     * @return 404 avec le message d'erreur
     */
    @ExceptionHandler(ConversationNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleConversationNotFound(
            ConversationNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(ex.getMessage()));
    }

    /**
     * Conversation deja archivee -> 409 Conflict.
     *
     * @param ex l'exception
     * @return 409 avec le message d'erreur
     */
    @ExceptionHandler(ConversationAlreadyArchivedException.class)
    public ResponseEntity<Map<String, String>> handleConversationArchivedConflict(
            ConversationAlreadyArchivedException ex) {
        log.info("Conflit d'archivage conversation - {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex.getMessage()));
    }

    /**
     * Conversation archivee en ecriture -> 409 Conflict.
     *
     * @param ex l'exception
     * @return 409 avec le message d'erreur
     */
    @ExceptionHandler(ArchivedConversationReadOnlyException.class)
    public ResponseEntity<Map<String, String>> handleArchivedConversationReadOnly(
            ArchivedConversationReadOnlyException ex) {
        log.info("Ecriture refusee sur conversation archivee - {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex.getMessage()));
    }

    /**
     * Projet archive -> 409 Conflict.
     *
     * @param ex l'exception
     * @return 409 avec le message d'erreur
     */
    @ExceptionHandler(ProjectArchivedConversationException.class)
    public ResponseEntity<Map<String, String>> handleArchived(
            ProjectArchivedConversationException ex) {
        log.info("Tentative de conversation sur projet archive - {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex.getMessage()));
    }

    /**
     * Validation applicative -> 400 Bad Request.
     *
     * @param ex l'exception
     * @return 400 avec le message d'erreur
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadRequest(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(errorBody(ex.getMessage()));
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    private void sendToken(SseEmitter emitter, String conversationId, String token) {
        try {
            if (log.isDebugEnabled()) {
                log.debug("SSE token - conversationId={}, token='{}'", conversationId, token);
            }
            emitter.send(SseEmitter.event().data(token));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
