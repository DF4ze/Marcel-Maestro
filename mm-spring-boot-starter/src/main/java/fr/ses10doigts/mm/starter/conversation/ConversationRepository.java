package fr.ses10doigts.mm.starter.conversation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository Spring Data JPA pour {@link ConversationEntity} (E2-M2).
 *
 * <p>Scannée automatiquement par {@code @EnableJpaRepositories} déclaré dans
 * {@code MmCoreAutoConfiguration} (basePackages = "fr.ses10doigts.mm.starter").</p>
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {

    /**
     * Retourne toutes les conversations d'un projet, dans l'ordre naturel de la DB.
     *
     * @param projectId l'ID du projet
     * @return liste des conversations (peut être vide)
     */
    List<ConversationEntity> findAllByProjectId(String projectId);
}
