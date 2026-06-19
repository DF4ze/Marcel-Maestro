/**
 * Noyau agentique pur de Marcel Maestro (MM).
 *
 * <p>Ce package et ses sous-packages ne contiennent <strong>aucune</strong> logique métier,
 * aucune implémentation de port, et ne dépendent que de Spring AI core.
 * Les contrats enfichables (les « prises ») sont introduits à l'étape 2 de la roadmap.</p>
 *
 * <p>Règle de frontière : rien de spécifique à un consommateur (dev/devops, artisan, …)
 * ne descend ici. Le litmus de pureté (maven-enforcer) garantit cette discipline au build.</p>
 */
package fr.ses10doigts.mm.core;
