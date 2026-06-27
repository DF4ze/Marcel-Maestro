# Évolution 3 — Marcel parle vraiment
**Statut : En cours d'implémentation — E3-M0 ✅ · E3-M1 🔄**
**Date : 2026-06-27**
**Prérequis : E2 complète — multi-projets, conversations persistées, JdbcChatMemory, HITL enrichi, Telegram E2-M5**

---

## 1. Vision

E3 donne à Marcel une **voix conversationnelle réelle**. Jusqu'ici, `POST /conversations/{id}/messages`
stocke le message utilisateur mais n'appelle jamais le LLM — il n'y a aucune réponse. L'`AgentLoop`
est un moteur task-only (JSON structuré obligatoire) : il ne sait pas "discuter".

L'objectif d'E3 : **un seul point d'entrée, deux modes de sortie naturels — c'est le LLM qui choisit.**

```
POST /conversations/{id}/messages
         ↓
   ChatAgent (ChatClient Spring AI + outils Spring AI)
         ↓
    ┌── Pas d'outil appelé  →  réponse texte libre (conversation)
    └── Outil submit_task   →  Dispatcher → AgentLoop → notification fin
```

Pas de classifier en amont. Pas de branche "mode conversation" / "mode tâche". Marcel décide
seul, guidé par son system prompt. Si la demande est une discussion, il répond. Si c'est une
action concrète, il utilise `submit_task` et informe l'utilisateur en temps réel.

**Ce que ça change fondamentalement :**
- La `JdbcChatMemory` est enfin vraiment utilisée (messages ASSISTANT persistés)
- Le `conversationId` devient le pivot d'une vraie session de travail continue
- L'`AgentLoop` reste intact — il exécute les tâches déléguées par le ChatAgent
- Telegram cesse de créer une nouvelle conversation par message

---

## 2. Décisions fondamentales

### 2.1 ChatAgent vs AgentLoop — deux rôles distincts

L'`AgentLoop` **n'est pas modifié**. Il reste le moteur task-only en JSON structuré :
déterministe, borné, HITL, outils fichier/Maven/VPS. Il exécute, ne discute pas.

Le `ChatAgent` est un **nouveau composant** dans `mm-app` (pas dans `mm-core`) :
- Utilise le `ChatClient` Spring AI en mode natif (`.call()` puis `.stream()`)
- Les outils sont des `@Bean` Spring AI annotés `@Tool` (function calling natif)
- Pas de `AgentResponseParser`, pas de JSON structuré : texte libre

Règle d'or : **le ChatAgent discute et délègue ; l'AgentLoop exécute.**

### 2.2 Streaming SSE — batch d'abord, SSE plus tard

E3-M1 implémente le ChatAgent en mode batch (`.call()`). C'est le minimum viable qui ferme
le trou critique : aujourd'hui l'API stocke le message mais ne répond pas.

Le streaming SSE est volontairement différé à E3-M5. La priorité immédiate est de rendre
le endpoint conversationnel utile, puis de stabiliser la sémantique conversation/tâche
avant d'ajouter la complexité du flux tokenisé.

### 2.3 submit_task — outil Spring AI, pas outil AgentLoop

`submit_task` est un `@Tool` Spring AI (function calling natif du ChatClient), **distinct**
des `AgentTool` de l'`AgentLoop`. Il n'a pas de `riskLevel` ni de `ToolExecutionGuard` :
le consentement à lancer une tâche est implicite (l'utilisateur l'a demandé dans la conversation).

La délégation est **asynchrone** : le ChatAgent répond immédiatement ("Je lance ça, je te
préviens à la fin"), la tâche tourne dans le Dispatcher/AgentLoop en arrière-plan.
La notification de fin passe par le canal habituel (`HumanInteraction.notify()` → Telegram).

### 2.4 Correction pré-E3 (E3-M0)

Trois dettes techniques d'E2 bloquent la cohérence d'E3 et sont corrigées en premier :

**a) Telegram conversationId** : `TelegramSessionService` maintient le `projectId` par `chatId`
mais pas le `conversationId`. Résultat : chaque message Telegram crée une nouvelle conversation.
Correction : étendre `TelegramSessionService` pour stocker aussi le `conversationId` actif.
Commande `/reset` ajoutée pour repartir sur une nouvelle conversation à la demande.

**b) Cascade SPRING_AI_CHAT_MEMORY** : suppression d'une conversation ou d'un projet ne nettoie
pas `SPRING_AI_CHAT_MEMORY` (pas de FK dans le schéma Spring AI). Correction : appel applicatif
`chatMemory.clear(conversationId)` dans `ConversationService` à la suppression, itération sur
toutes les conversations d'un projet lors d'un `DELETE /projects/{id}`.

**c) Contexte projet dans le system prompt** : Marcel ne sait pas sur quel projet il opère.
Correction : `ProjectSystemPromptExtension` implémente `SystemPromptExtension` et injecte
le nom et le workspace path du projet courant dans le system prompt.

### 2.5 Persistance des messages ASSISTANT

Actuellement seuls les messages `USER` sont persistés dans `SPRING_AI_CHAT_MEMORY`.
Dès E3-M1, chaque réponse du ChatAgent est persistée comme message `ASSISTANT`.
L'historique complet (question + réponse) survit aux redémarrages et est rechargé à la
reprise d'une conversation.

### 2.6 Lien conversation ↔ tâches (E3-M5)

Quand le ChatAgent délègue une tâche, le `taskId` est enregistré dans une table de jointure
`conversation_task`. Cela permet :
- De lister les tâches issues d'une conversation
- De retrouver quelle conversation a lancé quelle tâche (pour la notification de retour)
- D'associer le résultat final de la tâche à la mémoire de conversation

### 2.7 Contexte projet enrichi (E3-M3)

Au-delà du nom et du workspace (M0), le ChatAgent reçoit en contexte le contenu de
`project.md` et `roadmap.md` s'ils existent. Un outil `read_project_file` permet à Marcel
d'aller chercher d'autres fichiers projet à la demande.

La lecture est faite à chaque appel (pas de cache) : les fichiers évoluent fréquemment
pendant le développement. Un cache TTL court pourra être ajouté si la latence le justifie.

---

## 3. Schéma DB

### E3-M0 à M4 — aucun changement de schéma

Les corrections et le ChatAgent batch/SSE n'exigent pas de migration Flyway.

### E3-M5 — table conversation_task

Migration Flyway `V4__conversation_task.sql` :

```sql
CREATE TABLE conversation_task (
    id               TEXT PRIMARY KEY,       -- UUID
    conversation_id  TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    task_id          TEXT NOT NULL,          -- UUID du TaskMessage (non FK, pas en DB)
    submitted_at     TEXT NOT NULL,          -- ISO-8601
    status           TEXT NOT NULL DEFAULT 'RUNNING'  -- RUNNING | DONE | KO
);
```

`task_id` n'est pas une clé étrangère : les tâches vivent dans la `TaskQueue` in-memory,
pas en DB. Le lien est une référence faible — suffisante pour la traçabilité.

---

## 4. API REST

### Conversations (E3-M1 — changement de sémantique)

| Méthode | Endpoint | Avant E3 | Après E3 |
|---------|----------|----------|----------|
| `POST` | `/projects/{pId}/conversations/{id}/messages` | Stocke le message, 201 vide | Appelle le LLM, retourne la réponse Marcel |

**E3-M1 (batch) :** retourne `200 OK` avec body `{"role": "ASSISTANT", "content": "..."}`.

**E3-M5 (SSE) :** retourne `text/event-stream`, un événement par token :
```
data: {"delta": "Je"}
data: {"delta": " vais"}
data: {"delta": " analyser..."}
data: [DONE]
```

### Tâches liées à une conversation (E3-M5)

| Méthode | Endpoint | Action |
|---------|----------|--------|
| `GET` | `/projects/{pId}/conversations/{id}/tasks` | Liste les tâches soumises depuis cette conversation |

---

## 5. Impact sur les composants existants

### ConversationService (mm-app)
Nouvelle méthode `chat(conversationId, content)` → appelle le `ChatAgent`.
`addMessage()` existant reste (stockage pur, utilisé par les tests E2 et la migration).
Suppression : `chatMemory.clear(conversationId)` ajouté.

### ConversationController (mm-app)
`POST /{id}/messages` : délègue à `ConversationService.chat()` au lieu de `addMessage()`.
En E3-M1, retourne une réponse JSON synchrone. Le SSE est différé.

### TelegramSessionService (mm-app)
Champ ajouté : `Map<Long, String> activeConversationId` (en parallèle de `activeProjectId`).
Méthodes ajoutées : `getActiveConversationId(chatId)`, `setActiveConversationId(chatId, convId)`,
`resetConversation(chatId)`.
À la création d'une conversation depuis Telegram, le `conversationId` est stocké
et réutilisé pour tous les messages suivants du même `chatId`.

### TelegramMmController (mm-app)
Handler `@Chat` : réutilise le `conversationId` actif au lieu de créer une nouvelle conversation.
Commande `/reset` ajoutée : réinitialise le `conversationId` actif (nouvelle conversation).
Commande `/history` reste hors scope en M0/M1 et sera traitée plus tard si nécessaire.

### SystemPromptComposer / SystemPromptExtension (mm-core)
`ProjectSystemPromptExtension` (E3-M0) injecte le nom et workspace path du projet.
`ProjectContextExtension` (E3-M4) injecte le contenu de `project.md` et `roadmap.md`.
Pas de modification du contrat `SystemPromptExtension` — ajout d'implémentations.

### AgentContext
`projectId` et `conversationId` sont déjà présents et propagés. Aucun changement.
Le `ChatAgent` construit son `AgentContext` depuis le `conversationId` de la requête.

### ProjectService (mm-app)
`delete()` : iterate `conversationRepository.findAllByProjectId(id)` puis
`chatMemory.clear(convId)` pour chaque conversation avant suppression.

---

## 6. Nouveaux ADR

### ADR-026 — Architecture ChatAgent : Spring AI function calling natif
**Statut** : ✅ Acté

**Décision** : Le ChatAgent utilise les `@Tool` Spring AI (function calling natif) pour la
délégation de tâches et la lecture de fichiers. Pas de classifier externe pour détecter
l'intention (conversation vs tâche) — le LLM décide via le system prompt.

**Alternative écartée** : Classifier LLM en amont (appel LLM supplémentaire pour détecter
l'intention) ou branchement conditionnel dans le code (fragile sur les cas limites).

**Justification** : KISS + cohérence architecturale. Le function calling est exactement
conçu pour ce pattern. Le system prompt guide le LLM ; un tool disponible n'oblige pas
à l'utiliser.

---

### ADR-027 — Batch en M1, SSE en M5
**Statut** : ✅ Acté

**Décision** : E3-M1 implémente le ChatAgent en mode batch (`.call()`). E3-M5 ajoute
le streaming SSE (`.stream()`) sur le même endpoint. L'architecture M1 est conçue pour
faciliter ce passage ultérieur (même `ChatClient`, même `MessageChatMemoryAdvisor`).

**Justification** : KISS — batch suffit pour valider le comportement conversationnel.
Le SSE sera ajouté quand la base conversationnelle et la délégation de tâches seront stables.

---

### ADR-028 — conversationId Telegram : extension TelegramSessionService in-memory
**Statut** : ✅ Acté

**Décision** : `TelegramSessionService` maintient le `conversationId` actif par `chatId`
en mémoire (`ConcurrentHashMap`), au même titre que le `projectId`. Perdu au redémarrage :
la prochaine conversation crée un nouveau `conversationId` automatiquement.

**Alternative écartée** : Persister le `conversationId` actif en DB (table `telegram_session`).

**Justification** : KISS. Un redémarrage de Marcel est rare ; l'utilisateur peut `/reset`
à tout moment. La conversation précédente reste accessible en DB — seul le pointeur "actif"
est perdu.

---

### ADR-029 — Nettoyage SPRING_AI_CHAT_MEMORY : trigger applicatif
**Statut** : ✅ Acté

**Décision** : `chatMemory.clear(conversationId)` est appelé dans le code applicatif
(`ConversationService`, `ProjectService`) au moment de la suppression. Pas de FK SQL
(impossible sans modifier le schéma Spring AI), pas de cron.

**Alternative écartée** : Migration SQL ajoutant une FK vers `conversation` (couplage
fort au schéma interne Spring AI, fragile), ou job de nettoyage périodique (complexité inutile).

**Justification** : La suppression est toujours explicite (action utilisateur) — un trigger
applicatif est suffisant, simple et testable.

---

### ADR-030 — submit_task : @Tool Spring AI, pas AgentTool
**Statut** : ✅ Acté

**Décision** : `submit_task` est un `@Tool` Spring AI (function calling natif du `ChatClient`),
distinct des `AgentTool` de l'`AgentLoop`. Pas de `riskLevel`, pas de `ToolExecutionGuard`.

**Justification** : Le consentement à exécuter une tâche est implicite — l'utilisateur l'a
demandé dans la conversation. Appliquer le HITL à `submit_task` serait une double friction inutile.
Les `AgentTool` restent le bon pattern pour les outils à risque de l'`AgentLoop`.

---

### ADR-031 — Contexte projet : lecture fichier à chaque appel, pas de cache
**Statut** : ✅ Acté

**Décision** : `project.md` et `roadmap.md` sont lus depuis le filesystem à chaque appel
du ChatAgent. Pas de cache applicatif.

**Justification** : Ces fichiers évoluent fréquemment (Marcel les met à jour lui-même). Un cache
introduit un risque de décalage. La lecture fichier est négligeable devant la latence LLM (50-200ms
vs 1-5s). Un cache TTL court sera ajouté si des mesures montrent une dégradation notable.

---

## 7. Roadmap d'implémentation — Évolution 3

### E3-M0 — Passe de correction (prérequis E3) ✅ `done` (2026-06-27)
**Objectif** : Corriger les trois dettes techniques d'E2 avant de construire E3.

**Livrables** :
- `TelegramSessionService` : champ `activeConversationId` par `chatId` + méthodes `get/set/reset`
- `TelegramMmController` : handler `@Chat` réutilise le `conversationId` actif ; commande `/reset`
- `ConversationService.delete(conversationId)` : appelle `chatMemory.clear(conversationId)` + méthode publique exposée
- `ProjectService.delete(projectId)` : itère les conversations et nettoie la mémoire avant suppression DB
- `ProjectSystemPromptExtension` : implémente `SystemPromptExtension`, injecte nom + workspace path du projet courant
- Tests : continuité conversationId Telegram entre deux messages, cascade mémoire à la suppression, system prompt contient le nom projet

**Hors scope** : Réponse LLM dans les conversations, SSE, outils.

---

### E3-M1 — ChatAgent batch (réponse LLM réelle) 🔄 `en cours`
**Objectif** : `POST /conversations/{id}/messages` appelle vraiment le LLM et retourne une réponse.

**Livrables** :
- `ChatAgent` (bean Spring dans `mm-app`) : wrappeur autour du `ChatClient` avec `MessageChatMemoryAdvisor` ou équivalent Spring AI, mémoire JDBC par `conversationId`
- `ChatAgent` ne doit pas appeler `chatMemory.add()` manuellement : l'advisor persiste déjà USER et ASSISTANT
- `ConversationService.chat(conversationId, content)` : vérifie l'existence de la conversation, détecte le premier message avant appel LLM, délègue à `ChatAgent`, déclenche `ConversationTitleService.generateTitle()` uniquement au premier message
- `ConversationController` : `POST /{id}/messages` retourne `200 OK` + `{"role": "ASSISTANT", "content": "..."}`
- System prompt Marcel défini via propriété `mm.chat.system-prompt`, avec fallback code, composé avec `SystemPromptComposer`
- `MessageResponse` DTO enrichi : champ `role`
- Tests : réponse retournée, persistance ASSISTANT, historique rechargé, titre déclenché une seule fois, isolation entre conversations, non-régression sur `GET /messages`

**Hors scope** : SSE, outils, délégation de tâches.

---

### E3-M2 — Délégation de tâche depuis la conversation
**Objectif** : Marcel peut décider qu'une demande doit passer par le moteur task-only.

**Livrables** :
- outil `submit_task` exposé au `ChatClient`
- réponse conversationnelle immédiate quand une tâche est lancée
- première couture entre conversation et `Dispatcher`
- tests de délégation et d'isolation

**Hors scope** : SSE et suivi persistant conversation ↔ tâches.

---

### E3-M3 — Contexte projet enrichi dans le system prompt
**Objectif** : Marcel connaît l'état du projet avant même qu'on lui parle.

**Livrables** :
- `ProjectContextExtension` : lit `project.md` et `roadmap.md` si présents
- outillage minimal de lecture projet si nécessaire
- tests de présence/absence du contexte et respect des garde-fous de path

**Hors scope** : écriture de fichiers depuis la conversation.

---

### E3-M4 — Continuité Telegram et nettoyage mémoire
**Objectif** : fiabiliser le canal Telegram et la cohérence de la mémoire conversationnelle.

**Livrables** :
- validation manuelle et automatisée de la continuité `chatId` → `conversationId`
- sécurisation de la purge mémoire sur les suppressions projet/conversation
- durcissement des cas limites identifiés après M1/M2

**Hors scope** : SSE et table de jointure conversation ↔ tâches.

---

### E3-M5 — Streaming SSE + lien conversation ↔ tâches
**Objectif** : améliorer l'UX et la traçabilité une fois la conversation stable.

**Livrables** :
- streaming SSE sur `POST /messages`
- persistance du message assistant assemblé en fin de flux
- table `conversation_task` et endpoint de consultation
- polish Telegram et traçabilité

---

## 8. Points ouverts

- **Résultat de tâche → mémoire de conversation** : quand une tâche AgentLoop se termine, faut-il réinjecter son output dans la `JdbcChatMemory` de la conversation qui l'a lancée ? Ce serait la fermeture complète de la boucle conversation/tâche. Différé post-E3 (complexité du format AgentOutcome → texte naturel).

- **Nombre de tokens du contexte projet** : `project.md` peut devenir très long. Faut-il une stratégie de troncature intelligente (résumé LLM) ou une limite de caractères fixe ? Décision au moment de E3-M4 selon les retours terrain.

- **Telegram SSE — édition de message** : l'API Telegram ne supporte pas le streaming natif. L'édition de message (M5) est une approximation. Si le délai entre le premier et le dernier token dépasse ~30s, Telegram peut throttler les éditions. À surveiller.

- **System prompt Marcel** : le prompt de base M1 doit rester concis, direct, en français, et explicite sur le fait que les actions concrètes passeront plus tard par le système de tâches.

- **Tests d'intégration E3-M1/M2** : les tests existants (E2) utilisent `addMessage()` en mode stockage pur. La migration vers `chat()` (avec vrai appel LLM) nécessite un `ChatClient` mockable ou un profil de test avec `ScriptedChatModel` (déjà utilisé dans `mm-core`).

---

*Document rédigé le 2026-06-27 à l'issue de la session de conception E3.*
*Prochaine étape : implémentation E3-M0 — passe de correction.*
