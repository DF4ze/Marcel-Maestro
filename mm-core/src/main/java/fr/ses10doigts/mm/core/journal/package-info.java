/**
 * Port du journal d'actions (PB-04 Q2).
 *
 * <p>L'interface {@link fr.ses10doigts.mm.core.journal.Journal} vit dans le noyau ;
 * l'implémentation {@code FileJournal} (JSONL append-only) vivra dans le starter
 * (étape 8). Sert l'audit et le debug — jamais lu par le LLM en production.</p>
 */
package fr.ses10doigts.mm.core.journal;
