package fr.ses10doigts.mm.starter.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Entité JPA représentant un dossier de travail externe déclaré pour un projet (E2-M1).
 *
 * <p>Mappée sur la table {@code project_workspace} créée par la migration Flyway V2.
 * Un projet peut avoir 0..N dossiers externes déclarés explicitement par l'utilisateur.
 * La suppression du projet parent déclenche un {@code ON DELETE CASCADE} en base,
 * nettoyant automatiquement les lignes associées (ADR-023).</p>
 *
 * <p>En E2-M3, {@code PathValidator} consultera ces dossiers pour décider si le HITL
 * write doit être bypassé. En E2-M1, ils sont gérés en CRUD pur.</p>
 */
@Entity
@Table(name = "project_workspace")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProjectWorkspaceEntity {

    /** UUID v4 généré par {@code ProjectService} avant insertion. */
    @Id
    @Column(nullable = false)
    private String id;

    /** Projet propriétaire de ce dossier externe. */
    @ManyToOne
    @JoinColumn(name = "project_id", nullable = false)
    private ProjectEntity project;

    /** Chemin absolu du dossier externe déclaré par l'utilisateur. */
    @Column(nullable = false)
    private String path;

    /** Timestamp d'ajout, ISO-8601 (TEXT dans SQLite). */
    @Column(name = "added_at", nullable = false)
    private String addedAt;
}
