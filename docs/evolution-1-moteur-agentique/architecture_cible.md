# Architecture cible — Marcel Maestro (Java / Spring)
**Statut : Document de travail**
**Réconciliation du document Python avec la direction Java/Spring**
**Date : 2026-06-19**

---

## 0. Divergences Python → Java (inventaire)

Avant tout autre chose : les contradictions entre `architecture_generale.md` et les décisions actées, pour ne laisser aucune ambiguïté.

| # | Section d'origine | Ce que dit le doc Python | Ce qu'on fait en Java | Gravité |
|---|---|---|---|---|
| D1 | §2.3 | Spécialistes se parlent directement (boucle Java↔CI/CD) | File de tâches typées obligatoire, zéro dialogue LLM↔LLM | ⚠️ Changement de paradigme — la boucle est plus lente mais traçable |
| D2 | §7.3 | Pré-qualificateur petit modèle bypasse le Cortex | Reporté. Classifieur d'intent léger possible, Cortex reste SSOT | ✅ Cohérent avec SSOT — à ne pas introduire trop tôt |
| D3 | §10 | LangGraph pour l'orchestration | Machine à états custom (enum + switch) | ⚠️ Perd les hooks natifs de LangGraph (checkpoint, replay, hooks erreur tool) |
| D4 | §10 | ChromaDB ou Qdrant | pgvector (Spring AI VectorStore) sur PostgreSQL | ✅ Moins de dépendances, SSOT données |
| D5 | §7.4 | "LangGraph dispose de hooks natifs sur erreurs de tools" | Capture manuelle dans la boucle de traitement | ⚠️ À implémenter explicitement — facile à oublier |
| D6 | §10 | asyncio Python | Spring @Async + CompletableFuture + BlockingQueue | ⚠️ BlockingQueue non-durable — tâches perdues si crash process |
| D7 | §9.2 | Un bot Telegram par agent | Un seul bot, port HumanInteraction | ✅ KISS |
| D8 | §12 | Roadmap Python (LangGraph, Ollama, asyncio) | À réécrire entièrement en Java/Spring | Toute la roadmap d'implémentation est remplacée par le walking skeleton |

**D1 est la divergence la plus significative.** Le document Python conçoit des boucles opérationnelles directes entre spécialistes (ex : l'agent Java détecte une erreur de build, contacte directement l'agent CI/CD, récupère la réponse). En Java, chaque échange passe par la file : chaque résultat est un `TaskMessage`. La notion de "spécialiste autonome dans sa boucle" devient "spécialiste qui crée des sous-tâches jusqu'à résolution". Plus traçable, légèrement plus verbeux.

---

## 1. Modules Maven

### Structure mono-repo

```
marcel-maestro/                           (parent POM — packaging pom)
│
├── mm-core/
│   Dépendances : spring-ai-core, spring-context
│   ⛔ NE dépend PAS DE : spring-data, spring-batch, spring-web, mm-app
│
├── mm-spring-boot-starter/
│   Dépendances : mm-core, spring-boot-autoconfigure, spring-data-jpa,
│                 spring-ai-pgvector-store-spring-boot-starter
│   Fournit : implémentations par défaut de tous les ports de mm-core
│
├── mm-batch/
│   Dépendances : mm-core, mm-spring-boot-starter, spring-batch
│   ⛔ NE dépend PAS DE : mm-app (ni aucun module métier)
│
└── mm-app/                   (premier et seul consommateur métier)
    Dépendances : mm-spring-boot-starter, mm-batch
    Contient : outils métier artisan (QuoteTool, etc.), TelegramHumanInteraction
```

### Règle de validation des frontières (litmus test SRP)

Si `mm-core/pom.xml` contient une dépendance vers `mm-app` ou un module métier, la frontière est violée. Vérification automatisable dans la CI :

```bash
# Doit retourner 0 lignes
mvn -pl mm-core dependency:tree | grep -i "mm-app\|facture\|devis\|stock\|artisan"

# Doit passer sans erreur
mvn -pl mm-core test
```

---

## 2. Interfaces clés (`mm-core`)

### 2.1 SPI AgentTool

```java
public interface AgentTool {
    String name();           // Identifiant unique, snake_case
    String description();    // Lu par le LLM pour décider d'appeler l'outil
    JsonNode inputSchema();  // JSON Schema des paramètres — utilisé par Spring AI
    RiskLevel riskLevel();   // Pilote le HITL
    ToolResult execute(Map<String, Object> params, AgentContext ctx) throws ToolException;
}

public enum RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

public record ToolResult(boolean success, Object data, String error) {}
```

**Tension DRY à surveiller** : Spring AI expose déjà les outils au LLM via `FunctionCallback` et son propre système de schema JSON. Notre `AgentTool` est un wrapper dont la seule valeur ajoutée est `riskLevel()`. Si ce besoin disparaît, le wrapper disparaît. L'implémentation recommandée : `AgentTool` est adapté vers `FunctionCallback` par un converter interne du moteur. L'hôte n'implémente qu'`AgentTool`, le moteur traduit vers Spring AI — sans réenrober le schema JSON ni la sérialisation.

### 2.2 Port HumanInteraction

```java
public interface HumanInteraction {
    // L'hôte choisit le canal : console, Telegram, web...
    ConsentDecision ask(HitlRequest request);
    void notify(AgentNotification notification); // Fin de tâche, KO, blocage
}

public record HitlRequest(String question, RiskLevel riskLevel, AgentContext ctx) {}

public enum ConsentDecision {
    ALLOW_ONCE, ALLOW_SESSION, ALLOW_PROJECT, ALLOW_ALWAYS, DENY
}
```

### 2.3 Port MemoryStore (C3 — mémoire factuelle)

```java
public interface MemoryStore {
    void put(MemoryEntry entry);
    Optional<MemoryEntry> get(String key, AgentContext ctx);
    List<MemoryEntry> findByScope(String scope, AgentContext ctx);
    void delete(String key, AgentContext ctx);
}

public record MemoryEntry(
    String key,
    String value,
    String scope,     // "global" | "project:<id>" | "session:<id>" | "tenant:<id>"
    String tenant,    // artisan ID — présent dès J1, toujours "default" en MVP
    Instant createdAt,
    Instant updatedAt
) {}
```

Note : C6 (mémoire globale) est fusionnée dans C3 via `scope = "global"`. Pas de table ni d'interface séparée.

### 2.4 Port SemanticMemory (C4 — mémoire procédurale)

```java
public interface SemanticMemory {
    void store(SemanticEntry entry);
    List<SemanticEntry> search(String query, int topK, AgentContext ctx);
    void reinforce(String entryId); // Score de fraîcheur — reporté après MVP
}
```

### 2.5 AgentStatus et format de sortie LLM

```java
public enum AgentStatus {
    PENDING,  // Planifié, pas encore démarré
    RUNNING,  // En cours, nouvelle itération de boucle
    DONE,     // Terminé avec succès
    BLOCKED,  // Attend validation externe ou ressource
    TROUBLE,  // Difficultés — déclenche error-triggered retrieval
    KO        // Échec définitif
}
```

Format de sortie JSON imposé à tout agent via le system prompt :

```json
{
  "status": "running|done|blocked|trouble|KO",
  "reason": "Description courte de la situation",
  "output": "Résultat produit si status=done (sinon null)",
  "tool_calls": [{"tool": "nom_outil", "params": {}}],
  "sub_tasks": [{"assignee": "specialist_id", "description": "..."}]
}
```

Le parsing est **déterministe** : `ObjectMapper.readValue()`. Si le parsing échoue → traitement automatique comme `status: TROUBLE` avec une retry sur prompt renforcé ("Respond ONLY with valid JSON, no other text"). Zéro analyse NLP de la sortie.

### 2.6 AgentContext

```java
public record AgentContext(
    String tenant,          // "default" en MVP, artisan ID en multi-tenant
    String projectId,       // Identifiant du projet artisan
    String conversationId,  // UUID de la conversation courante
    String taskId           // UUID de la tâche en cours (pour idempotence)
) {}
```

---

## 3. Flux d'une requête de bout en bout

```
[Utilisateur]
    │  Message texte (Telegram ou console)
    ▼
[HumanInteraction impl]  ←  Port — l'hôte implémente
    │  Convertit en TaskMessage(type=USER_REQUEST, content, ctx)
    ▼
[Dispatcher]             ←  Non-LLM, composant permanent (@PostConstruct)
    │  Soumet à TaskQueue (BlockingQueue<TaskMessage>)
    │  Instancie CortexAgent et le lance via ThreadPoolTaskExecutor (@Async)
    ▼
[CortexAgent]              ←  Composant du moteur, engine/
    │
    ├─ 1. MemoryStore.findByScope("project:<id>") → règles HITL actives
    ├─ 2. ProjectState.load(projectId) → résumé project.md + roadmap_result.md
    ├─ 3. ChatClient.call(systemPrompt, messages, tools=registeredTools)
    │       ↓ Spring AI → provider LLM (Ollama ou cloud)
    ├─ 4. Parse JSON → AgentResponse → StateMachine.route(status)
    │
    ├─ [status=DONE]
    │       └─ Rapport + ProjectState.update() + HumanInteraction.notify(DONE)
    │
    ├─ [status=RUNNING]  → loop (vérifier stopped flag → si ok, itération suivante)
    │
    ├─ [status=BLOCKED]
    │       └─ HumanInteraction.ask() → attend réponse → reprend si ALLOW, KO si DENY
    │
    ├─ [status=TROUBLE]
    │       ├─ SemanticMemory.search(reason, topK=3)  ← error-triggered retrieval
    │       ├─ Réinjecte les solutions passées dans le contexte LLM
    │       └─ Loop avec compteur indépendant (max 3 retries sur trouble)
    │
    ├─ [status=KO]       → Log + HumanInteraction.notify(KO) → FIN
    │
    └─ [tool_call dans la réponse]
            ├─ HitlGuard.check(tool.riskLevel(), consentCache)
            │       Si HITL requis → HumanInteraction.ask()
            │       Si ALLOW_PROJECT/ALLOW_ALWAYS → MemoryStore.put(règle HITL)
            ├─ AgentTool.execute(params, ctx)  ← appel synchrone, timeout géré
            └─ ToolResult injecté comme function result dans le message suivant

[Si sub_tasks dans la réponse du Cortex]
    │
    ▼
[Dispatcher]
    │  TaskMessage(type=SPECIALIST_REQUEST, assignee="java-spring", ...)
    │  Instancie SpecialistAgent(@Async)
    ▼
[SpecialistAgent]        ←  Même boucle que CortexAgent, system prompt de spécialiste
    └─ Rapport final → TaskMessage(type=SPECIALIST_REPORT) → TaskQueue
    └─ CortexAgent consomme ce rapport → intègre dans sa prochaine itération

[En fin de toute tâche]
    ├─ Append dans journal JSONL (C2) — append-only, jamais modifié
    ├─ roadmap_result.md mis à jour si étape clôturée (status=DONE ou KO)
    └─ HumanInteraction.notify(résultat)
```

---

## 4. Composant Dispatcher

Le Dispatcher est le seul composant permanent. Il **n'est pas** un LLM.

```java
@Component
public class Dispatcher {

    private final TaskQueue taskQueue;
    private final Map<String, AgentFactory> agentFactories; // registre des agents
    private final ThreadPoolTaskExecutor executor;
    private final Map<String, AgentHandle> activeHandles = new ConcurrentHashMap<>();

    @PostConstruct
    public void start() {
        executor.submit(this::pollLoop);
    }

    private void pollLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            TaskMessage task = taskQueue.poll(5, TimeUnit.SECONDS);
            if (task == null) continue;
            AgentFactory factory = agentFactories.get(task.assignee());
            AgentHandle handle = factory.create(task.ctx());
            activeHandles.put(task.taskId(), handle);
            executor.submit(() -> {
                try { handle.process(task); }
                finally { activeHandles.remove(task.taskId()); }
            });
        }
    }

    public void stop(String taskId) {
        AgentHandle handle = activeHandles.get(taskId);
        if (handle != null) handle.interrupt(); // positionne AtomicBoolean stopped
    }
}
```

**Gestion du STOP** : `interrupt()` positionne un flag `AtomicBoolean stopped`. La boucle de l'agent vérifie ce flag uniquement entre deux opérations atomiques (avant l'appel LLM, après la réception du résultat tool — jamais en milieu d'écriture fichier ni en milieu de transaction JPA). L'agent émet `status: KO, reason: "stopped by user"` puis s'arrête proprement.

**Pool borné** : configurer `ThreadPoolTaskExecutor` avec `maxPoolSize` explicite (ex : 10 threads). Sans borne, N artisans simultanés = N × M agents = threads illimités.

---

## 5. Architecture mémoire réconciliée

| Couche | Rôle | Implémentation Java | Notes |
|--------|------|---------------------|-------|
| C1 — Travail | Context window LLM actif | Spring AI ChatClient (géré automatiquement) | Éphémère, pas de port |
| C2 — Journal | Append-only JSONL par agent/jour | `FileJournal` : writer avec verrou fichier | Jamais lu par le LLM en prod |
| C3 — Factuel | Faits courts, HITL persisté | `JpaMemoryStore` → SQLite → Postgres | Scope comme attribut (C6 fusionnée) |
| C4 — Procédural | Patterns, solutions d'erreurs | `PgVectorSemanticMemory` → pgvector | Retrieval sur trouble uniquement |
| C5 — Projet | project.md, roadmap_result.md | `FileProjectState` (lecture fichiers .md) | Injecté au démarrage de conversation |

**C6 est fusionnée dans C3** via `scope = "global"`. Une seule table, une seule interface.

**Une seule instance PostgreSQL** pour C3 (tables JPA) et C4 (pgvector). Pas de ChromaDB, Qdrant, ni base vectorielle séparée.

**SQLite pour C3 en M0-M4** (avant activation de pgvector). Migration vers PostgreSQL triviale avec Spring Data JPA.

### Batch de consolidation nocturne

```
@Scheduled(cron = "0 2 * * *")   // MVP : @Scheduled simple
                                   // → Spring Batch quand volume > ~1000 entrées/nuit

Étapes :
1. Lire journaux JSONL du jour, par agent (C2)
2. Chunking par session/tâche (pas par taille arbitraire)
3. Appel LLM (modèle moyen, ex: Llama 8B) — extraire faits nouveaux + procédures
4. Rapprochement C3 : MemoryStore.put() si fait nouveau, update si connu
5. Rapprochement C4 : SemanticMemory.store() si nouveau, reinforce() si connu
6. Archivage gz + suppression journal du jour
```

---

## 6. Arborescence fichiers projet (adaptée)

```
/projet-artisan-alpha/              → géré par mm-app
├── project.md                      → contexte, règles métier artisan
├── roadmap.md                      → plan d'actions (éditable humain + agent HITL)
├── roadmap_result.md               → état machine — écrit par les agents UNIQUEMENT
└── rapports/                       → rapports de fin de tâche

/agents/                            → géré par le moteur
├── cortex/
│   └── logs/2026-06-19.jsonl       → journal quotidien (C2)
└── java-spring/
    ├── logs/
    └── rapports/
```

---

## 7. Mapping composants Spring

| Besoin | Composant / Librairie | Notes |
|--------|-----------------------|-------|
| Appel LLM | `spring-ai-openai-starter` OU `spring-ai-ollama-starter` | Interchangeable via config uniquement |
| Mémoire vectorielle | `spring-ai-pgvector-store-starter` | Requiert PostgreSQL + extension pgvector |
| Mémoire relationnelle (MVP) | `spring-data-jpa` + `sqlite-jdbc` | `org.xerial:sqlite-jdbc:3.x` |
| Async / Dispatcher | `@EnableAsync`, `ThreadPoolTaskExecutor` | Pool borné, configurable |
| Batch consolidation | `@Scheduled` (MVP) → `spring-batch` (quand nécessaire) | Ne pas anticiper Spring Batch |
| Telegram | `org.telegram:telegrambots:6.x` ou REST direct | REST suffit pour les notifications simples |
| UI initiale | API REST uniquement (`spring-web`) | Thymeleaf ou web GUI reporté |
| Tests | `spring-boot-starter-test`, `Testcontainers` (pgvector) | `MockChatClient` pour tests sans LLM |
| Migrations schema | Flyway | Un seul schema à gérer (C3 + C4 sur même Postgres) |
