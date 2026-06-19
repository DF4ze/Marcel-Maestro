/**
 * Prise de validation humaine — Human-In-The-Loop (ADR-005).
 *
 * <p>Le moteur décide <em>quand</em> demander (HitlGuard, étape 4) ; l'hôte décide
 * <em>comment</em> demander via {@link fr.ses10doigts.mm.core.hitl.HumanInteraction}
 * (console, Telegram, web…). Console et Telegram sont deux adaptateurs du même port.</p>
 *
 * <p>Aucune implémentation ici : première impl concrète (console) à l'étape 4,
 * Telegram à l'étape 8.</p>
 */
package fr.ses10doigts.mm.core.hitl;
