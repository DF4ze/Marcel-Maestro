# Roadmap d'implémentation — Marcel Maestro (MM) V1
**Statut : Document de travail**
**Date : 2026-06-19**
**Méthode d'élaboration : décomposition en largeur (toutes les grandes étapes H1, puis le détail H2 de chacune).**

> **Nom du projet — tranché le 2026-06-19 : `Marcel Maestro`, alias `MM`.** Remplace les appellations provisoires « AgentLLM » / « SuperAgent ». Hiérarchie de nommage des modules : `mm-core` (noyau pur), `mm-spring-boot-starter` (implémentations par défaut), `mm-app` (consommateur dev/devops).
>
> **L'agent Chef historique est nommé `Cortex`** (le LLM planificateur). C'est le cerveau qui pense, questionne, planifie et délègue — il produit le plan, jamais l'exécution (SSOT : « Cortex seul planifie »). À distinguer de l'**orchestrateur** = le Dispatcher, code de routage non-LLM.
>
> Métaphore : Cortex (le cerveau) dirige via Maestro ; les spécialistes sont les pupitres, le Dispatcher est le régisseur, la file de tâches est la partition.

---

## 1. Cadrage

### 1.1 Ce qu'est Marcel Maestro

Un **noyau agentique product-agnostic** (un moteur), construit sous une discipline de frontières stricte : le noyau ne connaît aucun métier. Autour de ce noyau, on branche des consommateurs. La valeur du produit, c'est le noyau et sa **fiabilité sur de larges périmètres** — pas les fonctionnalités métier qui se greffent dessus.

### 1.2 Les trois couches

| Couche | Rôle | Statut |
|--------|------|--------|
| **Noyau** | Moteur agentique pur, aucune notion de métier ni d'environnement | Construit en V1 |
| **Cockpit dev/devops** | Premier client : usage personnel d'un développeur avancé + devops | Construit en V1 |
| **Solutions artisan** | Client différé : se branche sur le même noyau, ses propres outils et interfaces | Coutures laissées ouvertes, **rien construit maintenant** |

La modularité n'est pas une fonctionnalité à développer : c'est une **discipline de frontières**. On construit pour le cas dev/devops, et la seule règle est que rien de spécifique à ce cas ne descend dans le noyau.

### 1.3 Goal n°1 (le V1)

Un outil pour l'usage personnel : développement local sur **Windows (Java/Spring)** et devops sur **VPS Debian**. Exigence phare : **tenir de larges scopes de développement de façon fiable**.

### 1.4 Décisions de périmètre tranchées

- **Déploiement local**, pas SaaS (le SaaS et tout ce qu'il impliquait — multi-tenant actif, authentification, RGPD — est différé jusqu'au client artisan).
- **Pas de Telegram pour les artisans** ; le métier artisan est entièrement différé.
- **3 modules** Maven (le module `mm-batch` des documents d'origine est retiré : le batch d'apprentissage est différé).
- **SQLite** pour la mémoire factuelle ; pas de PostgreSQL/pgvector en V1.
- **Orchestrateur minimal** : présent pour marquer le coup, pas le roster réel de spécialistes.
- **Observabilité = log fichier simple.**
- **Telegram dans le V1** (notifications + HITL interactif), via le module multibot déjà développé.

### 1.5 Différé hors V1

Apprentissage automatique (distillation LLM nocturne + mémoire vectorielle), multi-tenant réel / sécurité pour autrui / RGPD, métier artisan, roster réel de spécialistes, file de tâches durable, GUI web riche.

---

## 2. Principes transverses (tranchés pendant la conception)

Ces principes gouvernent toutes les étapes. Ils sont la vraie colonne vertébrale du projet.

**Déterministe en bas, LLM pour le jugement seulement.** Une procédure connue (déployer, builder) est un script déterministe, pas une improvisation du LLM. Le LLM décide *l'intention* ; l'exécution multi-étapes est un playbook. Gain double : coût (moins d'appels LLM) et fiabilité (un script n'hallucine pas).

**Outil-vs-agent.** Un agent séparé ne se justifie que pour un *raisonnement indépendant* ou une *isolation de contexte*. Une capacité déterministe (build, déploiement, requête DB) est un **outil** appelé dans la boucle d'un agent, jamais un agent. Exemple : le build est un outil de l'agent dev → boucle serrée `code → build → erreur → corrige`, zéro aller-retour vers le Cortex.

**Outils : grossier ≠ monolithique.** On expose un outil **par opération distincte et séparément autorisable** (`build`, `build_and_deploy`, `service_management` sont trois outils), pas une primitive SSH par commande (trop fin), ni un seul gros outil « passerelle » (impossible à restreindre).

**Autorisation ≠ consentement.** Deux axes orthogonaux : l'*autorisation* (quels outils un agent possède → liste blanche déclarative par agent ; les outils non accordés sont invisibles du LLM) et le *consentement* (un outil possédé exige-t-il une confirmation → `riskLevel` / HITL).

**Sécurité en double couche.** La passerelle VPS applique un plancher de sécurité dur quel que soit l'appelant ; l'agent ajoute par-dessus le HITL contextuel. Deux serrures indépendantes.

**Mémoire : stockage uniforme, capture spécifique.** Le port mémoire (`put`/`get`/`search`) est uniforme et facile à câbler. La particularité de chaque mémoire (quand écrire, quoi extraire) est une **politique de capture** branchée sur des hooks de la boucle, isolée dans chaque adaptateur. La mémoire contextuelle (historique de conversation) est fournie par Spring AI `ChatMemory` — on ne la construit pas. Le bus d'événements de capture n'est introduit que quand une seconde politique (mémoire sur erreur) le justifie.

**Règle des deux implémentations.** On ne définit un port que lorsqu'on peut nommer deux implémentations concrètes réellement utilisées (cloud/local pour le LLM, simple/vectoriel pour la mémoire, console/Telegram pour l'humain, outils multiples). Sinon, on hardcode et on extrait le port à l'arrivée de la seconde.

**La sortie structurée JSON EST la machine à états.** Parsing déterministe, jamais d'interprétation NLP du texte libre. Un JSON invalide est une erreur, traitée comme telle.

**SSOT : le Cortex seul planifie.** Les spécialistes exécutent, ne créent jamais de sous-tâches eux-mêmes.

**Telegram = adaptateur général du port `HumanInteraction`.** Il sert tout le système (notifications de dev, HITL, commandes), pas uniquement la surveillance du VPS. Console et Telegram sont deux adaptateurs du même port, sans conflit ni impact sur le noyau.

**Le modèle d'exécution bi-environnement.** Le noyau tourne en local (Windows). Le VPS n'est atteint que par la **passerelle existante, gardée indépendante** (tunnel, secrets, playbooks de déploiement déjà implémentés), consommée comme un outil. Les secrets du VPS ne remontent jamais dans le noyau. La passerelle est aussi le point de passage unique pour l'audit des mutations VPS.

---

## 3. Les étapes (H1) et leur détail (H2)

### Étape 1 — Fondations & frontières  ✅ `done` (build vert le 2026-06-19)
*Le projet compile, démarre, et prouve ses frontières de modules.*
*Réalisé : mono-repo Maven (parent + mm-core / mm-spring-boot-starter / mm-app), litmus de pureté via maven-enforcer + CI GitLab, smoke test de démarrage. `mvn verify` validé vert sur poste Windows (Java 21).*

1. **Squelette mono-repo Maven** — parent POM + 3 modules : `mm-core` (noyau pur), `mm-spring-boot-starter` (implémentations par défaut), `mm-app` (consommateur).
2. **Règles de dépendances entre modules** — `mm-core` ne dépend que de Spring AI core ; interdits explicites (pas de `spring-data`, `spring-web`, ni rien de métier dans le noyau).
3. **Test de pureté du noyau (litmus) automatisé** — `mm-core` compile et teste seul ; scan de l'arbre de dépendances vérifie l'absence de dépendance métier.
4. **Pipeline CI de base** — `mvn verify` sur tous les modules + le litmus ; build vert obligatoire.
5. **Smoke test de démarrage** — `mm-app` boote, charge l'autoconfiguration (vide) du starter, log « noyau chargé ».

*Hors scope : toute interface métier, tout appel LLM, toute base de données.*

### Étape 2 — Contrats du noyau (les prises)  ✅ `done`
*On définit toutes les interfaces enfichables et types pivots dans `mm-core` — aucune implémentation.*

1. **Types pivots** — `AgentContext`, `AgentStatus` (pending/running/done/blocked/trouble/KO), `AgentResponse` (contrat de sortie structurée : status, reason, output, tool_calls, sub_tasks), `TaskMessage`.
2. **Prise LLM** — adoption du `ChatClient` de Spring AI (cloud/local interchangeables par config) ; pas de wrapper maison.
3. **SPI Outil** — `AgentTool` (name, description, inputSchema, riskLevel, execute), `RiskLevel`, `ToolResult`.
4. **Prise Validation humaine** — `HumanInteraction` (ask/notify), `HitlRequest`, `ConsentDecision` (4 niveaux + refus).
5. **Prises Mémoire** — `FactStore` (défini, rempli à l'étape 5) ; `SemanticMemory` (défini, **laissé vide** — couture pour l'apprentissage différé).

*Principe : définir toutes les prises, n'en remplir aucune.*

### Étape 3 — Boucle agentique fiable  🔄 `running` (build vert ; spike go/no-go LLM en attente)
*Le cœur du « gros périmètre de façon fiable ».*
*Réalisé : boucle/engine implémentés et build vert. Provider pressenti pour le spike : OpenRouter (API compatible OpenAI) via `spring-ai-openai` en scope test. Le go/no-go empirique (JSON >95 %) reste à valider côté user.*

1. **Spike de dérisquage du JSON (go/no-go)** — avant d'écrire la boucle : valider empiriquement que le LLM produit du JSON fiable (>95 %) sur 2-3 modèles. Si non concluant, changer de modèle avant d'aller plus loin.
2. **System prompt & contrat de sortie** — prompt de base imposant le format `AgentResponse` ; vit dans le noyau, **extensible par l'hôte, pas remplaçable**.
3. **Parsing robuste & cas dégradés** — désérialisation déterministe + filets (JSON mode du provider, fallback regex, détection de troncature) ; échec → `TROUBLE` + retry renforcé ; jamais de NLP.
4. **Machine à états (routage par statut)** — switch exhaustif sur `AgentStatus`. En V1, `trouble` = retry simple (la recherche en mémoire sur erreur reste la couture ouverte).
5. **Bornage, garde-fous & STOP de boucle** — `maxIterations` + compteurs par statut + détection de boucle infinie (→ KO) ; flag `AtomicBoolean` vérifié uniquement entre opérations atomiques.

*Hors scope : outils réels, mémoire persistante, spécialistes.*

### Étape 4 — Garde-fou humain (HITL)  ✅ `done`
*Couche de sécurité contextuelle, complète le plancher dur de la passerelle.*

1. **Politique RiskLevel → demande** — `LOW` = exécution directe ; `MEDIUM`/`HIGH`/`CRITICAL` = validation obligatoire ; configurable.
2. **HitlGuard (décideur du « quand »)** — intercepte avant exécution, lit le `riskLevel`, consulte le cache, tranche.
3. **Niveaux & cache de consentement** — `ponctuel`/`session` pleinement fonctionnels (cache in-memory) ; `projet`/`toujours` acceptés mais persistés à l'étape 5.
4. **ConsoleHumanInteraction (le « comment »)** — première implémentation concrète d'un port : `ask()` (stdin) + `notify()`. Vit dans `mm-spring-boot-starter` avec `@ConditionalOnMissingBean` (décision PB-04 tranchée).
5. **Intégration dans la boucle (cas `blocked`)** — `ask()` → attend → reprend si ALLOW, KO si DENY.

*Décision : le branchement du HitlGuard dans la boucle pour les tool_calls (consentement avant exécution) sera fait à l'étape 6, au même point que `AgentTool.execute()`. Le HitlGuard est conçu, testé et prêt dès l'étape 4 mais sans résolveur de riskLevel artificiel — l'intégration naturelle se fait avec le registre d'outils de l'étape 6.*

*Hors scope : persistance projet/toujours (étape 5), canal web/Telegram (étape 8), interception tool_calls dans la boucle (étape 6).*

### Étape 5 — Mémoire factuelle  ✅ `done`
*Les faits utiles et la confiance accordée survivent au redémarrage.*
*Réalisé : JpaMemoryStore (SQLite + Flyway), PersistentConsentCache (consentements projet/toujours rechargés au démarrage), RememberFactTool. Lombok/@Slf4j/JavaDoc appliqués.*

1. **Implémentation `FactStore` (SQLite)** — `put`/`get`/`findByScope`/`delete` via Spring Data JPA + SQLite.
2. **Schéma & migrations** — table `MemoryEntry` (key, value, scope, `tenant="default"`, timestamps) ; Flyway. Le `tenant` est présent mais figé — couture multi-artisan sans le coût.
3. **Persistance des consentements HITL** — `projet`/`toujours` écrits dans le `FactStore` et **rechargés au démarrage** de session.
4. **Lecture/écriture de faits par l'agent** — capture simple et explicite (outil « retiens ceci » et/ou hook de fin de tâche) ; **pas de bus d'événements**.
5. **Litmus de pureté** — vérifier qu'aucune dépendance SQLite/JPA ne contamine `mm-core`.

*Hors scope : `SemanticMemory` (reste vide), vectoriel, distillation nocturne, bus d'événements.*

### Étape 6 — Outils & passerelle VPS  ✅ `done`
*Le moteur touche le monde réel.*
*Réalisé : AgentToolConverter (→ToolCallback Spring AI), ToolRegistry (liste blanche par agent), ToolExecutionGuard (HITL + PathValidator + timeout), idempotencyKey dans AgentContext, intégration tool_calls dans AgentLoop. Outils dev local : read_file, write_file, read_logs, maven_build. Passerelle VPS : vps_build, vps_build_and_deploy, vps_service_management (MCP client, @ConditionalOnProperty). 27 tests unitaires couvrant le pipeline. `mvn verify` à valider sur poste Windows.*

1. **Adaptateur `AgentTool` → Spring AI** — converter interne qui expose les outils (avec leur `riskLevel`) au LLM ; l'hôte n'implémente que `AgentTool`.
2. **Registre & injection par contexte** — chaque agent reçoit sa **liste blanche d'outils** à l'instanciation (déclarative, nominative) ; les outils non accordés sont invisibles du LLM.
3. **Sécurité d'exécution (transverse, avant tout outil réel)** — validation des chemins (anti path-traversal), timeout par outil (→ `TROUBLE` si dépassé), aucun code dynamique construit par le LLM.
4. **Outils de dev local** — build Maven, lecture/écriture fichiers, lecture de logs ; `riskLevel` calibré (lecture LOW, écriture/exécution HIGH).
5. **Branchement de la passerelle VPS** — exposer les capacités existantes comme outils **distincts et déterministes** (`build`, `build_and_deploy`, `service_management`…), via client direct de préférence (pas de coût LLM). On ne reconstruit rien : on mappe vers les playbooks existants. Surface volontairement petite ; granularité = grain d'autorisation.

*Hors scope : outils métier artisan, email, génération de documents.*

### Étape 7 — Orchestrateur minimal
*Prouver les coutures (déléguer, router, rapporter, arrêter), pas le roster réel.*

1. **File de tâches typée (in-memory)** — `LinkedBlockingQueue<TaskMessage>` ; toute communication inter-agents y transite (pas de dialogue LLM↔LLM direct) ; non-durable (acceptable, documenté).
2. **Dispatcher (permanent, non-LLM)** — poll la file, instancie l'agent assigné, le lance, route le résultat.
3. **Délégation Cortex → sous-tâches (SSOT)** — le Cortex produit des `sub_tasks` → le Dispatcher les route ; les spécialistes exécutent, ne planifient pas.
4. **Un spécialiste de démo** — réutilise la boucle de l'étape 3 avec un autre system prompt ; prouve le cycle Cortex → spécialiste → rapport → Cortex.
5. **Async borné + STOP de bout en bout** — `ThreadPoolTaskExecutor` à pool **borné** ; `Dispatcher.stop(taskId)` → flag → arrêt propre → file nettoyée.

*Hors scope : roster réel, file durable, priorisation multi-projets, reprise sur crash.*

### Étape 8 — Pilotage & observabilité
*Conduire le système et savoir ce qu'il a fait. Intègre Telegram.*

1. **Journal d'actions (log fichier append-only)** — JSONL des décisions, tool_calls, résultats, transitions. Sert l'audit et le debug (et serait la matière première d'un futur apprentissage).
2. **Audit des actions VPS** — toute mutation tracée ; la passerelle reste le point de passage unique qui journalise l'exécution réelle.
3. **Interface de pilotage minimale** — soumettre une demande, lister les tâches actives, STOP, statut. Console + REST léger.
4. **Adaptateur Telegram — `notify()`** ⭐ — réutilisation du module multibot existant pour les notifications (fin de tâche, blocage, build/deploy terminé…). Coût quasi nul : module prêt + port déjà défini.
5. **Adaptateur Telegram — `ask()` + commandes** — HITL interactif via boutons inline + commandes `/stop`, `/status`.

*Note : Telegram sert tout le système, pas seulement le VPS. Console et Telegram sont deux adaptateurs du même port.*

---

## 4. Séquencement & dépendances

| Étape | Dépend de | Parallélisable avec | Note |
|-------|-----------|---------------------|------|
| 1 — Fondations | — | — | Préalable à tout |
| 2 — Contrats | 1 | — | Définit les prises |
| 3 — Boucle fiable | 2 | — | Contient le go/no-go LLM (point de risque critique) |
| 4 — HITL | 2, 3 | 5 | — |
| 5 — Mémoire factuelle | 2, 4 | — | Persiste les consentements de 4 |
| 6 — Outils & passerelle | 2, 3, 4 | 5 | HITL requis pour les outils risqués |
| 7 — Orchestrateur minimal | 3 | 6 | Réutilise la boucle de 3 |
| 8 — Pilotage & Telegram | 2, 4 | 6, 7 | Adaptateurs du port `HumanInteraction` |

---

## 5. Points encore ouverts / à dérisquer

- **Provider LLM (cloud vs local)** — à arbitrer ; tranché empiriquement par le spike de l'étape 3. Le port LLM rend le choix réversible par configuration.
- **Fiabilité du JSON structuré selon le modèle** — go/no-go de l'étape 3 : seuil >95 % de JSON valide, sinon changer de modèle.
- **Maturité de Spring AI** — à vérifier en amont : function calling (selon provider/modèle), structured output / JSON mode, comportement du VectorStore pgvector (pour le futur apprentissage).
- **`ask()` Telegram en V1 vs fast-follow** — penchant retenu : les deux (`notify()` + `ask()`) dans le V1, le module multibot existant rendant le surcoût faible ; repli possible = `notify()` d'abord.

---

*Document de roadmap consolidé à partir de la session de conception du 2026-06-19. Vivant : à enrichir au fil des décisions d'implémentation.*
