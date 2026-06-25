package fr.ses10doigts.mm.app.rest.dto;

/**
 * Corps de la requête {@code POST /projects/{id}/workspaces}.
 *
 * @param path chemin absolu du dossier externe à déclarer (doit exister)
 */
public record AddWorkspaceRequest(String path) {}
