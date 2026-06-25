package fr.ses10doigts.mm.starter.conversation;

/**
 * Statut du cycle de vie d'une conversation Marcel Maestro (E2-M2).
 *
 * <p>{@code OPEN} : la conversation est active et accepte de nouveaux messages.</p>
 * <p>{@code ARCHIVED} : conversation terminée, en lecture seule.</p>
 *
 * <p>Le projet parent doit être {@code ACTIVE} pour créer une conversation {@code OPEN}.
 * Les conversations d'un projet {@code ARCHIVED} passent à {@code ARCHIVED}
 * automatiquement lors de l'archivage du projet (E2-M4, hors scope E2-M2).</p>
 */
public enum ConversationStatus {

    /** Conversation active — accepte de nouveaux messages. */
    OPEN,

    /** Conversation archivée — plus de nouveaux messages. */
    ARCHIVED
}
