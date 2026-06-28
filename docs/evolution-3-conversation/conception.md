# Évolution 3 — Marcel parle vraiment
**Statut : En cours d'implémentation — E3-M0 ✅ · E3-M1 ✅ · E3-M2 ✅ · E3-M3 ✅ · E3-M4 ✅ · E3-M5 🔄 · E3-M6 📋**
**Date : 2026-06-28**
**Prérequis : E2 complète — multi-projets, conversations persistées, JdbcChatMemory, HITL enrichi, Telegram E2-M5**

---

## 1. Vision

E3 donne à Marcel une vraie voix conversationnelle. Jusqu'ici, `POST /conversations/{id}/messages`
stockait le message utilisateur mais n'appelait jamais le LLM. L'utilisateur écrivait, la mémoire
persistait, mais Marcel ne répondait pas.

L'objectif d'E3 est simple : un seul point d'entrée conversationnel, et c'est le LLM qui décide
s'il doit répondre naturellement ou déléguer une tâche au moteur agentique.

```text
POST /conversations/{id}/messages
         ↓
   ChatAgent (ChatClient Spring AI + tools Spring AI)
         ↓
    ┌── Pas d'outil appelé  →  réponse texte libre
    └── submit_task         →  Dispatcher → AgentLoop → notification de fin
```

Il n'y a pas de classifier en amont. Marcel choisit via son system prompt et les outils exposés.

> ⚠️ **Superseded le 2026-06-28 (consolidation E4).** Ce schéma a évolué : `submit_task`
> ne route plus vers `cortex` mais passe par un **qualificateur déterministe** (`TaskQualifier`,
> règles + repli LLM) qui résout l'agent cible et route **directement** vers le spécialiste
> Claude/Codex. Voir ADR-034 à ADR-037 plus bas et `docs/analyse-chaine-telegram-cortex-agents.md`.
> Flux actuel :
> ```text
> POST /conversations/{id}/messages
>          ↓
>    ChatAgent (ChatClient Spring AI + tools)
>          ↓
>     ┌── Pas d'outil appelé  →  réponse texte libre
>     └── submit_task         →  TaskQualifier (catégorie + agent) → Dispatcher
>                                  → spécialiste Claude/Codex → notification + fermeture de boucle
> ```

Ce que cela change :
- `JdbcChatMemory` devient une vraie mémoire de travail continue.
- `conversationId` devient l'ancre d'une session durable.
- `AgentLoop` reste inchangé et exécute uniquement les tâches déléguées.
- Telegram cesse de créer une nouvelle conversation à chaque message.

---

## 2. Décisions fondamentales

### 2.1 ChatAgent vs AgentLoop

L'`AgentLoop` n'est pas modifié. Il reste un moteur task-only à sortie structurée :
déterministe, borné, outillé, soumis au HITL.

Le `ChatAgent` est un composant applicatif de `mm-app` :
- il utilise `ChatClient` Spring AI ;
- il fonctionne en texte libre ;
- il expose des `@Tool` Spring AI pour la délégation et la lecture projet ;
- il compose son prompt via `SystemPromptComposer`.

Règle d'or : le ChatAgent discute et délègue ; l'AgentLoop exécute.

### 2.2 Batch d'abord, streaming ensuite

Le premier incrément utile est le mode batch (`.call()`). Il ferme le trou principal :
le endpoint conversationnel répond enfin.

Le SSE a ensuite été ajouté en Spring MVC pur via `SseEmitter`, sans WebFlux.
L'ordre retenu était volontaire :
1. rendre la conversation utile ;
2. stabiliser la sémantique conversation/tâche ;
3. ajouter ensuite le flux tokenisé.

### 2.3 `submit_task` comme outil Spring AI

`submit_task` est un `@Tool` Spring AI exposé au `ChatAgent`, distinct des `AgentTool`
de l'`AgentLoop`.

Conséquences :
- pas de `ToolExecutionGuard` spécifique sur `submit_task` ;
- pas de `riskLevel` dédié ;
- consentement implicite : si l'utilisateur demande l'action dans la conversation,
  Marcel peut lancer la tâche.

La délégation reste asynchrone : le ChatAgent répond tout de suite, la tâche part
dans le `Dispatcher`, puis la fin est notifiée par le canal habituel.

### 2.4 Corrections pré-E3 (E3-M0)

Trois dettes techniques bloquaient la cohérence d'E3 :

**a) Continuité Telegram**
- `TelegramSessionService` stocke désormais aussi le `conversationId` actif par `chatId`.
- `/reset` permet de repartir proprement sur une nouvelle conversation.

**b) Purge mémoire Spring AI**
- `ConversationService.delete()` appelle `chatMemory.clear(conversationId)`.
- `ProjectService.delete()` purge la mémoire de toutes les conversations du projet avant suppression.

**c) Contexte projet minimal dans le prompt**
- `ProjectSystemPromptExtension` injecte le nom du projet et son workspace principal.

### 2.5 Persistance des messages ASSISTANT

Avec le `ChatAgent`, l'historique complet de conversation vit dans `JdbcChatMemory`.
Le couple question/réponse persiste donc sur la durée, sans logique applicative manuelle
de duplication côté `ConversationService`.

### 2.6 Gestionnaire de conversations (E3-M5)

Le projet supporte maintenant plusieurs conversations par projet, avec navigation et cycle de vie.

Côté REST :
- liste enrichie ;
- renommage ;
- archivage ;
- filtrage par statut.

Côté Telegram :
- continuité par `chatId` ;
- réutilisation de la conversation active ;
- commandes de réinitialisation ou de bascule.

Compléments effectivement apportés au-delà du besoin initial :
- le handler Telegram de message libre est maintenant branché sur `ConversationService.chat()`
  et non plus sur l'ancien chemin tâche-only ;
- tout message commençant par `/` est intercepté comme commande système et n'est jamais envoyé
  au LLM, ni au flux HITL ;
- une conversation archivée devient strictement en lecture seule, sans mécanisme de désarchivage ;
- `/conversations` expose un menu à deux niveaux : projet, puis conversations ouvertes avec titre ;
- `/delete project|conv` et `/archive project|conv` ont été ajoutés côté Telegram avec boutons,
  annulation, confirmation explicite pour la suppression, et saisie d'une raison pour l'archivage.
- la navigation Telegram a ensuite été unifiée : `/projects`, `/conversations`, `/switch`,
  `/archive` et `/delete` passent désormais par un même parcours projet -> conversation -> action ;
- les projets sont triés par activité récente dans ces vues ;
- le projet système `Autre` y est visible mais protégé, avec archivage/suppression masqués.
- un `switch` de projet sans conversation explicite ne crée pas de conversation immédiatement :
  la conversation Telegram est créée paresseusement au premier message libre, pour éviter les
  conversations fantômes sans contenu.

### 2.7 Brief de conversation

Un service dédié `ConversationBriefService` produit maintenant un brief ponctuel d'une conversation :
- en REST via `GET /projects/{pId}/conversations/{id}/brief` ;
- en Telegram via `/brief` sur la conversation active.

Le brief :
- relit l'historique courant depuis `ChatMemory` ;
- reconstruit un transcript borné ;
- appelle le LLM avec un prompt dédié de synthèse ;
- ne persiste pas le résumé dans la mémoire conversationnelle.

### 2.8 Lien conversation ↔ tâches (E3-M6)

Le lien persistant conversation ↔ tâches est maintenant implémenté via `conversation_task`
pour tracer :
- quelle conversation a lancé quelle tâche ;
- quelles tâches sont liées à une conversation ;
- quel retour doit être réinjecté ou notifié.

### 2.9 Contexte projet enrichi (E3-M3)

Le `ChatAgent` reçoit en contexte le contenu de `PROJECT.md` et `ROADMAP.md` quand ils existent.
Un outil `read_project_file` lui permet de lire d'autres fichiers du projet à la demande.

La lecture se fait à chaque appel, sans cache. Ces fichiers évoluent souvent et doivent rester
alignés avec l'état réel du projet.

Le nommage cible est maintenant :
- `PROJECT.md`
- `ROADMAP.md`

Une compatibilité descendante est conservée en lecture pour les variantes legacy en minuscules
(`project.md`, `roadmap.md`) sur les projets plus anciens.

### 2.10 Amorçage mécanique du cadrage projet

Après les premiers tests manuels post-E3-M3, le comportement cible a été clarifié :
la première conversation d'un projet neuf n'est pas une conversation libre. C'est une
discussion dédiée à la construction de `PROJECT.md`.

Comportement retenu :
- la création d'un projet crée automatiquement `PROJECT.md` et `ROADMAP.md` dans le workspace
  interne du projet ;
- ces deux fichiers contiennent un texte d'amorçage qui guide Marcel ;
- la première conversation reçoit le titre `Cadrage initial du projet` ;
- son `conversationId` est stocké dans la config du projet comme conversation de bootstrap ;
- tant que cette conversation est active, chaque message utilisateur est ajouté mécaniquement
  dans `PROJECT.md` avant l'appel LLM ;
- le prompt précise explicitement à l'utilisateur qu'il peut ouvrir d'autres discussions s'il
  veut arrêter l'interview, puis revenir plus tard sur cette discussion pour continuer à mieux
  définir le projet.

La roadmap ne suit pas ce mode automatiquement. Le premier message de cadrage doit préciser
que la roadmap sera traitée plus tard, uniquement si l'utilisateur le demande, dans une
discussion dédiée à sa construction.

### 2.11 Résolution des fichiers projet avec plusieurs dossiers rattachés

Un projet peut posséder plusieurs dossiers rattachés.

La règle retenue en lecture est :
1. chercher d'abord dans le workspace interne du projet ;
2. si le fichier n'y existe pas, chercher dans les workspaces rattachés ;
3. refuser toute sortie des racines autorisées via `PathValidator` et `WorkspaceRegistry`.

Cette logique vaut pour :
- `read_project_file` ;
- `read_file` quand il lit un fichier projet.

Elle ne vaut pas pour l'écriture des fichiers de contexte. `PROJECT.md` et `ROADMAP.md`
doivent toujours être lus/écrits dans le workspace interne du projet.

---

## 3. Schéma DB

### E3-M0 à E3-M5

Aucune migration spécifique n'a été nécessaire pour :
- le ChatAgent batch ;
- le contexte projet enrichi ;
- le bootstrap de `PROJECT.md` ;
- la redirection/fallback de lecture projet ;
- la gestion applicative des conversations.

### E3-M6 — activité conversation + table `conversation_task`

Migrations désormais en place :

```sql
ALTER TABLE conversation ADD COLUMN message_count INTEGER NOT NULL DEFAULT 0;
ALTER TABLE conversation ADD COLUMN last_message_at TEXT;

CREATE TABLE conversation_task (
    id               TEXT PRIMARY KEY,
    conversation_id  TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE,
    task_id          TEXT NOT NULL,
    submitted_at     TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'RUNNING'
);
```

`task_id` reste une référence faible, pas une FK SQL, car la tâche vit dans la `TaskQueue`.

---

## 4. API REST

### Conversations

| Méthode | Endpoint | Comportement |
|---------|----------|--------------|
| `POST` | `/projects/{pId}/conversations/{id}/messages` | appelle le LLM et retourne la réponse assistant |
| `POST` | `/projects/{pId}/conversations/{id}/messages` + `Accept: text/event-stream` | diffuse les tokens en SSE puis `[DONE]` |
| `GET` | `/projects/{pId}/conversations` | liste les conversations du projet |
| `PATCH` | `/projects/{pId}/conversations/{id}` | renomme une conversation |
| `POST` | `/projects/{pId}/conversations/{id}/archive` | archive une conversation |
| `GET` | `/projects/{pId}/conversations/{id}/tasks` | liste les tâches liées à la conversation |
| `GET` | `/projects/{pId}/conversations/{id}/brief` | retourne un brief synthétique de la conversation |

Réponse actuelle de `POST /messages` :

```json
{
  "role": "ASSISTANT",
  "content": "..."
}
```

Le SSE est maintenant actif en Spring MVC pur, sans WebFlux.

---

## 5. Impact sur les composants existants

### `ConversationService` (mm-app)

Le service gère maintenant deux usages :
- `addMessage()` pour le stockage brut historique ;
- `chat()` pour le vrai flux conversationnel avec LLM.

Compléments effectivement livrés :
- la première conversation d'un projet reçoit le titre `Cadrage initial du projet` ;
- `startConversation()` enregistre la conversation de bootstrap dans la config projet ;
- `chat()` ajoute le message utilisateur dans `PROJECT.md` avant appel LLM quand il s'agit
  de la conversation de cadrage initial ;
- `chat()` et `addMessage()` refusent désormais tout ajout sur une conversation archivée ;
- `archive()` accepte une raison optionnelle pour tracer l'archivage demandé depuis Telegram ;
- le service continue à maintenir `messageCount` et `lastMessageAt`.

### `ConversationController` (mm-app)

`POST /{id}/messages` délègue à `ConversationService.chat()` et renvoie directement la réponse
assistant. On n'est plus sur un endpoint de stockage passif.

Compléments maintenant livrés :
- routage de contenu selon `Accept` entre batch JSON et SSE ;
- endpoint `GET /{id}/tasks` pour exposer `conversation_task` ;
- endpoint `GET /{id}/brief` pour produire une synthèse ponctuelle.

### `ConversationBriefService` (mm-app)

Nouveau service applicatif dédié au résumé :
- relit la conversation persistée ;
- construit un transcript borné ;
- appelle le LLM avec un prompt dédié de brief ;
- retourne un texte de synthèse sans l'ajouter à l'historique.

### `TelegramSessionService` et `TelegramMmController` (mm-app)

Le `conversationId` actif est conservé par `chatId`.

Résultats :
- les messages successifs Telegram restent dans la même conversation ;
- la conversation n'est créée qu'au premier message libre après un switch de projet si aucune
  conversation n'a été explicitement reprise ;
- `/reset` repart sur une nouvelle conversation ;
- `/brief` résume la conversation active ;
- les bugs de recréation systématique de conversation sont supprimés ;
- les messages libres Telegram passent par le même flux conversationnel que le REST ;
- les commandes slash sont court-circuitées avant tout envoi au LLM ;
- `/conversations` propose une navigation projet -> conversations ouvertes ;
- `/delete project|conv` et `/archive project|conv` s'appuient sur des boutons Telegram,
  avec annulation, confirmation de suppression et collecte d'un motif d'archivage ;
- l'état de session conserve aussi les suggestions affichées et les actions en attente
  (suppression à confirmer, archivage avec raison à saisir) ;
- la navigation Telegram conserve aussi une intention de parcours unifiée
  (`browse`, `switch`, `archive`, `delete`) pour réutiliser les mêmes écrans ;
- les listes projet sont triées par activité récente à partir des conversations ;
- le projet système `Autre` reste sélectionnable mais ses actions interdites ne sont pas exposées.

### `ProjectService` (mm-app)

`create()` initialise désormais automatiquement :
- `PROJECT.md`
- `ROADMAP.md`

Ces fichiers sont créés dans le workspace interne du projet, jamais à la racine globale
du workspace applicatif.

`delete()` purge toujours la mémoire des conversations avant suppression.

Compléments effectivement livrés :
- `delete()` reste le point unique de suppression complète d'un projet : purge mémoire Spring AI,
  suppression des données et suppression du dossier physique du workspace projet ;
- `archive()` accepte une raison optionnelle et cascade l'archivage aux conversations encore
  ouvertes du projet ;
- les messages de confirmation Telegram rappellent explicitement le nom du projet lorsqu'une
  conversation est archivée ou supprimée ;
- le service garantit aussi la présence du projet système `Autre`, son `PROJECT.md` dédié,
  et bloque archivage, désarchivage, suppression et renommage sur ce projet protégé.

### `ProjectBootstrapService` (mm-app)

Nouveau service applicatif dédié au cadrage initial :
- enregistre le `bootstrapConversationId` dans la config du projet ;
- détermine si une conversation est la conversation de bootstrap ;
- enrichit mécaniquement `PROJECT.md` avec les réponses utilisateur ;
- résout `PROJECT.md` en privilégiant la version majuscule, avec fallback legacy si besoin.

### `ProjectBootstrapPromptExtension` (mm-app)

Nouvelle extension de prompt activée uniquement pour la conversation de bootstrap.

Elle impose au LLM de :
- expliquer que cette première discussion sert à définir le projet ;
- rappeler que l'utilisateur peut ouvrir d'autres discussions pour cesser l'interview ;
- préciser dès le premier message que la roadmap sera traitée plus tard, sur demande,
  dans une discussion dédiée ;
- poser une seule question courte et ciblée à la fois ;
- arrêter explicitement les questions quand le cadrage est suffisant pour l'instant.

### `ProjectSystemPromptExtension` / `ProjectContextExtension`

Le contexte système projet est maintenant composé de plusieurs couches :
- identité du projet et workspace principal ;
- contenu de `PROJECT.md` et `ROADMAP.md` ;
- mode bootstrap pour la première conversation.

`ProjectContextExtension` :
- préfère `PROJECT.md` / `ROADMAP.md` ;
- tolère `project.md` / `roadmap.md` en fallback ;
- applique une troncature de sécurité sur les fichiers trop longs.

### `ReadFileTool` / `WriteFileTool` / `ChatAgent`

Les fichiers de contexte projet sont traités comme des cas spéciaux.

Règles implémentées :
- `read_file` et `write_file` redirigent explicitement `PROJECT.md`, `ROADMAP.md`,
  `workspace/PROJECT.md` et `workspace/ROADMAP.md` vers le workspace interne du projet courant ;
- `read_file` et `read_project_file` cherchent d'abord dans le workspace interne du projet ;
- si le fichier est absent, ils basculent vers les workspaces rattachés ;
- `write_file` n'écrit jamais dans un workspace rattaché par fallback pour éviter
  d'écrire les fichiers de cadrage au mauvais endroit.

### `PathValidator` (mm-core)

Le validateur de chemin a été durci pour accepter correctement les chemins absolus déjà
situés dans un workspace autorisé. Cela corrige les faux positifs vus dans les logs lors
de lectures de fichiers projet ou de sources (`src/Main.java`) pourtant légitimes.

---

## 6. Nouveaux ADR

### ADR-026 — Architecture ChatAgent : Spring AI function calling natif
**Statut** : ⚠️ Partiellement superseded par ADR-034 (2026-06-28)

**Décision** : le ChatAgent utilise des `@Tool` Spring AI pour déléguer des tâches et lire
des fichiers projet. Pas de classifier externe.

**Justification** : simplicité, cohérence et testabilité.

**Révision (ADR-034)** : le ChatAgent conserve le function calling natif pour *décider* de
déléguer, mais un qualificateur déterministe est désormais intercalé pour *router* la tâche.
La décision « déléguer ou non » reste au LLM ; la décision « quel agent » devient déterministe.

---

### ADR-027 — Batch d'abord, SSE plus tard
**Statut** : ✅ Acté

**Décision** : le premier incrément utile est le mode batch. Le streaming SSE est ajouté ensuite
sur la même route via négociation `Accept`.

**Justification** : fermer d'abord le trou fonctionnel principal.

---

### ADR-028 — Conversation active Telegram en mémoire
**Statut** : ✅ Acté

**Décision** : `TelegramSessionService` conserve le `conversationId` actif par `chatId`
en mémoire.

**Justification** : KISS. Le pointeur actif peut être perdu au redémarrage sans gravité.

---

### ADR-029 — Purge mémoire Spring AI au niveau applicatif
**Statut** : ✅ Acté

**Décision** : `chatMemory.clear(...)` est déclenché explicitement dans les services
applicatifs lors des suppressions.

**Justification** : pas de couplage fort au schéma interne Spring AI.

---

### ADR-030 — `submit_task` comme `@Tool`, pas comme `AgentTool`
**Statut** : ✅ Acté (assignee révisé par ADR-035)

**Décision** : `submit_task` appartient au monde conversationnel du `ChatAgent`.

**Justification** : éviter une double friction de consentement.

**Révision (ADR-035)** : l'`assignee` du `TaskMessage` produit par `submit_task` n'est plus
`cortex` mais l'agent résolu par le qualificateur (`claude`/`codex`).

---

### ADR-031 — Lecture des fichiers de contexte à chaque appel
**Statut** : ✅ Acté

**Décision** : `PROJECT.md` et `ROADMAP.md` sont lus à chaque appel, sans cache.

**Justification** : ces fichiers évoluent souvent et sont modifiés pendant le cadrage.

---

### ADR-032 — La première conversation construit `PROJECT.md`
**Statut** : ✅ Acté

**Décision** : la première conversation d'un projet neuf devient une conversation spéciale
de cadrage initial. Son identité est persistée dans la config projet, son prompt est enrichi,
et les réponses utilisateur y sont ajoutées automatiquement dans `PROJECT.md`.

**Alternative écartée** : laisser `PROJECT.md` passif et espérer une mise à jour opportuniste
par le modèle sans mécanique explicite.

**Justification** : comportement mécanique, prédictible, testable, compréhensible pour l'utilisateur.

---

### ADR-033 — Les fichiers de contexte vivent dans le workspace interne du projet
**Statut** : ✅ Acté

**Décision** : `PROJECT.md` et `ROADMAP.md` sont toujours lus/écrits dans le workspace interne
du projet. Les workspaces rattachés ne servent qu'en fallback de lecture pour les autres fichiers.

**Alternative écartée** : résoudre les fichiers indistinctement dans tous les workspaces, ou
écrire dans le premier workspace trouvé.

**Justification** : le workspace interne est toujours présent et porte la source de vérité
du cadrage projet.

---

### ADR-034 — Qualificateur hybride de routage (règles + repli LLM)
**Statut** : ✅ Acté (2026-06-28)

**Décision** : un composant `TaskQualifier` classe toute tâche déléguée en catégorie métier
(`CODING`/`ANALYSIS`/`BUILD`) par des règles de mots-clés déterministes d'abord, avec un repli
petit LLM uniquement sur les cas ambigus et un défaut de sûreté sinon. La catégorie est ensuite
résolue en agent via la table `mm.agents.routing`. Chaque décision porte sa `source`
(`RULES`/`LLM`/`FALLBACK_DEFAULT`).

**Alternative écartée** : tout-LLM (variance, latence, coût) ou tout-règles (fragile sur le
langage naturel).

**Justification** : supprimer la variance du routage tout en restant tolérant au langage libre.
Réintroduit, sous forme déterministe et bornée, le « pré-qualificateur » prévu dans la conception
générale (§7.3) et retiré par ADR-026.

---

### ADR-035 — Routage direct conversation → spécialiste
**Statut** : ✅ Acté (2026-06-28)

**Décision** : pour le flux conversationnel, `submit_task` route directement vers le spécialiste
qualifié (`claude`/`codex`) au lieu de passer par `cortex`. Cortex reste l'orchestrateur du flux
REST `/api/tasks`.

**Alternative écartée** : continuer à router vers Cortex puis espérer une `sub_task` avec le bon
`assignee` (trois décisions LLM empilées → routage aléatoire, cause d'origine du problème).

**Justification** : déterminisme et garantie que les tâches de code atteignent réellement
Claude/Codex. Limite assumée : l'orchestration multi-étapes par Cortex n'opère plus depuis le
chat (à réintroduire via une catégorie `ORCHESTRATION → cortex` si le besoin se confirme).

---

### ADR-036 — Un seul cerveau de routage (suppression du double dispatch)
**Statut** : ✅ Acté (2026-06-28)

**Décision** : suppression de `TaskDispatcher`, `TaskRouter`, `CodingTaskController` et
`ManualCodingAgentController`. La logique de routage déterministe par catégorie est recentrée
dans le `TaskQualifier`. Seul subsiste le chemin `TaskQueue → Dispatcher → spécialistes`.

**Justification** : il existait deux systèmes de routage divergents (LLM par `assignee` vs
déterministe par `category`), ce dernier branché uniquement sur un endpoint REST de test —
source d'incohérence et de confusion.

---

### ADR-037 — Fermeture de boucle via `TaskOutcomeListener`
**Statut** : ✅ Acté (2026-06-28)

**Décision** : interface noyau `TaskOutcomeListener` notifiée par le `Dispatcher` en fin de
tâche utilisateur. L'hôte (`ConversationTaskOutcomeListener`) enregistre le résultat dans
`conversation_task` (statut final, résumé, agent, catégorie, `completed_at` — migration V6) et
réinjecte un résumé du résultat dans la mémoire de la conversation source.

**Alternative écartée** : coupler le noyau à la persistance JPA / `ChatMemory` (violation des
frontières mm-core).

**Justification** : enregistrement requêtable des actions et de leurs résultats, et continuité
de contexte conversationnel, sans casser la pureté du noyau.

---

## 7. Roadmap d'implémentation — Évolution 3

### E3-M0 — Passe de correction (pré-requis E3) ✅ `done` (2026-06-27)

**Livrables** :
- continuité `chatId` → `conversationId` côté Telegram ;
- purge mémoire Spring AI à la suppression ;
- injection du contexte projet minimal dans le prompt.

### E3-M1 — ChatAgent batch ✅ `done` (2026-06-27)

**Livrables** :
- `ChatAgent` branché au `ChatClient` ;
- `POST /messages` qui retourne une vraie réponse assistant ;
- persistance effective de l'historique conversationnel ;
- génération de titre au premier message.

### E3-M2 — Délégation de tâche depuis la conversation ✅ `done` (2026-06-27)

**Livrables** :
- outil `submit_task` ;
- couture conversation → `Dispatcher` ;
- réponse conversationnelle immédiate au lancement de tâche.

### E3-M3 — Contexte projet enrichi ✅ `done` (2026-06-27)

**Livrables initiaux** :
- `ProjectContextExtension` lit `PROJECT.md` et `ROADMAP.md` ;
- `read_project_file` permet des lectures ciblées ;
- garde-fous de path et tests associés.

**Compléments post-milestone effectivement implémentés** :
- création automatique de `PROJECT.md` et `ROADMAP.md` à la création d'un projet ;
- contenu d'amorçage générique dans ces fichiers ;
- préférence pour les noms en majuscules avec fallback legacy en minuscules ;
- première conversation dédiée au cadrage initial du projet ;
- alimentation mécanique de `PROJECT.md` pendant cette conversation ;
- redirection de `read_file` / `write_file` pour éviter toute création de `PROJECT.md`
  à la racine globale du workspace ;
- lecture avec fallback vers les workspaces rattachés si le fichier n'est pas présent
  dans le workspace interne ;
- correction `PathValidator` sur les chemins absolus autorisés.

### E3-M4 — Continuité Telegram et nettoyage mémoire ✅ `done` (2026-06-27)

**Livrables** :
- validation manuelle et automatisée de la continuité conversationnelle ;
- sécurisation de la purge mémoire ;
- durcissement des cas limites identifiés après M1/M2.

**Correctifs complémentaires effectivement absorbés** :
- recâblage du chat Telegram sur `ConversationService.chat()` au lieu du chemin `taskQueue` ;
- interception systématique des commandes `/...` avant LLM, outils et HITL ;
- blocage explicite de tout ajout de message dans une conversation archivée ;
- consolidation des tests autour de la persistance `chatId` -> `conversationId` et des suppressions.

### E3-M5 — Gestionnaire de conversations 🔄

**Cible** :
- navigation explicite entre conversations ;
- renommage ;
- archivage ;
- filtres de statut ;
- commandes Telegram de bascule.

Une partie de cette capacité est déjà visible côté service REST/applicatif, mais le périmètre
complet reste à finaliser et stabiliser.

**Compléments déjà livrés au-delà de la cible initiale** :
- menu Telegram `/conversations` structuré en deux niveaux projet -> conversations ;
- commandes guidées `/delete project|conv` et `/archive project|conv` avec boutons et annulation ;
- confirmation obligatoire avant suppression et demande de motif avant archivage ;
- cascade de suppression jusqu'au dossier physique du projet ;
- cascade d'archivage du projet vers ses conversations ouvertes ;
- rappel du nom du projet dans les messages Telegram de validation ;
- navigation Telegram unifiée projet -> conversation -> action pour `/projects`, `/conversations`,
  `/switch`, `/archive` et `/delete` ;
- tri des projets par activité récente dans ces vues ;
- protection explicite du projet système `Autre` dans les parcours Telegram et service.

### E3-M6 — Streaming SSE + lien conversation ↔ tâches ✅ `done` (2026-06-28)

**Livrables** :
- streaming SSE sur `POST /messages` ;
- persistance du message assistant assemblé en fin de flux ;
- table `conversation_task` ;
- endpoint de consultation des tâches liées à une conversation.

**Compléments effectivement livrés** :
- propriété `mm.chat.sse.timeout-ms` dans la configuration ;
- endpoint `GET /brief` pour le résumé ponctuel ;
- commande Telegram `/brief` ;
- libellés Telegram avec icônes de navigation ;
- règle explicite de création paresseuse de conversation après `/switch`.

---

## 7.1 Strategie de qualification des tests

La conception cible doit preserver trois niveaux de feedback :

- `mvn test` : **rapide** par defaut
- `mvn test -Pslow-tests` : **rapide + lent**
- `mvn test -Pfull-tests` : **full** automatisable

En consequence, tout test ajoute pendant E3 doit etre **qualifie des l'ecriture** :

- **Rapide** : test unitaire ou test leger, sans `@SpringBootTest`, sans DB/Flyway, sans timeout metier
- **Lent** : test avec demarrage Spring, SQLite/JPA/Flyway, MockMvc, autoconfiguration applicative
- **Tres lent** : test qui valide explicitement un timeout, une attente longue, ou un contexte lourd redemarre souvent

Traduction attendue dans le code :

- `@Tag("slow")` pour les tests lents
- `@Tag("very-slow")` pour les tests tres lents
- `@Tag("spike")` pour les spikes exclus du full standard
- `@Tag("manual")` pour les tests manuels

Regle structurante : **tout test de timeout est classe tres lent**, meme si sa duree est
raccourcie en environnement de test.

---

## 8. Points ouverts

- **Troncature du contexte projet** : `PROJECT.md` et `ROADMAP.md` sont actuellement tronqués
  à une limite fixe. Une mécanique de résumé ciblé serait plus robuste que cette troncature brute.

- **Roadmap en mode dédié** : la discussion de cadrage `PROJECT.md` est mécanique ; le même mode
  pourra être décliné plus tard pour `ROADMAP.md`, mais uniquement sur déclenchement explicite
  de l'utilisateur.

- **Réinjection du résultat de tâche dans la conversation** : à décider lors d'E3-M6 ou juste après.

- **Streaming Telegram** : l'édition de message reste une approximation et devra être mesurée.

---

*Document initialement rédigé le 2026-06-27 à l'issue de la conception E3.*
*Mis à jour le 2026-06-28 pour intégrer les implémentations réalisées après le milestone E3-M3.*
