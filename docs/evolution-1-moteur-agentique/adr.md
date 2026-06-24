# Journal de décisions architecturales (ADR)
**Statut : Document de travail**
**Légende** : ✅ Acté | 💡 Recommandé (non encore acté) | ❓ Ouvert (à trancher)

Format par entrée : **Décision** → **Alternative écartée** → **Justification vs les 5 principes**
Quand une décision actée me semble risquée par rapport aux 5 principes, je le signale explicitement.

---

## ADR-001 — Mono-repo multi-module Maven sans publication d'artefact
**Statut** : ✅ Acté

**Décision** : Un seul dépôt Git, modules Maven avec parent POM commun. Pas de publication vers un registre Maven tant qu'il n'y a qu'un seul consommateur. Pas de versioning sémantique de l'API publique.

**Alternative écartée** : Multiples dépôts Git avec publication Nexus/GitHub Packages dès le départ.

**Justification** :
- YAGNI : versionner et publier un "framework" interne sans second consommateur réel, c'est payer le coût d'une bibliothèque publique sans la valeur.
- KISS : un seul dépôt, une seule CI, un seul `mvn install` pour tout.
- Un changement d'API dans `mm-core` est immédiatement visible dans tous les modules — c'est voulu.

**Trigger d'extraction** : quand un deuxième produit indépendant consomme `mm-core` pour de vrai (règle des trois).

---

## ADR-002 — Trois modules : mm-core / starter / app
**Statut** : ✅ Acté

**Décision** : `mm-core` ne dépend que de Spring AI core et ne contient aucune logique métier. `mm-spring-boot-starter` fournit les implémentations par défaut des ports. `mm-app` est le premier et seul consommateur métier.

**Alternative écartée** : Monolithe Spring Boot unique.

**Justification** : SRP — le moteur doit compiler et tourner sans aucune connaissance des artisans, des factures, ou des devis. La séparation permet de remplacer une implémentation par défaut (ex: `InMemoryTaskQueue` → `RabbitMqTaskQueue`) sans toucher au moteur.

---

## ADR-003 — Pureté du moteur : litmus test SRP
**Statut** : ✅ Acté

**Décision** : Si `mm-core` est isolé de tous ses consommateurs métier, il doit compiler et ses tests doivent passer. Automatisé en CI : `mvn -pl mm-core test`.

**Ce qui ne doit JAMAIS apparaître dans `mm-core`** : imports de classes métier, logique de facture/devis/stock, configuration spécifique artisan, références à `mm-app`.

---

## ADR-004 — Contrat AgentTool avec RiskLevel
**Statut** : ✅ Acté

**Décision** : `AgentTool` expose `riskLevel()` en plus des métadonnées LLM standard. Le `RiskLevel` est la seule information non couverte par Spring AI `FunctionCallback`.

**Alternative écartée** : Réenrober entièrement le function calling de Spring AI avec notre propre format de schema.

**Justification** : DRY — Spring AI gère déjà le JSON Schema, la sérialisation, et l'enregistrement auprès du modèle. La valeur ajoutée du wrapper est uniquement `riskLevel()`.

**Risque identifié (DRY)** : Si Spring AI ajoute un concept de "risk level" ou "confirmation required" nativement, notre wrapper devient une couche sans valeur. À surveiller et simplifier si cela arrive.

**Recommandation d'implémentation** : `AgentTool` est adapté vers `FunctionCallback` via un converter interne dans le moteur. L'hôte n'implémente qu'`AgentTool`.

---

## ADR-005 — HITL : 4 niveaux, port HumanInteraction
**Statut** : ✅ Acté

**Décision** : `once / session / project / always`. Le moteur décide *quand* demander (`HitlGuard`). L'hôte décide *comment* demander (`HumanInteraction`). Les niveaux `project` et `always` sont persistés en C3.

**Alternative écartée** : HITL binaire (toujours demander / ne jamais demander) ou HITL géré dans les tools eux-mêmes.

**Justification** : SRP — décision de demander (moteur) séparée du canal de demande (hôte). KISS — quatre niveaux couvrent 99% des cas.

---

## ADR-006 — Pas de LangGraph : machine à états custom
**Statut** : ✅ Acté

**Décision** : La sortie LLM structurée (JSON avec champ `status`) EST la machine à états. Un `switch` déterministe route sur l'enum `AgentStatus`. Boucle bornée par `maxIterations`.

**Alternative écartée** : Spring AI StateGraph (si disponible), portage LangGraph en Java, ou bibliothèque de workflow (Activiti, jBPM).

**Justification** : KISS — un switch sur enum est lisible par tout développeur Java. Une bibliothèque de workflow est une abstraction supplémentaire à maîtriser.

**Ce que la machine à états custom doit couvrir manuellement** (fourni nativement par LangGraph) :
1. Error-triggered retrieval sur `status: TROUBLE`
2. Détection de boucle infinie (même status sur N itérations consécutives → KO)
3. Interruption propre sur signal STOP (flag `AtomicBoolean`)
4. Idempotence des tool calls (aucun mécanisme natif — voir PB-07)

**Risque** : Si la complexité des flux augmente (branches conditionnelles multiples, fork, join de sous-tâches parallèles), la lisibilité du switch se dégrade. À réévaluer si > 8 cas distincts.

---

## ADR-007 — Cortex = seul planificateur (SSOT)
**Statut** : ✅ Acté

**Décision** : Seul le Cortex crée des sous-tâches et les assigne. Les spécialistes exécutent, jamais ne planifient.

**Alternative écartée** : Spécialistes autonomes qui créent leurs propres sous-tâches sans remonter au Cortex.

**Justification** : SSOT — si deux agents peuvent planifier, on perd la vue globale. Le Cortex doit pouvoir synthétiser l'état du système entier pour répondre à l'utilisateur.

**Corollaire (signalé comme risque)** : Le pré-qualificateur de §7.3 du document Python est reporté avec la mention "garder comme classifieur d'intent". Si ce classifieur finit par router des requêtes directement vers des spécialistes sans passer par le Cortex, il viole ADR-007. Le rôle du classifieur doit rester : enrichir le contexte avant que le Cortex le voie, jamais décider à sa place.

---

## ADR-008 — Communication inter-agents par file de tâches typées
**Statut** : ✅ Acté

**⚠️ Contradiction avec §2.3 du document Python** : le document original autorise les boucles directes entre spécialistes. Cette décision l'interdit.

**Décision** : Toute communication entre agents passe par des `TaskMessage` typés dans la `TaskQueue`. Zéro dialogue LLM↔LLM libre.

**Conséquence concrète** : La boucle build→erreur→correction du document Python devient :
1. SpecialistJava crée `TaskMessage(type=BUILD_REQUEST)`
2. SpecialistCICD consomme, exécute, crée `TaskMessage(type=BUILD_RESULT, success=false, log=...)`
3. SpecialistJava consomme le résultat → nouvelle itération

**Alternative écartée** : Appels directs entre agents (plus simple, moins traçable, impossible à rejouer en cas d'audit).

**Justification** : SSOT — le Cortex (ou un superviseur) peut voir l'historique complet via la queue. SRP — le Dispatcher reste le seul composant qui connaît la topologie des agents.

---

## ADR-009 — C3 et C6 fusionnées : scope comme attribut
**Statut** : ✅ Acté

**Décision** : Pas de "mémoire globale" séparée. `MemoryEntry.scope` vaut `"global"`, `"project:<id>"`, `"session:<id>"`, etc. Une seule table, une seule interface.

**Alternative écartée** : Tables séparées par scope, ou couche 6 sous forme de fichiers séparés.

**Justification** : SSOT + KISS — inutile de dupliquer l'interface pour des données qui ne diffèrent que par leur portée.

---

## ADR-010 — pgvector comme VectorStore unique (C3 + C4)
**Statut** : ✅ Acté

**Décision** : Une seule instance PostgreSQL pour C3 (tables JPA) et C4 (pgvector). Pas de ChromaDB ni Qdrant.

**Alternative écartée** : PostgreSQL (C3) + ChromaDB (C4) en deux processus distincts.

**Justification** : KISS — une seule BDD à démarrer, une seule connexion, une seule sauvegarde. pgvector est mature et bien supporté par Spring AI VectorStore.

**Note** : C3 démarre sur SQLite (M0-M4). Migration vers PostgreSQL au jalon M5 quand C4 est activée. Flyway gère la migration — triviale avec Spring Data JPA.

---

## ADR-011 — Error-triggered retrieval uniquement
**Statut** : ✅ Acté

**Décision** : La mémoire C4 (sémantique) n'est interrogée qu'à la transition `status: TROUBLE` ou sur échec d'outil. Pas de retrieval à chaque requête.

**Alternative écartée** : RAG systématique (retrieval à chaque appel LLM).

**Justification** : KISS + performance — le signal est plus fort sur erreur (message d'erreur précis = meilleure requête vectorielle). Coût nul pour les tâches sans accroc.

---

## ADR-012 — @Scheduled pour le batch MVP, Spring Batch en option
**Statut** : 💡 Recommandé

**Décision** : Démarrer avec `@Scheduled(cron = "0 2 * * *")` pour la consolidation nocturne. Migrer vers Spring Batch quand le volume de journaux dépasse ~1000 entrées/nuit, ou quand la reprise sur erreur du batch devient nécessaire.

**Alternative écartée** : Spring Batch dès le départ.

**Justification** : YAGNI — Spring Batch ajoute `JobRepository`, `JobLauncher`, définitions de `Step`, etc. Non justifié pour un MVP avec un seul artisan. La migration est directe.

---

## ADR-013 — Clé tenant présente dès J1, multi-tenant non implémenté
**Statut** : ✅ Acté

**Décision** : `AgentContext` et `MemoryEntry` portent un champ `tenant`. Le filtrage multi-tenant n'est pas implémenté. La valeur sera `"default"` dans le MVP.

**Risque identifié** : Si `tenant` est ajouté plus tard, il faut une migration de schéma et potentiellement des données à réaffecter. En l'ajoutant dès J1, on évite une réécriture structurelle.

**Ce qui est YAGNI** : Row-Level Security PostgreSQL, isolation des données entre artisans, authentification JWT. Tout ça attend qu'un second artisan existe.

---

## ADR-014 — SQLite pour démarrer, PostgreSQL quand pgvector est nécessaire
**Statut** : ✅ Acté

**Jalons** :
- M0-M4 : SQLite uniquement (C3, relational)
- M5 (activation C4) : migration vers PostgreSQL + pgvector

---

## ADR-015 — TaskQueue in-memory (BlockingQueue) pour le MVP
**Statut** : 💡 Recommandé

**Décision** : `LinkedBlockingQueue<TaskMessage>` in-process. Non-durable : tâches perdues si le process crash.

**Alternative écartée** : Spring Integration avec canal persisté, ou RabbitMQ.

**Justification** : YAGNI — pour un seul artisan, la durabilité de la file est excessive. La perte de tâches sur crash est acceptable (l'artisan relance). Migrer vers une file durable quand des tâches à valeur légale (facturation) entrent dans le scope.

**⚠️ Risque documenté (voir PB-07)** : Si une tâche de facturation légale est dans la file lors d'un crash, elle est perdue sans trace. Ce cas d'usage interdit la BlockingQueue in-memory. À traiter avant d'activer le produit facturation.

---

## ADR-016 — Un seul bot Telegram (pas un par agent)
**Statut** : 💡 Recommandé

**Décision** : Un seul bot, toutes les notifications et demandes HITL transitent par lui. Le contexte (quel agent, quelle tâche) est inclus dans le message.

**Alternative écartée** : Un bot par agent (comme §9.2 du document Python le suggère).

**Justification** : KISS — gérer N tokens, N listeners, N bots pour un seul utilisateur est sur-ingénié. Le routing par contexte dans le message est suffisant.

---

## ADR-017 — Démarrer API REST + Telegram, reporter le web GUI riche
**Statut** : ✅ Acté (confirmé dans les décisions)

**Décision** : Pas de web GUI avec visualisation mémoire temps réel pour le MVP. Interface : API REST (pour les tests développeur) + Telegram (pour l'artisan).

---

## ADR-018 — SaaS vs installation locale
**Statut** : ❓ Ouvert — CRITIQUE, à trancher avant M0

**Cette décision conditionne l'ensemble de l'architecture de déploiement, la stratégie multi-tenant, et le modèle économique.**

**Voir Registre des points bloquants, PB-01.**

---

## ADR-019 — Provider LLM : cloud vs local pour le Cortex
**Statut** : ❓ Ouvert — CRITIQUE, à trancher avant M1

**La philosophie "local" du document Python est probablement irréaliste pour la cible artisan TPE.**

**Voir Registre des points bloquants, PB-02.**

---

## Tableau de synthèse

| ADR | Sujet | Statut | Principe dominant |
|-----|-------|--------|-------------------|
| 001 | Mono-repo sans publication | ✅ Acté | YAGNI, KISS |
| 002 | Séparation core/starter/app | ✅ Acté | SRP |
| 003 | Litmus test SRP | ✅ Acté | SRP |
| 004 | AgentTool + RiskLevel | ✅ Acté | DRY |
| 005 | HITL 4 niveaux | ✅ Acté | SRP, KISS |
| 006 | Machine à états custom | ✅ Acté | KISS |
| 007 | Cortex = seul planificateur | ✅ Acté | SSOT |
| 008 | File typée inter-agents | ✅ Acté | SSOT, SRP |
| 009 | C3/C6 fusionnées (scope) | ✅ Acté | SSOT, KISS |
| 010 | pgvector unique | ✅ Acté | KISS |
| 011 | Error-triggered retrieval | ✅ Acté | KISS |
| 012 | @Scheduled avant Spring Batch | 💡 Recommandé | YAGNI |
| 013 | tenant dès J1, non implémenté | ✅ Acté | YAGNI |
| 014 | SQLite → Postgres | ✅ Acté | YAGNI |
| 015 | BlockingQueue in-memory | 💡 Recommandé | YAGNI |
| 016 | Un seul bot Telegram | 💡 Recommandé | KISS |
| 017 | API REST + Telegram, pas de GUI | ✅ Acté | YAGNI |
| 018 | SaaS vs local | ❓ Ouvert | — |
| 019 | Provider LLM | ❓ Ouvert | — |
