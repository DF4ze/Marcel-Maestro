package fr.ses10doigts.mm.starter.conversation;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    /**
     * Compte les conversations d'un projet ayant un statut donné (E2-M5).
     *
     * <p>Utilisé en interne ; préférer {@link #countOpenByProjectIds} pour les
     * agrégations multi-projets (évite le N+1).</p>
     *
     * @param projectId l'ID du projet
     * @param status    le statut à compter (ex : {@link ConversationStatus#OPEN})
     * @return le nombre de conversations correspondantes
     */
    long countByProjectIdAndStatus(String projectId, ConversationStatus status);

    /**
     * Compte en une seule requête les conversations {@link ConversationStatus#OPEN}
     * pour une liste de projets (E2-M5 — anti N+1).
     *
     * <p>Retourne une liste de tableaux {@code [projectId, count]} que l'appelant
     * convertit en {@code Map<String, Long>} via
     * {@link #toOpenCountMap(List)}.</p>
     *
     * @param projectIds liste des IDs de projets à agréger
     * @param status     statut à compter
     * @return projections {@code [projectId, count]} (une ligne par projet ayant ≥ 1 conversation)
     */
    @Query("SELECT c.projectId, COUNT(c) FROM ConversationEntity c " +
           "WHERE c.projectId IN :projectIds AND c.status = :status " +
           "GROUP BY c.projectId")
    List<Object[]> countOpenByProjectIds(List<String> projectIds, ConversationStatus status);

    /**
     * Convertit le résultat de {@link #countOpenByProjectIds} en map projectId → count.
     *
     * @param rows résultat de la requête agrégée
     * @return map projectId → nombre de conversations ouvertes
     */
    static Map<String, Long> toOpenCountMap(List<Object[]> rows) {
        return rows.stream().collect(
                Collectors.toMap(
                        row -> (String) row[0],
                        row -> (Long) row[1]));
    }
}
