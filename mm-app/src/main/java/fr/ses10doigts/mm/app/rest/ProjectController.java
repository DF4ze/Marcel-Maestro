package fr.ses10doigts.mm.app.rest;

import fr.ses10doigts.mm.app.project.ProjectNameConflictException;
import fr.ses10doigts.mm.app.project.ProjectNotFoundException;
import fr.ses10doigts.mm.app.project.ProtectedProjectMutationException;
import fr.ses10doigts.mm.app.project.ProjectService;
import fr.ses10doigts.mm.app.rest.dto.AddWorkspaceRequest;
import fr.ses10doigts.mm.app.rest.dto.CreateProjectRequest;
import fr.ses10doigts.mm.app.rest.dto.ImportProjectRequest;
import fr.ses10doigts.mm.app.rest.dto.ProjectResponse;
import fr.ses10doigts.mm.app.rest.dto.ProjectWorkspaceResponse;
import fr.ses10doigts.mm.app.rest.dto.UpdateProjectRequest;
import fr.ses10doigts.mm.starter.project.ProjectEntity;
import fr.ses10doigts.mm.starter.project.ProjectStatus;
import fr.ses10doigts.mm.starter.project.ProjectWorkspaceEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contrôleur REST de gestion des projets Marcel Maestro (E2-M1).
 *
 * <p>Endpoints conformes à la table §4 de {@code docs/evolution-2-projets/conception.md}.
 * Toutes les réponses sont en JSON. Les erreurs métier sont renvoyées avec le
 * code HTTP approprié et un corps {@code {"error": "message"}}.</p>
 *
 * <p>Validation de base inline : nom non vide, chemin non vide pour les imports
 * et workspaces externes. La validation applicative approfondie (chemin absolu,
 * existence du dossier) est déléguée à {@link ProjectService}.</p>
 */
@RestController
@RequestMapping("/projects")
@RequiredArgsConstructor
@Slf4j
public class ProjectController {

    private final ProjectService projectService;

    // ─────────────────────────────────────────────────────────────────────────
    // CRUD projets
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Crée un nouveau projet.
     *
     * @param request body JSON {@code {"name": "..."}}
     * @return 201 Created avec le projet créé, ou 409 Conflict si slug en conflit,
     *         ou 400 Bad Request si le nom est vide
     */
    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateProjectRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return badRequest("Le champ 'name' est obligatoire et ne peut pas être vide.");
        }
        ProjectEntity project = projectService.create(request.name());
        ProjectResponse response = toFullResponse(project);
        log.info("POST /projects — projet créé id={}", project.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Liste les projets. Par défaut retourne uniquement les ACTIVE.
     *
     * @param status filtre optionnel : {@code ACTIVE}, {@code ARCHIVED} ou {@code ALL}
     * @return 200 OK avec la liste
     */
    @GetMapping
    public List<ProjectResponse> list(
            @RequestParam(name = "status", defaultValue = "ACTIVE") String status) {
        List<ProjectEntity> projects = switch (status.toUpperCase()) {
            case "ALL" -> projectService.findAll();
            case "ARCHIVED" -> projectService.findByStatus(ProjectStatus.ARCHIVED);
            default -> projectService.findByStatus(ProjectStatus.ACTIVE);
        };
        return projects.stream().map(ProjectResponse::from).toList();
    }

    /**
     * Retourne le détail d'un projet avec ses dossiers externes.
     *
     * @param id l'ID du projet
     * @return 200 OK avec le projet complet, ou 404 si introuvable
     */
    @GetMapping("/{id}")
    public ResponseEntity<ProjectResponse> getById(@PathVariable String id) {
        ProjectEntity project = projectService.findById(id);
        ProjectResponse response = toFullResponse(project);
        return ResponseEntity.ok(response);
    }

    /**
     * Modifie le nom d'affichage d'un projet.
     *
     * @param id      l'ID du projet
     * @param request body JSON {@code {"name": "..."}}
     * @return 200 OK avec le projet mis à jour, ou 404/400
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable String id,
                                     @RequestBody UpdateProjectRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return badRequest("Le champ 'name' est obligatoire et ne peut pas être vide.");
        }
        ProjectEntity project = projectService.updateName(id, request.name());
        return ResponseEntity.ok(toFullResponse(project));
    }

    /**
     * Archive un projet (ACTIVE → ARCHIVED).
     *
     * @param id l'ID du projet
     * @return 200 OK avec le projet archivé, ou 404
     */
    @PostMapping("/{id}/archive")
    public ResponseEntity<ProjectResponse> archive(@PathVariable String id) {
        ProjectEntity project = projectService.archive(id);
        log.info("POST /projects/{}/archive — ok", id);
        return ResponseEntity.ok(toFullResponse(project));
    }

    /**
     * Désarchive un projet (ARCHIVED → ACTIVE).
     *
     * @param id l'ID du projet
     * @return 200 OK avec le projet désarchivé, ou 404
     */
    @PostMapping("/{id}/unarchive")
    public ResponseEntity<ProjectResponse> unarchive(@PathVariable String id) {
        ProjectEntity project = projectService.unarchive(id);
        log.info("POST /projects/{}/unarchive — ok", id);
        return ResponseEntity.ok(toFullResponse(project));
    }

    /**
     * Supprime un projet définitivement (DB + filesystem).
     *
     * @param id l'ID du projet
     * @return 204 No Content si supprimé, 404 si introuvable
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        projectService.delete(id);
        log.info("DELETE /projects/{} — supprimé", id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Importe un dossier existant comme projet sans recréer le dossier.
     *
     * @param request body JSON {@code {"name": "...", "path": "..."}}
     * @return 201 Created avec le projet importé, ou 409/400
     */
    @PostMapping("/import")
    public ResponseEntity<?> importProject(@RequestBody ImportProjectRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            return badRequest("Le champ 'name' est obligatoire.");
        }
        if (request.path() == null || request.path().isBlank()) {
            return badRequest("Le champ 'path' est obligatoire.");
        }
        ProjectEntity project = projectService.importExisting(request.name(), request.path());
        log.info("POST /projects/import — projet importé id={}", project.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(toFullResponse(project));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Workspaces externes
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Ajoute un dossier externe déclaré à un projet.
     *
     * @param id      l'ID du projet
     * @param request body JSON {@code {"path": "..."}}
     * @return 201 Created avec le workspace créé, ou 404/400
     */
    @PostMapping("/{id}/workspaces")
    public ResponseEntity<?> addWorkspace(@PathVariable String id,
                                           @RequestBody AddWorkspaceRequest request) {
        if (request.path() == null || request.path().isBlank()) {
            return badRequest("Le champ 'path' est obligatoire.");
        }
        ProjectWorkspaceEntity ws = projectService.addWorkspace(id, request.path());
        log.info("POST /projects/{}/workspaces — wsId={}", id, ws.getId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectWorkspaceResponse.from(ws));
    }

    /**
     * Retire un dossier externe d'un projet (DB uniquement, le dossier n'est pas supprimé).
     *
     * @param id   l'ID du projet (présent dans l'URL pour la lisibilité REST)
     * @param wsId l'ID du workspace externe à retirer
     * @return 204 No Content
     */
    @DeleteMapping("/{id}/workspaces/{wsId}")
    public ResponseEntity<Void> removeWorkspace(@PathVariable String id,
                                                  @PathVariable String wsId) {
        projectService.removeWorkspace(wsId);
        log.info("DELETE /projects/{}/workspaces/{} — retiré", id, wsId);
        return ResponseEntity.noContent().build();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Gestion des erreurs
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Gère les conflits de slug (409 Conflict).
     *
     * @param ex l'exception levée par ProjectService
     * @return 409 avec le message d'erreur
     */
    @ExceptionHandler(ProjectNameConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(ProjectNameConflictException ex) {
        log.info("Conflit de nom de projet — {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex.getMessage()));
    }

    /**
     * Gère les projets introuvables (404 Not Found).
     *
     * @param ex l'exception levée par ProjectService
     * @return 404 avec le message d'erreur
     */
    @ExceptionHandler(ProjectNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(ProjectNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody(ex.getMessage()));
    }

    /**
     * Gère les arguments invalides (400 Bad Request).
     *
     * @param ex l'exception levée par ProjectService
     * @return 400 avec le message d'erreur
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadArg(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(errorBody(ex.getMessage()));
    }

    @ExceptionHandler(ProtectedProjectMutationException.class)
    public ResponseEntity<Map<String, String>> handleProtectedProject(ProtectedProjectMutationException ex) {
        log.info("Mutation interdite sur projet protege — {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(errorBody(ex.getMessage()));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers privés
    // ─────────────────────────────────────────────────────────────────────────

    private ProjectResponse toFullResponse(ProjectEntity project) {
        List<ProjectWorkspaceResponse> workspaces = projectService
                .findWorkspaces(project.getId())
                .stream()
                .map(ProjectWorkspaceResponse::from)
                .toList();
        return ProjectResponse.from(project, workspaces);
    }

    private ResponseEntity<Map<String, String>> badRequest(String message) {
        return ResponseEntity.badRequest().body(errorBody(message));
    }

    private Map<String, String> errorBody(String message) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", message);
        return body;
    }
}
