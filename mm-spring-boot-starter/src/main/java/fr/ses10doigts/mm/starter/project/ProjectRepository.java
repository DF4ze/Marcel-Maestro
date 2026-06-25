package fr.ses10doigts.mm.starter.project;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository Spring Data pour les {@link ProjectEntity}.
 *
 * <p>Fournit les queries nécessaires au {@code ProjectService} (mm-app) :
 * recherche par slug, liste par statut.</p>
 */
public interface ProjectRepository extends JpaRepository<ProjectEntity, String> {

    /**
     * Recherche un projet par son slug sanitisé (contrainte UNIQUE).
     *
     * @param sanitizedName le slug kebab-case à chercher
     * @return le projet correspondant, ou {@link Optional#empty()} s'il n'existe pas
     */
    Optional<ProjectEntity> findBySanitizedName(String sanitizedName);

    /**
     * Retourne tous les projets ayant le statut donné.
     *
     * @param status le statut à filtrer (ACTIVE ou ARCHIVED)
     * @return liste des projets correspondants, éventuellement vide
     */
    List<ProjectEntity> findAllByStatus(ProjectStatus status);
}
