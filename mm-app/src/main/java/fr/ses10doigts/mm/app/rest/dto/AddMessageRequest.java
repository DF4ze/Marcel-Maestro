package fr.ses10doigts.mm.app.rest.dto;

/**
 * Corps de la requête {@code POST .../messages} — ajout d'un message utilisateur
 * dans la mémoire d'une conversation (E2-M2).
 *
 * @param content le texte du message (obligatoire, non vide)
 */
public record AddMessageRequest(String content) {}
