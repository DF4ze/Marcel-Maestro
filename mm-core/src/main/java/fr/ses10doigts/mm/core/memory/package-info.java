/**
 * Prises mémoire — stockage uniforme, capture spécifique.
 *
 * <p>{@link fr.ses10doigts.mm.core.memory.MemoryStore} (mémoire factuelle, scope comme
 * attribut — ADR-009) sera implémenté à l'étape 5 (SQLite).
 * {@link fr.ses10doigts.mm.core.memory.SemanticMemory} (mémoire procédurale) est une
 * <strong>couture laissée vide</strong> pour l'apprentissage différé.</p>
 *
 * <p>Les ports sont uniformes ({@code put}/{@code get}/{@code search}) ; la politique de
 * capture (quand écrire, quoi extraire) vivra dans chaque adaptateur, pas ici.</p>
 */
package fr.ses10doigts.mm.core.memory;
