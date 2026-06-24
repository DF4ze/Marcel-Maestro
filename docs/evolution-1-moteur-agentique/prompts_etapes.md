# Prompts de lancement par étape — Marcel Maestro (MM)

Prompts à coller dans une **nouvelle conversation du projet** pour lancer chaque étape.
Le contexte projet (Marcel Maestro, agent Cortex, modules `mm-*`) est déjà en mémoire.
La **roadmap (`docs/roadmap_v1.md`) reste la source de vérité**.

⚠️ **Règles de codage transverses obligatoires** (`docs/coding_rules.md`) : **Lombok partout**,
**`@Slf4j` + logs** (`info` à chaque étape importante, `debug` sur les éléments clés à vérifier),
**JavaDoc sur les méthodes**. Chaque prompt ci-dessous demande de lire ce fichier — elles
s'appliquent à TOUT le code produit, à toutes les étapes.

Suivi : Étapes 1-2 ✅ `done` · Étape 3 🔄 build vert (spike LLM en attente) · Étapes 4-5 ✅ `done` · Étapes 6-7-8 ⏳ à lancer (prompts prêts).

---

## Étape 2 — Contrats du noyau (les prises)

```
Lance l'Étape 2 de la roadmap Marcel Maestro : « Contrats du noyau (les prises) ».

Avant tout, lis ces fichiers comme source de vérité (la roadmap prime) :
- docs/roadmap_v1.md → §3 Étape 2 (les 5 livrables H2)
- docs/architecture_cible.md → §2 (signatures Java exactes des interfaces et types)
- docs/adr.md → ADR-004 (AgentTool+RiskLevel), ADR-005 (HITL 4 niveaux), ADR-006
  (la sortie JSON EST la machine à états), ADR-009 (scope comme attribut)
- docs/points_bloquants.md → PB-04 (frontières exactes core/starter/hôte)

Objectif : définir TOUTES les prises enfichables et les types pivots dans mm-core,
SANS AUCUNE implémentation. « Définir toutes les prises, n'en remplir aucune. »

Livrables (tous dans mm-core, package fr.ses10doigts.mm.core.* ) :
1. Types pivots : AgentContext, AgentStatus (pending/running/done/blocked/trouble/KO),
   AgentResponse (status, reason, output, tool_calls, sub_tasks), TaskMessage.
2. Prise LLM : adopter le ChatClient de Spring AI tel quel (cloud/local via config) —
   AUCUN wrapper maison à créer.
3. SPI Outil : AgentTool (name, description, inputSchema, riskLevel, execute),
   RiskLevel (LOW/MEDIUM/HIGH/CRITICAL), ToolResult.
4. Prise Validation humaine : HumanInteraction (ask/notify), HitlRequest,
   ConsentDecision (ALLOW_ONCE/SESSION/PROJECT/ALWAYS + DENY).
5. Prises Mémoire : FactStore / MemoryStore (défini, non rempli — sera implémenté
   à l'étape 5) et SemanticMemory (défini mais LAISSÉ VIDE — couture pour
   l'apprentissage différé). Inclure aussi le port Journal (cf. PB-04).

Contraintes :
- mm-core reste PUR : pas de spring-web, spring-data, ni rien de métier. Le litmus
  maven-enforcer doit continuer à passer.
- Le system prompt de base (format JSON + boucle) appartient au noyau, extensible
  par l'hôte mais pas remplaçable (cf. PB-04 Q1 : bean SystemPromptExtension).
- Parsing déterministe, jamais de NLP. Le JSON invalide = erreur.
- Aucune logique de boucle agentique ici (c'est l'étape 3) — uniquement les contrats.

Méthode :
- Crée une task list.
- Commence par me proposer l'inventaire complet des interfaces/types/enums avec leurs
  signatures (et les questions de frontière non tranchées) pour validation AVANT
  d'écrire le code.
- Puis implémente les contrats dans mm-core.
- Termine par une vérification : je builderai sur mon poste Windows (Java 21) et te
  collerai la sortie de `mvn verify` — adapte-toi à ce mode (tu ne peux pas builder
  toi-même).

Hors scope : outils réels, mémoire persistante, spécialistes, boucle d'exécution.
```

---

## Étape 3 — Boucle agentique fiable

```
Lance l'Étape 3 de la roadmap Marcel Maestro : « Boucle agentique fiable ».
Prérequis : l'Étape 2 (contrats du noyau) doit être terminée et le build vert.

Avant tout, lis ces fichiers comme source de vérité (la roadmap prime) :
- docs/roadmap_v1.md → §3 Étape 3 (les 5 livrables H2) + §5 (points à dérisquer)
- docs/architecture_cible.md → §2.5 (AgentStatus + format JSON imposé) et §3 (flux
  d'une requête de bout en bout, routage par statut)
- docs/adr.md → ADR-006 (la sortie JSON structurée EST la machine à états ; pas de
  LangGraph ; ce que le switch custom doit couvrir manuellement)
- docs/points_bloquants.md → PB-09 (robustesse du parsing JSON, modes de défaillance),
  PB-08 (fiabilité structured output selon le modèle), PB-07 (STOP propre, boucle
  infinie), PB-02/PB-11 (provider LLM, JSON mode Spring AI)

Objectif : implémenter dans mm-core la boucle agentique de Cortex, fiable et bornée,
en s'appuyant sur les contrats définis à l'étape 2. L'agent planificateur s'appelle
Cortex (ex-« Chef »).

Livrables :
1. SPIKE DE DÉRISQUAGE JSON (go/no-go) — À FAIRE EN PREMIER, avant d'écrire la boucle.
   Valider empiriquement que le LLM produit du JSON conforme au contrat AgentResponse
   à >95 % sur 2-3 modèles. Si non concluant : signaler et proposer un changement de
   modèle avant d'aller plus loin. (Ce test nécessite un provider LLM réel — voir note.)
2. System prompt & contrat de sortie — prompt de base imposant le format AgentResponse ;
   vit dans le noyau, EXTENSIBLE par l'hôte mais PAS remplaçable (bean SystemPromptExtension).
3. Parsing robuste & cas dégradés — désérialisation déterministe (Jackson) + filets :
   JSON mode du provider si dispo, fallback regex pour extraire le bloc JSON, détection
   de troncature via finishReason (STOP vs LENGTH). Échec de parsing → status TROUBLE +
   retry sur prompt renforcé. JAMAIS d'interprétation NLP du texte libre.
4. Machine à états (routage par statut) — switch EXHAUSTIF sur AgentStatus. En V1,
   trouble = retry simple (la recherche en mémoire sur erreur reste une couture ouverte,
   non implémentée ici).
5. Bornage, garde-fous & STOP — maxIterations (configurable) + compteurs par statut
   (troubleCount, runningSansProgrès) + détection de boucle infinie → KO. Flag
   AtomicBoolean « stopped » vérifié UNIQUEMENT entre opérations atomiques (avant appel
   LLM, après réception d'un résultat), jamais en milieu d'opération.

Contraintes :
- mm-core reste PUR (litmus maven-enforcer doit passer). La boucle utilise le ChatClient
  Spring AI INJECTÉ (le bean concret est configuré côté starter/app, pas dans le noyau).
- Tests unitaires de la boucle SANS LLM réel : utiliser un MockChatClient (réponses JSON
  scriptées : done, running, blocked, trouble, KO, JSON invalide, JSON tronqué).
- Déterministe en bas, LLM pour le jugement seulement.

Méthode :
- Crée une task list.
- Propose-moi d'abord le découpage (classes/responsabilités : StateMachine, parser,
  garde-fous, contrat de prompt) et le plan du spike, pour validation AVANT d'écrire le code.
- Puis implémente.
- Vérification finale : je builderai sur mon poste Windows (Java 21, `mvn verify`) et,
  pour le spike, je lancerai les appels LLM réels et te collerai les résultats. Tu ne
  peux pas builder ni appeler le LLM toi-même — conçois le travail pour ce mode.

Note provider LLM (PB-02, non encore tranché) : le spike de l'étape 3 est le moment de
trancher empiriquement cloud vs local. Si aucun provider n'est encore configuré,
demande-moi lequel utiliser (clé API / endpoint) avant de lancer le spike.

Hors scope : outils réels, mémoire persistante, spécialistes, HITL (étape 4).
```

---

## Étape 4 — Garde-fou humain (HITL)

```
Lance l'Étape 4 de la roadmap Marcel Maestro : « Garde-fou humain (HITL) ».
Prérequis : l'Étape 3 (boucle agentique fiable) doit être terminée et le build vert.

Avant tout, lis ces fichiers comme source de vérité (la roadmap prime) :
- docs/roadmap_v1.md → §3 Étape 4 (les 5 livrables H2)
- docs/architecture_cible.md → §2.2 (port HumanInteraction, HitlRequest,
  ConsentDecision) et §3 (flux : tool_call → HitlGuard.check → ask/notify)
- docs/adr.md → ADR-005 (HITL 4 niveaux ; le moteur décide QUAND, l'hôte décide COMMENT)
- docs/points_bloquants.md → PB-04 (frontière : où vit ConsoleHumanInteraction —
  starter vs app) et PB-10 (sécurité, le HITL HIGH comme 1re ligne de défense)
- docs/coding_rules.md → RÈGLES DE CODAGE OBLIGATOIRES : Lombok partout, @Slf4j + logs
  (info aux étapes clés, debug sur les éléments à vérifier), JavaDoc sur les méthodes.
  À appliquer à TOUT le code produit dans cette étape.

Objectif : ajouter la couche de sécurité contextuelle qui complète le plancher dur de
la passerelle. Le moteur décide QUAND demander (HitlGuard, dans mm-core) ; l'hôte décide
COMMENT demander (implémentation concrète du port HumanInteraction).

Livrables :
1. Politique RiskLevel → demande — LOW = exécution directe ; MEDIUM/HIGH/CRITICAL =
   validation obligatoire ; seuil configurable.
2. HitlGuard (le « quand ») — dans mm-core. Intercepte AVANT l'exécution d'un outil,
   lit le riskLevel, consulte le cache de consentement, tranche (demander ou non).
3. Niveaux & cache de consentement — ALLOW_ONCE et ALLOW_SESSION pleinement
   fonctionnels via un cache in-memory (dans mm-core). ALLOW_PROJECT et ALLOW_ALWAYS
   sont ACCEPTÉS mais PAS encore persistés (persistance = étape 5, FactStore) — pour
   l'instant ils se comportent comme session, avec une couture claire pour la persistance.
4. ConsoleHumanInteraction (le « comment ») — PREMIÈRE implémentation concrète d'un port
   du noyau : ask() (lit stdin) + notify() (écrit stdout). Va dans mm-spring-boot-starter
   comme implémentation par défaut (à confirmer selon PB-04 — me proposer le choix).
5. Intégration dans la boucle (cas blocked) — sur status BLOCKED ou tool nécessitant
   consentement : ask() → attend la décision → reprend si ALLOW, KO si DENY.

Contraintes :
- mm-core reste PUR (litmus maven-enforcer doit passer) : HitlGuard, la politique et le
  cache in-memory sont du noyau ; AUCUNE implémentation concrète de canal dans mm-core.
- ConsoleHumanInteraction est un adaptateur concret → hors du noyau (starter/app).
- Autorisation ≠ consentement : le HITL ne gère QUE le consentement (faut-il confirmer).
  L'autorisation (quels outils un agent possède) est un autre axe, traité à l'étape 6.
- Tests sans interaction réelle : mocker HumanInteraction pour scripter ALLOW/DENY et
  vérifier le routage (reprise vs KO) et le comportement du cache (session ne redemande pas).

Méthode :
- Crée une task list.
- Propose-moi d'abord le découpage (HitlGuard, politique riskLevel, ConsentCache,
  point d'interception dans la boucle, emplacement de ConsoleHumanInteraction) pour
  validation AVANT d'écrire le code.
- Puis implémente.
- Vérification finale : je builderai sur mon poste Windows (Java 21, `mvn verify`) et je
  testerai le flux console à la main (ask/notify) — tu ne peux pas builder toi-même,
  conçois le travail pour ce mode.

Hors scope : persistance ALLOW_PROJECT/ALLOW_ALWAYS (étape 5), canal Telegram/web
(étape 8), autorisation/liste blanche d'outils (étape 6).
```

---

## Étape 5 — Mémoire factuelle

```
Lance l'Étape 5 de la roadmap Marcel Maestro : « Mémoire factuelle ».
Prérequis : l'Étape 4 (HITL) doit être terminée et le build vert — l'étape 5 PERSISTE
les consentements ALLOW_PROJECT / ALLOW_ALWAYS définis à l'étape 4.

Avant tout, lis ces fichiers comme source de vérité (la roadmap prime) :
- docs/roadmap_v1.md → §3 Étape 5 (les 5 livrables H2)
- docs/architecture_cible.md → §2.3 (port MemoryStore / MemoryEntry), §5 (mémoire
  réconciliée : JpaMemoryStore → SQLite ; C6 fusionnée dans C3 via scope)
- docs/adr.md → ADR-009 (C3/C6 fusionnées, scope comme attribut), ADR-013 (tenant dès
  J1, non implémenté), ADR-014 (SQLite pour démarrer, PostgreSQL quand pgvector arrive)
- docs/points_bloquants.md → PB-06 (propagation tenant — DIFFÉRÉE), PB-04 (frontières)
- docs/coding_rules.md → RÈGLES DE CODAGE OBLIGATOIRES : Lombok partout, @Slf4j + logs
  (info aux étapes clés, debug sur les éléments à vérifier), JavaDoc sur les méthodes.
  À appliquer à TOUT le code produit dans cette étape (entité, repository, JpaMemoryStore…).

Objectif : rendre persistants, au redémarrage, les faits utiles et la confiance accordée
(consentements HITL projet/toujours). On REMPLIT la prise FactStore/MemoryStore définie
à l'étape 2, avec une implémentation SQLite — SANS toucher au noyau.

Livrables :
1. Implémentation FactStore (SQLite) — put / get / findByScope / delete via Spring Data
   JPA + SQLite (org.xerial:sqlite-jdbc). VA DANS mm-spring-boot-starter (implémentation
   par défaut d'un port), JAMAIS dans mm-core.
2. Schéma & migrations — table MemoryEntry (key, value, scope, tenant, createdAt,
   updatedAt) via Flyway. Le champ tenant est PRÉSENT mais FIGÉ à "default" (couture
   multi-artisan sans le coût). scope porte "global" | "project:<id>" | "session:<id>".
3. Persistance des consentements HITL — ALLOW_PROJECT et ALLOW_ALWAYS écrits dans le
   FactStore (clé du type hitl:<tool>:<scope>) et RECHARGÉS au démarrage de session,
   avant le premier appel LLM. Brancher ça sur le ConsentCache de l'étape 4.
4. Lecture/écriture de faits par l'agent — capture simple et EXPLICITE : un outil
   « retiens ceci » et/ou un hook de fin de tâche. PAS de bus d'événements (différé).
5. Litmus de pureté — vérifier qu'AUCUNE dépendance SQLite/JPA ne contamine mm-core
   (l'enforcer de mm-core bannit déjà spring-data — ça doit rester vert).

Contraintes :
- mm-core reste PUR : la prise MemoryStore y est déjà définie (étape 2) ; l'implémentation
  JPA/SQLite va dans le starter. Spring Data JPA et sqlite-jdbc ne descendent JAMAIS dans
  le noyau (le litmus maven-enforcer le garantit).
- Gotcha SQLite + Hibernate : prévoir un dialecte SQLite (ex. hibernate-community-dialects)
  et la config Spring Data adéquate. À valider au premier build.
- Tenant figé à "default" : aucune logique de filtrage multi-tenant (différée, PB-06).
- Tests : repository JPA testé sur SQLite (fichier temp ou in-memory) ; vérifier le cycle
  put → get → findByScope → delete et le rechargement des consentements au redémarrage.

Méthode :
- Crée une task list.
- Propose-moi d'abord le schéma de table, la stratégie Flyway, l'emplacement des classes
  (entité, repository, JpaMemoryStore) et le branchement sur le ConsentCache de l'étape 4,
  pour validation AVANT d'écrire le code.
- Puis implémente.
- Vérification finale : je builderai sur mon poste Windows (Java 21, `mvn verify`) et je
  testerai la persistance (arrêt/relance → consentement ALLOW_ALWAYS toujours actif).
  Tu ne peux pas builder toi-même — conçois le travail pour ce mode.

Hors scope : SemanticMemory / C4 vectorielle (reste vide), pgvector, PostgreSQL,
distillation nocturne, bus d'événements, filtrage multi-tenant.
```

---

## Étape 6 — Outils & passerelle VPS

```
Lance l'Étape 6 de la roadmap Marcel Maestro : « Outils & passerelle VPS ».
Prérequis : Étapes 2, 3 et 4 terminées et build vert (les outils risqués s'appuient sur
le HITL de l'étape 4).

Avant tout, lis ces fichiers comme source de vérité (la roadmap prime) :
- docs/roadmap_v1.md → §3 Étape 6 (les 5 livrables H2) + §2 (principes : outil-vs-agent,
  grossier ≠ monolithique, autorisation ≠ consentement, sécurité en double couche,
  passerelle gardée indépendante)
- docs/architecture_cible.md → §2.1 (SPI AgentTool) et §3 (flux : tool_call → HitlGuard
  → AgentTool.execute → ToolResult)
- docs/adr.md → ADR-004 (AgentTool adapté vers FunctionCallback par un converter INTERNE
  au moteur ; l'hôte n'implémente qu'AgentTool)
- docs/points_bloquants.md → PB-05 (AgentTool face aux cas réels : params structurés,
  outil long-running/timeout, fiabilité du function calling), PB-10 (sécurité/isolation :
  path-traversal, rate limiting, JAMAIS de code dynamique construit par le LLM), PB-07
  (idempotence des tool calls)
- docs/coding_rules.md → RÈGLES DE CODAGE OBLIGATOIRES : Lombok partout, @Slf4j + logs
  (info aux étapes clés — appel d'outil, autorisation, exécution, résultat —, debug sur
  les éléments à vérifier), JavaDoc sur les méthodes. À appliquer à TOUT le code produit.

Objectif : le moteur touche enfin le monde réel via des outils. Distinguer clairement
AUTORISATION (quels outils un agent possède) et CONSENTEMENT (HITL, déjà fait à l'étape 4).

Livrables :
1. Adaptateur AgentTool → Spring AI — converter INTERNE au moteur (mm-core) qui expose les
   outils (avec leur riskLevel) au LLM via FunctionCallback. L'hôte n'implémente QUE
   l'interface AgentTool ; le moteur traduit. Ne PAS réenrober le schema JSON de Spring AI.
2. Registre & injection par contexte — chaque agent reçoit sa LISTE BLANCHE d'outils à
   l'instanciation (déclarative, nominative). Les outils non accordés sont INVISIBLES du
   LLM (pas seulement refusés à l'exécution). C'est l'axe AUTORISATION.
3. Sécurité d'exécution (transverse, AVANT tout outil réel) — validation des chemins
   (anti path-traversal, workspace racine configurable), timeout par outil (→ TROUBLE si
   dépassé), AUCUN code dynamique construit par le LLM (les paramètres sont des données,
   pas des instructions). Brancher le HitlGuard de l'étape 4 sur l'exécution des outils.
4. Outils de dev local (dans mm-app, JAMAIS dans mm-core) — build Maven, lecture/écriture
   de fichiers, lecture de logs. riskLevel calibré : lecture = LOW, écriture/exécution =
   HIGH (donc HITL obligatoire).
5. Branchement de la passerelle VPS via MCP (dans mm-app) — la passerelle expose un
   serveur MCP en local. mm-app la consomme avec le client MCP de Spring AI
   (org.springframework.ai:spring-ai-starter-mcp-client, version gérée par le BOM ;
   transport STDIO si la passerelle est lancée en process, SSE si c'est un serveur local).
   IMPORTANT — NE PAS auto-exposer tous les outils MCP au LLM (piège « MCP+LLM coûteux » :
   chaque appel passerait par le function-calling, et on perdrait le contrôle riskLevel/
   HITL/autorisation). À la place : mm-app détient le client MCP et on écrit de fines
   implémentations AgentTool DISTINCTES (build, build_and_deploy, service_management…) qui
   appellent DIRECTEMENT l'outil MCP correspondant. Chaque outil porte son riskLevel
   calibré ; la granularité d'un outil = le grain d'autorisation. NE RIEN reconstruire :
   mapper vers les playbooks/outils MCP existants. Les secrets VPS ne remontent JAMAIS
   dans le noyau ; la passerelle reste indépendante et est le point de passage unique pour
   l'audit des mutations VPS. (spring-ai-starter-mcp-client va dans mm-app, jamais mm-core.)
6. RÉFLEXION FINALE — « se brancher autrement à la passerelle » (À DÉROULER SUR LE PC
   FINAL qui détient le code source de la passerelle). Le branchement MCP générique du
   livrable 5 est un point de départ. Mener une réflexion (et produire une courte note
   docs/passerelle_integration.md) sur une exposition OUTIL PAR OUTIL : faire évoluer la
   passerelle pour exposer chaque capacité comme un endpoint/outil dédié et typé, plutôt
   qu'un MCP générique — meilleur contrôle du riskLevel, de l'autorisation et de l'audit,
   schémas de paramètres explicites, et indépendance vis-à-vis du function-calling MCP.
   Comparer : MCP générique vs exposition outil-par-outil (avantages/inconvénients, impact
   frontière mm-app, secrets, coût, fiabilité). NE PAS implémenter ici si le code source de
   la passerelle n'est pas disponible : cette sous-étape se termine sur le PC final, là où
   on peut modifier la passerelle pour trouver la meilleure solution d'exposition.

Contraintes :
- mm-core reste PUR (litmus maven-enforcer) : le converter, le registre et la sécurité
  d'exécution transverse sont du noyau ; les outils CONCRETS (dev local, passerelle) sont
  dans mm-app. Aucun secret, aucune logique d'environnement dans mm-core.
- Outil-vs-agent : une capacité déterministe est un OUTIL appelé dans la boucle, jamais un
  agent. Grossier ≠ monolithique : un outil par opération séparément autorisable, pas une
  primitive SSH générique ni un gros outil « passerelle » unique.
- Tests : AgentTool mockés pour vérifier le converter, l'invisibilité des outils hors
  liste blanche, le rejet d'un path hors workspace, le timeout → TROUBLE, et le passage
  par HitlGuard sur un outil HIGH.

Question à me poser avant de coder : faut-il fournir un idempotencyKey natif dans
AgentContext (PB-07, recommandé : natif dans le moteur, transparent pour l'hôte) ? Trancher
avec moi avant d'implémenter les outils à effet de bord.

Méthode :
- Crée une task list.
- Propose-moi d'abord le découpage (converter AgentTool→FunctionCallback, ToolRegistry +
  liste blanche, couche de sécurité d'exécution, inventaire des outils dev local, mapping
  passerelle VPS) et la question idempotence, pour validation AVANT d'écrire le code.
- Puis implémente.
- Vérification finale : je builderai sur mon poste Windows (Java 21, `mvn verify`) ; les
  outils passerelle VPS et le build Maven réel, je les testerai moi-même (tu n'as pas accès
  à la passerelle ni au VPS). Conçois le travail pour ce mode.

Hors scope : outils métier artisan, email, génération de documents (étapes ultérieures) ;
orchestrateur/spécialistes (étape 7) ; Telegram (étape 8).
```

---

## Étape 7 — Orchestrateur minimal

```
Lance l'Étape 7 de la roadmap Marcel Maestro : « Orchestrateur minimal ».
Prérequis : l'Étape 3 (boucle agentique) terminée et build vert. But : PROUVER les
coutures (déléguer, router, rapporter, arrêter), PAS le roster réel de spécialistes.

Avant tout, lis ces fichiers comme source de vérité (la roadmap prime) :
- docs/roadmap_v1.md → §3 Étape 7 (les 5 livrables H2)
- docs/architecture_cible.md → §4 (composant Dispatcher, code de référence) et §3
  (flux des sub_tasks Cortex → spécialiste → rapport → Cortex)
- docs/adr.md → ADR-007 (Cortex SEUL planifie, SSOT), ADR-008 (communication inter-agents
  par file de TaskMessage typés, zéro dialogue LLM↔LLM direct), ADR-015 (BlockingQueue
  in-memory, non-durable assumé)
- docs/points_bloquants.md → PB-07 (STOP propre entre opérations atomiques, idempotence),
  PB-06 Q3 (priorisation multi-tenant — DIFFÉRÉE)
- docs/coding_rules.md → RÈGLES DE CODAGE OBLIGATOIRES : Lombok partout, @Slf4j + logs
  (info : tâche soumise/poll/instanciation d'agent/routage de rapport/STOP ; debug :
  contenu des TaskMessage, état de la file), JavaDoc sur les méthodes.

Rappel terminologie : l'agent planificateur = Cortex ; l'« orchestrateur » = le Dispatcher
(code de routage NON-LLM), à ne pas confondre.

Livrables :
1. File de tâches typée (in-memory) — port TaskQueue dans mm-core + implémentation
   InMemoryTaskQueue (LinkedBlockingQueue<TaskMessage>) dans le starter. TOUTE communication
   inter-agents transite par la file ; AUCUN dialogue LLM↔LLM direct. Non-durable (assumé,
   documenté).
2. Dispatcher (permanent, NON-LLM) — poll la file, instancie l'agent assigné, le lance via
   le pool, route le résultat. C'est un @Component permanent (@PostConstruct), pas un agent.
3. Délégation Cortex → sous-tâches (SSOT) — Cortex produit des sub_tasks dans sa réponse →
   le Dispatcher les route vers le spécialiste assigné. Les spécialistes EXÉCUTENT, ne
   planifient JAMAIS (ADR-007).
4. Un spécialiste de démo — réutilise la boucle de l'étape 3 avec un autre system prompt
   (ex. EchoSpecialist dans mm-app). Prouve le cycle complet Cortex → spécialiste →
   rapport (TaskMessage) → Cortex l'intègre à son itération suivante.
5. Async borné + STOP de bout en bout — ThreadPoolTaskExecutor à pool BORNÉ (maxPoolSize
   explicite, configurable). Dispatcher.stop(taskId) → flag AtomicBoolean → arrêt propre
   (vérifié uniquement entre opérations atomiques) → file nettoyée → rapport
   status:KO, reason:"stopped by user".

Contraintes :
- mm-core reste PUR (litmus) : port TaskQueue + logique d'orchestration côté noyau ;
  l'implémentation in-memory et le spécialiste de démo concret côté starter/app.
- Outil-vs-agent : un spécialiste n'existe que pour un raisonnement séparable, pas pour
  une micro-boucle opérationnelle (qui reste un outil dans la boucle de l'agent).
- Tests : deux tâches simultanées → traitées en parallèle dans le pool → deux rapports ;
  pool plein (N+1 pour un pool de N) → la N+1ᵉ attend sans crash ; STOP en cours →
  arrêt propre, aucun thread orphelin.

Méthode :
- Crée une task list.
- Propose-moi d'abord le découpage (port TaskQueue, InMemoryTaskQueue, Dispatcher,
  AgentFactory/registre des spécialistes, spécialiste de démo, intégration STOP) pour
  validation AVANT d'écrire le code.
- Puis implémente.
- Vérification finale : je builderai sur mon poste Windows (Java 21, `mvn verify`) et je
  testerai le cycle délégation + STOP. Tu ne peux pas builder toi-même — conçois pour ce mode.

Hors scope : roster réel de spécialistes, file durable, priorisation multi-projets,
reprise sur crash, Telegram (étape 8).
```

---

## Étape 8 — Pilotage & observabilité (intègre Telegram)

```
Lance l'Étape 8 de la roadmap Marcel Maestro : « Pilotage & observabilité » (dernière
étape du V1). Prérequis : Étapes 2 et 4 terminées (le port HumanInteraction existe) ;
idéalement Étapes 6 et 7 faites pour avoir des outils et l'orchestration à piloter/journaliser.

Avant tout, lis ces fichiers comme source de vérité (la roadmap prime) :
- docs/roadmap_v1.md → §3 Étape 8 (les 5 livrables H2)
- docs/architecture_cible.md → §2.2 (port HumanInteraction : Telegram = un autre
  adaptateur du MÊME port que la console) et §5 (FileJournal, C2)
- docs/adr.md → ADR-016 (UN SEUL bot Telegram, contexte dans le message), ADR-017
  (API REST + Telegram, PAS de web GUI riche)
- docs/points_bloquants.md → PB-04 Q2 (port Journal dans mm-core, FileJournal dans le
  starter) et PB-04 Q4 (TelegramHumanInteraction dans mm-app), PB-10 (ne pas logguer de
  secret/donnée sensible en clair dans le journal)
- docs/coding_rules.md → RÈGLES DE CODAGE OBLIGATOIRES : Lombok partout, @Slf4j + logs,
  JavaDoc sur les méthodes.

Contexte asset : le module multibot Telegram est DÉJÀ développé (asset réutilisable). On le
branche comme adaptateur du port HumanInteraction — on ne le réécrit pas.

Objectif : conduire le système et savoir ce qu'il a fait. Console et Telegram sont deux
adaptateurs du même port HumanInteraction ; Telegram sert TOUT le système (dev + VPS),
pas seulement la surveillance VPS.

Livrables :
1. Journal d'actions (log fichier append-only) — implémentation FileJournal (JSONL par
   agent/jour) du port Journal défini en amont : décisions, tool_calls, résultats,
   transitions de statut. Sert l'audit et le debug. Verrou fichier pour éviter les écritures
   concurrentes. JAMAIS de secret/donnée sensible en clair.
2. Audit des actions VPS — toute mutation VPS tracée ; la passerelle reste le point de
   passage unique qui journalise l'exécution réelle (cf. étape 6).
3. Interface de pilotage minimale — soumettre une demande, lister les tâches actives,
   STOP, statut. Console + REST léger (spring-web dans mm-app uniquement ; PAS de web GUI).
4. Adaptateur Telegram — notify() ⭐ — réutiliser le module multibot existant pour les
   notifications (fin de tâche, blocage, build/deploy terminé…). TelegramHumanInteraction
   dans mm-app. Coût quasi nul : module prêt + port déjà défini.
5. Adaptateur Telegram — ask() + commandes — HITL interactif via boutons inline
   (Une fois / Session / Projet / Toujours / Refuser) + commandes /stop <taskId> et /status.

Contraintes :
- mm-core reste PUR (litmus) : port Journal côté noyau ; FileJournal, REST, Telegram tous
  côté starter/app. spring-web et le module Telegram ne descendent JAMAIS dans mm-core.
- Un seul bot (ADR-016) : le contexte (quel agent, quelle tâche) est inclus dans le message.
- Console et Telegram coexistent : deux adaptateurs du même port, sans impact sur le noyau.

Méthode :
- Crée une task list.
- Propose-moi d'abord le découpage (FileJournal, contrôleur REST de pilotage, branchement
  du module multibot en TelegramHumanInteraction notify() puis ask()+commandes) pour
  validation AVANT d'écrire le code.
- Puis implémente, idéalement dans l'ordre : journal → REST → Telegram notify() →
  Telegram ask()+commandes (repli possible : notify() d'abord, ask() en fast-follow).
- Vérification finale : je builderai sur mon poste Windows (Java 21, `mvn verify`) et je
  testerai le flux Telegram réel (token/chatId). Tu ne peux pas builder ni joindre Telegram
  toi-même — conçois pour ce mode.

Hors scope : web GUI riche, multi-utilisateurs Telegram, un bot par agent, scoring de
fraîcheur mémoire, apprentissage nocturne (tout différé hors V1).
```

---

## Étapes suivantes

Les 8 étapes du V1 ont maintenant leur prompt. Après le V1, la prochaine phase
(apprentissage nocturne + mémoire vectorielle C4, métier artisan, multi-tenant réel, etc.)
fera l'objet d'une roadmap séparée.
```
