# Prompts de lancement par étape — Marcel Maestro (MM)

Prompts à coller dans une **nouvelle conversation du projet** pour lancer chaque étape.
Le contexte projet (Marcel Maestro, agent Cortex, modules `mm-*`) est déjà en mémoire.
La **roadmap (`docs/roadmap_v1.md`) reste la source de vérité**.

⚠️ **Règles de codage transverses obligatoires** (`docs/coding_rules.md`) : **Lombok partout**,
**`@Slf4j` + logs** (`info` à chaque étape importante, `debug` sur les éléments clés à vérifier),
**JavaDoc sur les méthodes**. Chaque prompt ci-dessous demande de lire ce fichier — elles
s'appliquent à TOUT le code produit, à toutes les étapes.

Suivi : Étape 1 ✅ `done` · Étape 2 ✅ `done` · Étape 3 🔄 build vert (spike LLM en attente) · Étape 4 ⏳ à lancer.

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

## Étapes suivantes

Les prompts des étapes 6 à 8 seront ajoutés ici au fil de l'avancement (un prompt par
étape, généré quand l'étape précédente est `done`).
```
