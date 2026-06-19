/**
 * Prise de validation humaine — Human-In-The-Loop (ADR-005, étape 4).
 *
 * <p>Le moteur décide <em>quand</em> demander ({@link fr.ses10doigts.mm.core.hitl.HitlGuard}) ;
 * l'hôte décide <em>comment</em> demander via
 * {@link fr.ses10doigts.mm.core.hitl.HumanInteraction} (console, Telegram, web…).</p>
 *
 * <p>Composants noyau :</p>
 * <ul>
 *   <li>{@link fr.ses10doigts.mm.core.hitl.HitlPolicy} — politique risk → consentement</li>
 *   <li>{@link fr.ses10doigts.mm.core.hitl.ConsentCache} — cache in-memory des décisions session</li>
 *   <li>{@link fr.ses10doigts.mm.core.hitl.HitlGuard} — orchestre policy → cache → ask()</li>
 *   <li>{@link fr.ses10doigts.mm.core.hitl.HitlVerdict} — résultat typé du guard</li>
 * </ul>
 *
 * <p>Aucune implémentation concrète du canal dans ce package : la console vit dans
 * {@code mm-spring-boot-starter}, Telegram dans {@code mm-app} (étape 8).</p>
 */
package fr.ses10doigts.mm.core.hitl;
