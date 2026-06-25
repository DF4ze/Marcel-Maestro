package fr.ses10doigts.mm.app.rest.dto;

import fr.ses10doigts.mm.starter.project.ProjectWorkspaceEntity;
import lombok.Value;

/**
 * DTO de réponse pour un dossier externe déclaré ({@code project_workspace}).
 */
@Value
public class ProjectWorkspaceResponse {

    String id;
    String projectId;
    String path;
    String addedAt;

    /**
     * Construit le DTO depuis l'entité JPA.
     *
     * @param entity l'entité workspace
     * @return le DTO correspondant
     */
    public static ProjectWorkspaceResponse from(ProjectWorkspaceEntity entity) {
        return new ProjectWorkspaceResponse(
                entity.getId(),
                entity.getProject().getId(),
                entity.getPath(),
                entity.getAddedAt());
    }
}
