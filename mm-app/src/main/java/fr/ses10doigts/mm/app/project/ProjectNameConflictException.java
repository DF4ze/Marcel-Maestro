package fr.ses10doigts.mm.app.project;

/**
 * Levée quand un nouveau projet produit un slug {@code sanitizedName} déjà utilisé
 * par un projet existant.
 *
 * <p>Deux noms d'affichage différents peuvent donner le même slug après sanitisation
 * (ex : "Mon Projet" et "mon-projet" → tous deux → {@code "mon-projet"}).
 * Cette exception expose le slug en conflit pour que le message d'erreur REST soit
 * exploitable sans ambiguïté.</p>
 */
public class ProjectNameConflictException extends RuntimeException {

    private final String conflictingSlug;

    /**
     * @param conflictingSlug le slug kebab-case déjà présent en base
     */
    public ProjectNameConflictException(String conflictingSlug) {
        super("Un projet avec le slug '" + conflictingSlug + "' existe déjà. "
                + "Choisissez un nom qui donne un slug différent.");
        this.conflictingSlug = conflictingSlug;
    }

    /**
     * @return le slug en conflit
     */
    public String getConflictingSlug() {
        return conflictingSlug;
    }
}
