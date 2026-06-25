package fr.ses10doigts.mm.app.rest;

import fr.ses10doigts.mm.app.conversation.ConversationNotFoundException;
import fr.ses10doigts.mm.app.conversation.ConversationService;
import fr.ses10doigts.mm.app.conversation.ProjectArchivedConversationException;
import fr.ses10doigts.mm.app.project.ProjectNotFoundException;
import fr.ses10doigts.mm.app.rest.dto.AddMessageRequest;
import fr.ses10doigts.mm.app.rest.dto.ConversationResponse;
import fr.ses10doigts.mm.app.rest.dto.MessageResponse;
import fr.ses10doigts.mm.starter.conversation.ConversationEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST de gestion des conversations Marcel Maestro (E2-M2).
 *
 * <p>Endpoints conformes à la conception §4. Toutes les réponses sont en JSON.
 * Les conversations sont sous-ressources des projets : {@code /projects/{projectId}/conversations}.</p>
 *
 * <table border="1">
 *   <tr><th>Méthode</th><th>URL</th><th>Description</th></tr>
 *   <tr><td>POST</td><td>/projects/{projectId}/conversations</td><td>Démarre une conversation</td></tr>
 *   <tr><td>GET</td><td>/projects/{projectId}/conversations</td><td>Liste les conversations</td></tr>
 *   <tr><td>GET</td><td>/projects/{projectId}/conversations/{id}</td><td>Détail d'une conversation</td></tr>
 *   <tr><td>GET</td><td>/projects/{projectId}/conversations/{id}/messages</td><td>Messages en mémoire</td></tr>
 *   <tr><td>POST</td><td>/projects/{projectId}/conversations/{id}/messages</td><td>Ajoute un message</td></tr>
 * </table>
 */
@RestController
@RequestMapping("/projects/{projectId}/conversations")
@RequiredArgsConstructor
@Slf4j
public class ConversationController {

    private final ConversationService conversationService;

    // ─────────────────────────────────────────────────────────────────────────
    // Conversations
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Démarre une nouvelle conversation pour un projet ACTIVE.
     *
     * @param projectId l'ID du projet
     * @return 201 Created avec la conversation créée, 404 si projet introuvable,
     *         409 Conflict si le projet est archivé
     */
    @PostMapping
    public ResponseEntity<ConversationResponse> start(@PathVariable String projectId) {
        ConversationEntity conv = conversationService.startConversation(projectId);
        log.info("POST /projects/{}/conversations — conversationId={}", projectId, conv.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ConversationResponse.from(conv));
    }

    /**
     * Liste les conversations d'un projet.
     *
     * @param projectId l'ID du projet
     * @return 200 OK avec la liste (peut être vide), 404 si projet introuvable
     */
    @GetMapping
    public List<ConversationResponse> list(@PathVariable String projectId) {
        return conversationService.listByProject(projectId)
                .stream()
                .map(ConversationResponse::from)
                .toList();
    }

    /**
     * Retourne le détail d'une conversation.
     *
     * @param projectId      l'ID du projet (validation d'appartenance en E2-M4, hors scope)
     * @param conversationId l'ID de la conversation
     * @return 200 OK avec la conversation, 404 si introuvable
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<ConversationResponse> get(
            @PathVariable String projectId,
            @PathVariable String conversationId) {
        ConversationEntity conv = conversationService.getConversation(conversationId);
        return ResponseEntity.ok(ConversationResponse.from(conv));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Messages (mémoire JDBC)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Retourne les messages en mémoire d'une conversation.
     *
     * <p>Les messages sont stockés dans {@code SPRING_AI_CHAT_MEMORY} via
     * {@code JdbcChatMemoryRepository} (SQLite). Survit aux redémarrages.</p>
     *
     * @param projectId      l'ID du projet
     * @param conversationId l'ID de la conversation
     * @return 200 OK avec la liste des messages (peut être vide), 404 si conversation introuvable
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
     * Ajoute un message utilisateur dans la mémoire JDBC d'une conversation.
     *
     * @param projectId      l'ID du projet
     * @param conversationId l'ID de la conversation
     * @param request        corps JSON {@code {"content": "..."}}
     * @return 201 Created si ajouté, 400 si contenu vide, 404 si conversation introuvable
     */
    @PostMapping("/{conversationId}/messages")
    public ResponseEntity<?> addMessage(
            @PathVariable String projectId,
            @PathVariable String conversationId,
            @RequestBody AddMessageRequest request) {

        if (request.content() == null || request.content().isBlank()) {
            return badRequest("Le champ 'content' est obligatoire et ne peut pas être vide.");
        }

        conversationService.addMessage(conversationId, request.content());
        log.info("POST /projects/{}/conversations/{}/messages — message ajouté",
                projectId, conversationId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gestion des erreurs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Projet introuvable → 404.
     *
     * @param ex l'exception
     * @return 404 avec le message d'erreur
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleProjectNotFound(ProjectNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(ex.getMessage()));
    }

    /**
     * Conversation introuvable → 404.
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
     * Projet archivé → 409 Conflict.
     *
     * @param ex l'exception
     * @return 409 avec le message d'erreur
     */
    @ExceptionHandler(ProjectArchivedConversationException.class)
    public ResponseEntity<Map<String, String>> handleArchived(
            ProjectArchivedConversationException ex) {
        log.info("Tentative de conversation sur projet archivé — {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
