/**
 * Prise LLM — <strong>aucun code, aucun wrapper maison</strong>.
 *
 * <p>Le port LLM du noyau <em>est</em> {@code org.springframework.ai.chat.client.ChatClient}
 * de Spring AI, adopté tel quel. Le provider (cloud ou local) est interchangeable par
 * configuration uniquement ({@code spring.ai.openai.*} vs {@code spring.ai.ollama.*}) —
 * le code du moteur ne change pas.</p>
 *
 * <p>Justification (règle des deux implémentations) : Spring AI fournit déjà l'abstraction
 * à deux implémentations réelles (cloud/local). Réenrober ce port n'apporterait aucune
 * valeur (DRY). Ce package n'existe que pour documenter la décision ; il ne contient
 * volontairement aucune classe.</p>
 */
package fr.ses10doigts.mm.core.llm;
