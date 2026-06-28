package fr.ses10doigts.mm.starter.conversation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Repository Spring Data JPA pour {@link ConversationEntity}.
 */
public interface ConversationRepository extends JpaRepository<ConversationEntity, String> {

    /**
     * Retourne toutes les conversations d'un projet, dans l'ordre naturel de la DB.
     *
     * @param projectId l'ID du projet
     * @return liste des conversations (peut etre vide)
     */
    List<ConversationEntity> findAllByProjectId(String projectId);

    /**
     * Retourne les conversations d'un projet pour un statut donne.
     *
     * @param projectId l'ID du projet
     * @param status le statut a filtrer
     * @return liste des conversations filtrees
     */
    List<ConversationEntity> findAllByProjectIdAndStatus(String projectId, ConversationStatus status);

    /**
     * Compte les conversations d'un projet ayant un statut donne.
     *
     * @param projectId l'ID du projet
     * @param status le statut a compter
     * @return le nombre de conversations correspondantes
     */
    long countByProjectIdAndStatus(String projectId, ConversationStatus status);

    /**
     * Compte en une seule requete les conversations OPEN pour une liste de projets.
     *
     * @param projectIds liste des IDs de projets a agreger
     * @param status statut a compter
     * @return projections {@code [projectId, count]}
     */
    @Query("SELECT c.projectId, COUNT(c) FROM ConversationEntity c "
            + "WHERE c.projectId IN :projectIds AND c.status = :status "
            + "GROUP BY c.projectId")
    List<Object[]> countOpenByProjectIds(List<String> projectIds, ConversationStatus status);

    /**
     * Retourne la derniere activite connue par projet via les colonnes
     * persistées de conversation.
     *
     * @param projectIds liste des IDs de projets a agreger
     * @return projections {@code [projectId, lastActivityIso]}
     */
    @Query("SELECT c.projectId, MAX(COALESCE(c.lastMessageAt, c.startedAt)) FROM ConversationEntity c "
            + "WHERE c.projectId IN :projectIds "
            + "GROUP BY c.projectId")
    List<Object[]> findLastActivityByProjectIds(List<String> projectIds);

    /**
     * Convertit le resultat de {@link #countOpenByProjectIds} en map projectId -> count.
     *
     * @param rows resultat de la requete agregee
     * @return map projectId -> nombre de conversations ouvertes
     */
    static Map<String, Long> toOpenCountMap(List<Object[]> rows) {
        return rows.stream().collect(
                Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]));
    }
}
