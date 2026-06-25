package fr.ses10doigts.mm.app.rest.dto;

/**
 * Corps de la requête {@code POST /projects/import}.
 *
 * @param name nom d'affichage du projet (non vide)
 * @param path chemin absolu du dossier existant à importer (non vide)
 */
public record ImportProjectRequest(String name, String path) {}
