package fr.ses10doigts.mm.starter.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entité JPA représentant un projet Marcel Maestro (E2-M1).
 *
 * <p>Mappée sur la table {@code project} créée par la migration Flyway V2.
 * La source de vérité est la base de données, pas le filesystem (ADR-022).
 * L'ID est un UUID TEXT généré par l'application avant la persistance.</p>
 *
 * <p>Le champ {@code config} (JSON nullable) est une couture pour de futurs
 * paramètres (description, tech stack…) — sans logique associée en E2-M1.</p>
 *
 * <p>Cette entité ne doit jamais être exposée directement dans {@code mm-core} :
 * le noyau ne voit que des records domaine ou des ports. (ADR-002, ADR-003)</p>
 */
@Entity
@Table(name = "project")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectEntity {

    /** UUID v4 généré par {@code ProjectService} avant insertion. */
    @Id
    @Column(nullable = false)
    private String id;

    /** Nom d'affichage original (ex : "Mon Super Projet"). */
    @Column(nullable = false)
    private String name;

    /**
     * Slug kebab-case minuscule, clé du dossier workspace et contrainte UNIQUE.
     * Calculé par {@code ProjectService.sanitize(name)}.
     */
    @Column(name = "sanitized_name", nullable = false, unique = true)
    private String sanitizedName;

    /** Chemin absolu du dossier interne géré par Marcel. */
    @Column(name = "workspace_path", nullable = false)
    private String workspacePath;

    /** Statut du cycle de vie (ACTIVE | ARCHIVED). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ProjectStatus status = ProjectStatus.ACTIVE;

    /**
     * Paramètres JSON optionnels (couture pour futurs usages).
     * Nullable, aucune logique associée en E2-M1.
     */
    @Column(columnDefinition = "TEXT")
    private String config;

    /** Timestamp de création, ISO-8601 (TEXT dans SQLite). */
    @Column(name = "created_at", nullable = false)
    private String createdAt;

    /** Timestamp de dernière mise à jour, ISO-8601. */
    @Column(name = "updated_at", nullable = false)
    private String updatedAt;
}
