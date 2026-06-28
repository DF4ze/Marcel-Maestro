package fr.ses10doigts.mm.starter.conversation;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA des liens conversation -> taches.
 */
public interface ConversationTaskRepository extends JpaRepository<ConversationTaskEntity, String> {

    /**
     * Liste les taches d'une conversation de la plus recente a la plus ancienne.
     *
     * @param conversationId identifiant de conversation
     * @return liens conversation/tache tries par date de soumission decroissante
     */
    List<ConversationTaskEntity> findAllByConversationIdOrderBySubmittedAtDesc(String conversationId);

    /**
     * Retrouve le lien conversation/tache associe a un identifiant de tache moteur.
     *
     * @param taskId identifiant de la tache soumise au moteur
     * @return lien conversation/tache si present
     */
    Optional<ConversationTaskEntity> findByTaskId(String taskId);
}
