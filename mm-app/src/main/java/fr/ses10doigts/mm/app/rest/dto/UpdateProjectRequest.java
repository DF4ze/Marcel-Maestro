package fr.ses10doigts.mm.app.rest.dto;

/**
 * Corps de la requête {@code PUT /projects/{id}}.
 *
 * @param name nouveau nom d'affichage du projet (non vide)
 */
public record UpdateProjectRequest(String name) {}
