package fr.ses10doigts.mm.core.journal;

/**
 * Port du journal d'actions append-only (PB-04 Q2).
 *
 * <p>Implémentation {@code FileJournal} (JSONL) au starter, étape 8.</p>
 */
public interface Journal {

    /** Ajoute une entrée au journal. Append-only : jamais de modification. */
    void append(JournalEntry entry);
}
