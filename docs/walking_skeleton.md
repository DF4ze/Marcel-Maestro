# Walking Skeleton + Jalons d'implémentation
**Statut : Document de travail**
**Principe** : chaque jalon livre une tranche verticale fonctionnelle de bout en bout. On élargit — on n'empile pas.

---

## Pré-requis aux jalons : décisions bloquantes

Deux décisions (PB-01 et PB-02) doivent être tranchées avant d'écrire une ligne de code fonctionnelle.

| Décision | Impact si non tranchée |
|----------|----------------------|
| PB-01 : SaaS vs local | Architecture de déploiement, multi-tenant activé ou non, Spring Security ou non |
| PB-02 : Provider LLM | `application.properties` de base, configuration des tests d'intégration |

**Hypothèses retenues pour les jalons ci-dessous** (à réviser si PB-01/PB-02 tranchés différemment) :
- SaaS simplifié : un seul tenant `"default"`, pas de Spring Security en MVP
- Provider LLM : configurable via Spring AI, tests M1 avec un provider cloud (OpenAI ou Anthropic) pour éviter les problèmes hardware
- Ollama reste disponible comme option locale documentée

---

## M0 — Structure du projet
**Durée estimée** : 2-3 jours
**Objectif** : Le projet compile, démarre, et prouve les frontières de modules.

**Livré** :
- Mono-repo Maven avec 4 modules : `mm-core`, `mm-spring-boot-starter`, `mm-batch`, `mm-app`
- `mm-core` : interfaces `AgentTool`, `HumanInteraction`, `MemoryStore`, `SemanticMemory`, port `Journal`, enums `RiskLevel`, `AgentStatus`, records `AgentContext`, `TaskMessage`, `AgentResponse`
- `mm-spring-boot-starter` : `AgentCoreAutoConfiguration` vide (câble les beans, aucune implémentation fonctionnelle)
- `mm-app` : `MarcelMaestroApplication` qui démarre et affiche "AgentCore loaded" avec les beans du starter
- CI de base : `mvn verify` passe sur tous les modules

**Critère de validation** :
```bash
mvn -pl mm-core verify                          # BUILD SUCCESS, 0 dépendance métier
mvn verify                                          # BUILD SUCCESS sur tous les modules
mvn -pl mm-core dependency:tree | grep "mm-app\|artisan\|devis\|facture"  # 0 ligne
java -jar mm-app/target/mm-app.jar  # "Started MarcelMaestroApplication"
```

**Hors scope** : Toute logique fonctionnelle. Aucun appel LLM. Aucune base de données.

---

## M1 — Boucle LLM minimale + machine à états
**Durée estimée** : 5-7 jours
**Objectif** : Une requête texte entre, passe par le Cortex, produit un structured output parsé, la machine à états route correctement sur tous les cas.

**Livré** :
- `CortexAgent` avec boucle bornée (`maxIterations` configurable, défaut : 10)
- System prompt imposant le format JSON de sortie
- Intégration Spring AI `ChatClient` (provider cloud via `application.properties`)
- `StateMachine` : switch exhaustif sur `AgentStatus`, tous les cas couverts (certains ne font que logger en M1)
- Compteurs de protection : `troubleCount` (max 3), `runningWithoutProgressCount` (max 5)
- Fallback parsing JSON (regex sur bloc JSON si parsing direct échoue)
- `ConsoleHumanInteraction` : implémentation minimale stdin/stdout pour les demandes HITL
- Test empirique structured output : même prompt avec 2-3 modèles, mesure du taux de JSON valide

**Critère de validation** :
```bash
# Requête simple : "What is 2+2?" → status: done en < 5s (provider cloud)
# Requête impossible : "Do the impossible" → status: KO en ≤ maxIterations appels LLM
# STOP pendant traitement → status: KO "stopped by user" propre, aucun thread orphelin
# JSON invalide simulé (mock) → fallback regex → si invalide → status: TROUBLE → retry
```

**Point de risque critique** : C'est ici que PB-09 (robustesse JSON) et PB-08 (fiabilité du structured output selon le modèle) sont validés empiriquement. Si le modèle choisi produit du JSON invalide > 10% du temps avec le fallback activé, le modèle doit être changé avant d'aller plus loin.

**Hors scope** : Outils réels. Mémoire persistante. Spécialistes. Telegram. Base de données.

---

## M2 — Premier outil + HITL de session
**Durée estimée** : 5-7 jours
**Objectif** : Le Cortex peut appeler un outil, le HITL intercepte selon le `RiskLevel`, l'utilisateur décide, le cache de session est géré.

**Livré** :
- `HitlGuard` : intercepte avant toute exécution selon `RiskLevel`, vérifie le cache de consentement
- `ConsentCache` : in-memory, scoped à la session (`Map<String, ConsentDecision>`)
- Deux outils de démo dans `mm-app` :
  - `GetCurrentDateTool` (`RiskLevel.LOW` → pas de HITL, exécuté directement)
  - `WriteFileTool` (`RiskLevel.HIGH` → HITL obligatoire, path restreint au workspace)
- `ConsoleHumanInteraction.ask()` fonctionnel : affiche la question, lit stdin, retourne `ConsentDecision`
- Validation du path dans `WriteFileTool` : rejet de tout path en dehors du workspace configuré (voir PB-10)

**Critère de validation** :
```bash
# "What's today's date?" → GetCurrentDateTool → réponse, aucune demande HITL
# "Write hello to workspace/test.txt" → HITL demandé → user tape "session" → fichier créé
# Même requête dans la même session → HITL non demandé (cache session) → fichier créé directement
# "Write to /etc/passwd" → rejeté par la validation du path avant même le HITL
```

**Hors scope** : Persistance du consentement (ALLOW_PROJECT/ALLOW_ALWAYS). Mémoire C3. Spécialistes.

---

## M3 — Mémoire C3 persistante (SQLite)
**Durée estimée** : 5-7 jours
**Objectif** : Les règles HITL `project` et `always` survivent au redémarrage. Le Cortex peut lire et écrire des faits simples.

**Livré** :
- `JpaMemoryStore` : implémentation C3 via Spring Data JPA + SQLite (`org.xerial:sqlite-jdbc`)
- Champ `tenant` présent dans le schema dès maintenant, valeur `"default"` pour tous les enregistrements
- Persistance des consentements `ALLOW_PROJECT` et `ALLOW_ALWAYS` dans `MemoryStore` (clé : `hitl:<tool_name>:<scope>`)
- Chargement des règles HITL au démarrage de chaque session (avant le premier appel LLM)
- Migrations de schema via Flyway
- `CortexAgent` peut lire/écrire des faits simples via `MemoryStore` (ex: "le workspace artisan est /home/artisan/projets")

**Critère de validation** :
```bash
# User choisit ALLOW_ALWAYS pour WriteFileTool → entrée créée en DB SQLite
# Arrêter et relancer l'app
# "Write hello to workspace/test2.txt" → aucun HITL demandé (chargé depuis SQLite)
# MemoryStore.get("hitl:write_file_tool:global") → ConsentDecision.ALLOW_ALWAYS
# mvn -pl mm-core test → BUILD SUCCESS (aucune dépendance SQLite dans mm-core)
```

**Hors scope** : C4 vectorielle. pgvector. PostgreSQL. Batch de consolidation.

---

## M4 — Dispatcher + un spécialiste + STOP
**Durée estimée** : 7-10 jours
**Objectif** : Le flux asynchrone Cortex → Dispatcher → Spécialiste → rapport → Cortex fonctionne de bout en bout. La commande STOP est propre.

**Livré** :
- `Dispatcher` : `LinkedBlockingQueue<TaskMessage>` + `ThreadPoolTaskExecutor` (pool borné, configurable)
- `InMemoryTaskQueue` implémentant le port `TaskQueue`
- Un spécialiste de démo : `EchoSpecialist` (reçoit une tâche, la renvoie comme `done` avec un résumé)
- `CortexAgent` peut créer des `sub_tasks` dans sa réponse → Dispatcher les route vers le spécialiste assigné
- Rapport du spécialiste retourne via `TaskQueue` → Cortex l'intègre dans son itération suivante
- Commande STOP : flag `AtomicBoolean`, vérifié entre chaque itération (avant appel LLM, après résultat tool) — jamais en milieu d'opération
- À la fin : rapport `status: KO, reason: "stopped by user"`, queue nettoyée

**Critère de validation** :
```bash
# Tâche déléguée → EchoSpecialist traite → Cortex reçoit le rapport → console notifié DONE
# STOP pendant le traitement EchoSpecialist → log "stopped by user" → pas de thread orphelin
# Deux tâches simultanées → traitées en parallèle dans le pool → deux notifications DONE
# Pool plein (N+1 tâches pour un pool de N) → N+1ème attend en queue sans crash
```

**Hors scope** : Spécialiste réel avec LLM. Telegram. Mémoire C4.

---

## M5 — Mémoire C4 + batch de consolidation + error-triggered retrieval
**Durée estimée** : 8-12 jours
**Objectif** : La mémoire procédurale est activée. Le batch nocturne distille les journaux. L'error-triggered retrieval fonctionne.

**Livré** :
- Migration SQLite → PostgreSQL (requis par pgvector)
- Extension pgvector activée sur PostgreSQL (`CREATE EXTENSION vector;`)
- `PgVectorSemanticMemory` : implémentation C4 via `spring-ai-pgvector-store-starter`
- `FileJournal` : append-only JSONL par agent/jour, avec verrou fichier (évite les écritures concurrentes)
- `@Scheduled` batch nocturne : lit les journaux du jour → appel LLM (modèle medium) → extraction faits → `MemoryStore.put()` + `SemanticMemory.store()`
- Archivage gz et suppression du journal du jour
- Error-triggered retrieval dans `CortexAgent` : sur transition `status: TROUBLE`, `SemanticMemory.search(reason, topK=3)` → résultats injectés dans le prochain prompt

**Critère de validation** :
```bash
# Simuler une erreur connue → SemanticMemory.search() déclenché → solution passée injectée
# Batch nocturne lancé manuellement → journaux du jour traités → entrées créées en pgvector
# SemanticMemory.search("file write error") → retourne les solutions historiques
# mvn -pl mm-core test → BUILD SUCCESS (aucune dépendance pgvector dans mm-core)
```

**Hors scope** : Scoring de fraîcheur (`reinforce()`). Spring Batch. Archivage long terme.

---

## M6 — Premier outil artisan réel (QuoteTool)
**Durée estimée** : 8-12 jours
**Objectif** : Valider que le contrat `AgentTool` tient avec une vraie logique métier. Litmus test SRP.

**Livré** (dans `mm-app`, jamais dans `mm-core`) :
- `QuoteTool implements AgentTool` : génère un devis PDF à partir de paramètres structurés
  - `inputSchema()` : client (nom, adresse), lignes de prestation (description, quantité, prix unitaire), taux TVA
  - `riskLevel()` : `MEDIUM` (génère un fichier, ne le transmet pas)
  - `execute()` : génère un PDF via Apache PDFBox (ou iText selon licence choisie)
- Test end-to-end : "Génère un devis pour 5h de plomberie à 65€/h pour M. Dupont" → PDF généré dans le workspace → HITL MEDIUM → confirmation → fichier accessible

**Litmus test SRP (obligatoire)** :
```bash
mvn -pl mm-core test
# → BUILD SUCCESS, sans QuoteTool dans le classpath
mvn -pl mm-core dependency:tree | grep -i "quote\|devis\|artisan\|pdfbox"
# → 0 lignes
```

**Test de fiabilité du function calling** : mesurer si le Cortex (avec le modèle choisi en M1) passe correctement tous les champs du schema `QuoteTool`, notamment les listes imbriquées. C'est la validation empirique de PB-05.

**Critère de validation** :
```bash
# "Génère un devis pour 5h de plomberie à 65€/h pour M. Dupont" → PDF généré
# mm-core compile seul sans QuoteTool
# Le schema est correctement rempli par le LLM sur 10 requêtes de test (taux > 80%)
```

**Hors scope** : Facturation électronique Factur-X (voir PB-03 — décision distincte). Envoi par email. Gestion du stock.

---

## M7 — Telegram HITL + notifications
**Durée estimée** : 7-10 jours
**Objectif** : L'artisan n'a plus besoin d'être derrière une console. Il envoie des messages Telegram et reçoit des notifications push.

**Livré** :
- `TelegramHumanInteraction implements HumanInteraction` dans `mm-app`
  - `ask()` : envoie un message Telegram avec boutons inline (Une fois / Session / Projet / Toujours / Refuser)
  - `notify()` : envoie un message Telegram texte (DONE / KO / BLOCKED avec raison)
- Configuration : un seul `botToken`, un seul `chatId` (un artisan = un chat)
- Commandes Telegram : `/stop <taskId>` → Dispatcher.stop(), `/status` → liste des tâches actives
- Test complet : requête Telegram → `QuoteTool` → HITL via boutons inline Telegram → confirmation → PDF généré → notification DONE Telegram

**Critère de validation** :
```bash
# Message Telegram envoyé → réponse en < 10s (provider cloud)
# Bouton HITL tapé dans Telegram → consentement enregistré → exécution continue
# /stop envoyé pendant traitement → KO propre → notification Telegram "stopped"
# Notification de fin reçue sur Telegram sans intervention console
```

**Hors scope** : Web GUI. Multi-utilisateurs Telegram (plusieurs artisans sur le même bot). Un bot par agent.

---

## Récapitulatif et séquence

| Jalon | Ce qui est prouvé | Durée est. | Bloquants |
|-------|-------------------|-----------|-----------|
| M0 | Frontières de modules, build CI | 2-3j | PB-01/02 tranchés |
| M1 | Boucle LLM + machine à états | 5-7j | Provider LLM choisi |
| M2 | AgentTool SPI + HITL session | 5-7j | M1 terminé |
| M3 | Mémoire C3 + HITL persisté | 5-7j | M2 terminé |
| M4 | Async + Dispatcher + STOP | 7-10j | M1 terminé (parallélisable avec M2-M3) |
| M5 | C4 + batch + error-retrieval | 8-12j | M3 + M4 terminés |
| M6 | Premier outil métier + litmus SRP | 8-12j | M5 terminé, PB-05 validé |
| M7 | Telegram HITL + notifications | 7-10j | M6 terminé, PB-01 tranché (SaaS ou local) |

**Total estimé** : 47-71 jours développeur selon les obstacles empiriques (modèle LLM, Spring AI gaps, etc.).

**Ce qui reste hors scope après M7 (prochaine phase — à planifier séparément)** :
- Facturation électronique Factur-X + intégration PDP (PB-03 doit être tranché d'abord)
- Gestion de stock
- Multi-tenant réel (Row-Level Security, Spring Security, onboarding multi-artisans)
- Web GUI avec visualisation mémoire
- Scoring de fraîcheur C4 (`reinforce()`)
- Spring Batch complet (si volume nocturne le justifie)
- Second spécialiste réel (domaine à définir selon le premier retour artisan)
