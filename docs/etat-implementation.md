# État d'implémentation — Marcel Maestro
**Date : 2026-06-27**
**Statut : E1 (8 étapes) ✅ + E2 (5 milestones) ✅ + E3-M0 ✅ + E3-M1 ✅ + E3-M2 ✅ — E3-M3 en cours**

---

## 1. Ce qui est construit

### E1 — Moteur agentique (8 étapes, toutes ✅)

Le noyau agentique complet est en place :

| Étape | Livrable clé | Statut |
|-------|-------------|--------|
| 1 — Fondations | Mono-repo Maven 3 modules (`mm-core` / `mm-spring-boot-starter` / `mm-app`) + CI | ✅ |
| 2 — Contrats | `AgentContext`, `AgentStatus`, `AgentResponse`, `TaskMessage`, ports LLM/Mémoire/HITL | ✅ |
| 3 — Boucle agentique | `AgentLoop` (JSON structuré, state machine, garde-fous, stop coopératif) | ✅ |
| 4 — HITL | `HitlGuard`, `ConsentDecision` (3 scopes × 3 persistances + ONCE + DENY), `ConsoleHumanInteraction` | ✅ |
| 5 — Mémoire factuelle | `JpaMemoryStore` (SQLite), `PersistentConsentCache` rechargé au démarrage | ✅ |
| 6 — Outils & VPS | `ToolRegistry`, `ToolExecutionGuard`, `PathValidator`, outils fichier/Maven/VPS | ✅ |
| 7 — Orchestrateur | `Dispatcher` (poll/dispatch/stop), `AgentFactory` SPI, `EchoSpecialist`, `InMemoryTaskQueue` | ✅ |
| 8 — Pilotage | `FileJournal` (JSONL), `TaskController` REST, `TelegramHumanInteraction` + commandes `/stop` `/status` | ✅ |

**Architecture mm-core (pur Java, 0 dépendance infra) :**
- `engine/` : `AgentLoop`, `AgentStateMachine`, `AgentResponseParser`, `LoopGuards`, `StopSignal`
- `hitl/` : `HitlGuard`, `ConsentDecision`, `HumanInteraction` (port)
- `tool/` : `AgentTool`, `ToolRegistry`, `ToolExecutionGuard`, `PathValidator`
- `orchestration/` : `Dispatcher`, `AgentFactory`, `DispatcherHandle`
- `prompt/` : `SystemPromptComposer`, `SystemPromptExtension`
- `memory/` : `MemoryStore`, `SemanticMemory` (port vide, couture pour le futur)

**Architecture mm-spring-boot-starter :**
- Implémentations JPA (`JpaMemoryStore`, `PersistentConsentCache`, `JpaWorkspaceRegistry`)
- Entités (`MemoryEntryEntity`, `ProjectEntity`, `ProjectWorkspaceEntity`, `ConversationEntity`)
- `DispatcherAutoConfiguration` (virtual threads), `MmChatMemoryAutoConfiguration` (JdbcChatMemory)
- `ConsoleHumanInteraction`, `CompositeHumanInteraction`, `CancellableHumanInteraction`

---

### E2 — Multi-projets & Conversations (5 milestones, tous ✅)

| Milestone | Livrable clé | Statut |
|-----------|-------------|--------|
| E2-M1 | Virtual threads (`spring.threads.virtual.enabled=true`), `ProjectEntity` + CRUD REST 10 endpoints | ✅ |
| E2-M2 | `ConversationEntity`, `JdbcChatMemory` (SQLite, Flyway V3), isolation mémoire par `conversationId` | ✅ |
| E2-M3 | `project_workspace` table, `JpaWorkspaceRegistry`, bypass HITL write sur dossiers déclarés | ✅ |
| E2-M4 | `PersistentConsentCache` : `ALLOW_PROJECT` scopé `"project:<id>"`, interdiction `null` projectId | ✅ |
| E2-M5 | Titre de conversation LLM async (`ConversationTitleService`), Telegram `/projects` `/switch` + préfixe `[NomProjet]` | ✅ |

**DB (SQLite, Flyway) — schéma actuel :**
```
V1 : memory_entry (MemoryStore + PersistentConsentCache)
V2 : project + project_workspace
V3 : conversation + SPRING_AI_CHAT_MEMORY (JdbcChatMemory)
```

**API REST opérationnelle :**
- `POST/GET /projects`, `/projects/{id}`, `/projects/{id}/archive`, `/projects/import`
- `POST/DELETE /projects/{id}/workspaces/{wsId}`
- `POST/GET /projects/{id}/conversations`, `/projects/{id}/conversations/{id}/messages`

---

## 2. E3-M0 livré

Les corrections préalables à E3 sont désormais en place :

- `TelegramSessionService` conserve le `conversationId` actif par `chatId`
- `chatMemory.clear()` est appelé à la suppression d'une conversation ou d'un projet
- `ProjectSystemPromptExtension` injecte le nom et le workspace du projet courant dans le system prompt via `SystemPromptComposer`

Ces points ferment les trous de continuité Telegram, de purge mémoire applicative et de contexte projet minimal.

---

## 3. Ce qui ne fonctionne pas encore (trous restants)

### Trou #1 — La conversation ne répond pas (le plus critique)

`POST /conversations/{id}/messages` **stocke** le message utilisateur dans `SPRING_AI_CHAT_MEMORY`
mais **n'appelle jamais le LLM**. Il n'y a pas de réponse. La mémoire ASSISTANT n'est jamais alimentée.

L'`AgentLoop` est un moteur task-only en JSON structuré — il n'est pas câblé à la conversation REST.
La route Telegram crée une tâche dans la `TaskQueue`, mais le résultat ne revient pas dans la conversation.

### Trou #2 — Pas de mode "conversation libre" vs "tâche"

Le système est 100 % task-oriented (JSON `AgentResponse` obligatoire). Il n'existe pas de chemin
pour une discussion libre où le LLM répond en texte naturel, puis peut décider de lancer une tâche.

### Trou #3 — Pas encore de délégation conversation → tâche

Le système E3 cible un mode hybride : réponse directe si Marcel peut répondre tout de suite,
ou soumission d'une tâche si l'action doit passer par l'`AgentLoop`. Ce branchement n'est pas
encore câblé.

### Trou #4 — Pas encore de streaming ni de traçabilité conversation ↔ tâches

Le endpoint REST est encore en mode batch vide et il n'existe ni SSE, ni lien persistant entre
une conversation et les tâches qu'elle a lancées.

---

## 4. Vision E3 — "Marcel parle vraiment"

L'objectif d'E3 : **fermer le trou #1 et #2** — donner à Marcel une vraie voix conversationnelle
tout en conservant sa capacité à basculer sur des tâches déterministes.

Principe directeur : **un seul point d'entrée, deux modes de sortie**.
L'utilisateur envoie un message → Marcel répond directement (conversation) OU délègue au Dispatcher
(tâche). C'est le LLM qui choisit, pas un classifier en amont.

```
POST /conversations/{id}/messages
         ↓
   ChatAgent (ChatClient + outils Spring AI)
         ↓
    ┌─── Réponse directe (texte libre) ──→ retour immédiat dans la conversation
    └─── submit_task (outil) ───────────→ Dispatcher → AgentLoop → notification
```

### Milestones E3

| # | Titre | Essentiel |
|---|-------|-----------|
| E3-M1 | ChatAgent — réponse LLM réelle dans `POST /conversations/{id}/messages` | ⭐ critique |
| E3-M2 | Délégation tâche depuis la conversation (outil `submit_task`) | ⭐ critique |
| E3-M3 | Contexte projet dans le system prompt (project.md, roadmap) | important |
| E3-M4 | Continuité de conversation Telegram + nettoyage cascade mémoire | important |
| E3-M5 | Streaming SSE + lien conversation ↔ tâches en DB | confort |

---

## 5. ADR E3

| ADR | Décision |
|-----|----------|
| ADR-026 | Architecture ChatAgent : mode hybride conversation/tâche via Spring AI tools (vs classifier externe) |
| ADR-027 | Réponse LLM : batch en E3-M1, streaming SSE en E3-M5 |
| ADR-028 | Persistance conversationId Telegram : in-memory (`TelegramSessionService`) suffit → oui/non ? |
| ADR-029 | Nettoyage `SPRING_AI_CHAT_MEMORY` : trigger applicatif vs migration SQL vs cron |
| ADR-030 | `submit_task` exposé comme tool Spring AI, pas comme `AgentTool` |
| ADR-031 | Lecture du contexte projet à chaque appel, sans cache en première version |

---

*Document rédigé le 2026-06-27 à l'issue de la lecture complète du code E1+E2.*
*Mise à jour le 2026-06-27 après livraison E3-M0 et préparation du prompt d'implémentation E3-M1.*
