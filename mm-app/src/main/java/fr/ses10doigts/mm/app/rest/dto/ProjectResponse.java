package fr.ses10doigts.mm.app.rest.dto;

import fr.ses10doigts.mm.starter.project.ProjectEntity;
import java.util.List;
import lombok.Value;

/**
 * DTO de réponse complet pour un projet Marcel Maestro.
 *
 * <p>Utilisé par {@code GET /projects}, {@code GET /projects/{id}} et toutes
 * les opérations qui retournent l'état courant d'un projet.</p>
 */
@Value
public class ProjectResponse {

    String id;
    String name;
    String sanitizedName;
    String workspacePath;
    String status;
    String config;
    String createdAt;
    String updatedAt;
    List<ProjectWorkspaceResponse> workspaces;

    /**
     * Construit le DTO depuis l'entité JPA et la liste de ses workspaces externes.
     *
     * @param entity     l'entité projet
     * @param workspaces les workspaces externes du projet (peut être vide)
     * @return le DTO correspondant
     */
    public static ProjectResponse from(ProjectEntity entity,
                                        List<ProjectWorkspaceResponse> workspaces) {
        return new ProjectResponse(
                entity.getId(),
                entity.getName(),
                entity.getSanitizedName(),
                entity.getWorkspacePath(),
                entity.getStatus().name(),
                entity.getConfig(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                workspaces);
    }

    /**
     * Construit le DTO sans workspaces (liste filtrée ou liste).
     *
     * @param entity l'entité projet
     * @return le DTO avec liste de workspaces vide
     */
    public static ProjectResponse from(ProjectEntity entity) {
        return from(entity, List.of());
    }
}
