package fr.ses10doigts.mm.app.rest.dto;

/**
 * Corps de la requête {@code POST /projects}.
 *
 * @param name nom d'affichage du projet (non vide)
 */
public record CreateProjectRequest(String name) {}
