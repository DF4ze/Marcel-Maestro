# Évolution 2 — Introduction des Projets
**Statut : En cours de conception**
**Date : 2026-06-24**
**Prérequis : V1 (Évolution 1) complète — moteur agentique, HITL, mémoire C3, Dispatcher, Telegram**

---

## 1. Vision

Marcel Maestro passe d'un agent mono-contexte à un système **multi-projets**. L'inspiration
directe est l'UX de Cowork : une liste de projets, et dans chaque projet une liste de
conversations. Plusieurs projets peuvent tourner en parallèle — lancer une tâche sur le
projet A, switcher sur le projet B et lancer une autre tâche sans attendre.

**Ce que ça change fondamentalement :**
- `AgentContext.projectId` (déjà présent mais ignoré) devient le pivot de toute isolation
- La mémoire conversationnelle est scindée par conversation → plus d'interférence entre projets
- Le workspace est structuré : dossier Marcel-géré par projet + dossiers externes déclarés
- Le HITL niveau `projet` est réellement scopé à un projet identifié

---

## 2. Décisions fondamentales

### 2.1 Définition du projet

**Création** : un nom seul suffit. Le nom est sanitisé en `kebab-case` pour créer un
dossier dans le workspace principal. Aucune autre information obligatoire.

**Extensions prévues (non implémentées maintenant)** : description, tech stack, liste
blanche de spécialistes, implémentation d'orchestrateur spécifique. La structure DB est
extensible sans refactoring.

**Importation** : si un dossier de ce nom existe déjà dans le workspace, Marcel propose
de l'importer comme projet (plutôt que de rejeter). En cas de collision entre deux noms
sanitisés différents qui donnent le même slug, rejet explicite avec message clair.

**Stockage** : DB-driven (`project` table en SQLite). La source de vérité est la base,
pas le scan du filesystem.

### 2.2 Cycle de vie d'un projet

| Statut | Sémantique |
|--------|------------|
| `ACTIVE` | Reçoit de nouvelles conversations et tâches |
| `ARCHIVED` | Consultable (historique, mémoire), plus de nouvelles tâches |
| `(suppression)` | Irréversible — DB + filesystem nettoyés intégralement |

Pas de corbeille en V2 (différé). La suppression est définitive.

### 2.3 Conversation = session

Une conversation est une session de travail sur un projet. Elle contient 1..N tâches.
Elle est **indéfiniment ouverte** : on peut revenir dessus 2 semaines plus tard et
retrouver l'historique complet (mémoire conversationnelle rechargée depuis la DB).

**Titre** : généré par LLM de façon asynchrone à partir du premier message.
La tâche part immédiatement ; le titre arrive dans les secondes suivantes et s'affiche
dès qu'il est prêt. Approche la plus simple — pas de latence ajoutée.

### 2.4 Mémoire conversationnelle persistée (JdbcChatMemory)

Spring AI `JdbcChatMemory` (JDBC-backed) remplace le `ChatMemory` in-memory par défaut.
La mémoire est scoped par `conversationId` — clé unique par session.

**Conséquence directe** : switcher de projet = switcher de `conversationId` = charger
un historique distinct. **Aucune discussion du projet X ne peut influencer le projet Y.**

La table Spring AI (`spring_ai_chat_memory` ou équivalente) est ajoutée via Flyway
dans la même migration que les tables projet/conversation.

### 2.5 Dossiers de travail

**Workspace interne** : `{workspace}/{sanitizedName}/`
- Géré exclusivement par Marcel
- Contenu : `project.md`, `roadmap.md`, `roadmap_result.md`, `rapports/`, journaux

**Dossiers externes** : N dossiers déclarés explicitement par l'utilisateur (ex: repo Git)
- L'utilisateur a *explicitement* accordé accès → HITL write bypassé sur ces dossiers
- `PathValidator` doit reconnaître ces chemins et court-circuiter la demande de consentement
- Plusieurs dossiers externes possibles par projet

**Sécurité** : le bypass HITL write est limité aux dossiers déclarés. Tout chemin hors
workspace interne ET hors dossiers externes déclarés reste soumis au HITL normal.

### 2.6 Règle HITL Projet renforcée

`ALLOW_PROJECT` dans `PersistentConsentCache` doit obligatoirement être associé à un
`projectId` non null. Cette règle est **enforced dans le starter**, pas seulement dans l'UI.

Scope stocké en base : `"project:<projectId>"` — jamais `"global"` pour ce niveau de
consentement. Tentative d'enregistrer un `ALLOW_PROJECT` sans `projectId` → exception.

### 2.7 Virtual Threads (Java 21 Loom)

Remplacement du `ThreadPoolTaskExecutor` borné par les virtual threads de Java 21.

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

Spring Boot 3.2+ gère automatiquement l'exécuteur (`@Async`, `TaskExecutor`, Tomcat).
Plus de risque de rejet de thread quand plusieurs tâches longues tournent en parallèle.
Le coût d'un virtual thread est négligeable (quelques centaines d'octets vs ~1Mo pour un
thread OS).

---

## 3. Schéma DB

Migration Flyway `V2__projects_and_conversations.sql` :

```sql
-- Projet
CREATE TABLE project (
    id              TEXT PRIMARY KEY,          -- UUID
    name            TEXT NOT NULL,             -- nom d'affichage
    sanitized_name  TEXT NOT NULL UNIQUE,      -- slug, clé du dossier workspace
    workspace_path  TEXT NOT NULL,             -- chemin absolu du dossier interne
    status          TEXT NOT NULL DEFAULT 'ACTIVE',  -- ACTIVE | ARCHIVED
    created_at      TEXT NOT NULL,
    updated_at      TEXT NOT NULL
);

-- Dossiers de travail externes (0..N par projet)
CREATE TABLE project_workspace (
    id          TEXT PRIMARY KEY,
    project_id  TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    path        TEXT NOT NULL,
    added_at    TEXT NOT NULL
);

-- Conversation (session de travail)
CREATE TABLE conversation (
    id          TEXT PRIMARY KEY,              -- UUID = conversationId Spring AI
    project_id  TEXT NOT NULL REFERENCES project(id) ON DELETE CASCADE,
    title       TEXT,                          -- nullable jusqu'à génération LLM
    started_at  TEXT NOT NULL,
    status      TEXT NOT NULL DEFAULT 'OPEN'
);

-- Mémoire conversationnelle Spring AI (JdbcChatMemory)
-- Créée par Spring AI via Flyway ou auto-schema — à valider selon la version
```

La suppression d'un projet (`ON DELETE CASCADE`) nettoie automatiquement ses dossiers
externes et ses conversations en base. Le filesystem est nettoyé par le service applicatif.

---

## 4. API REST

### Projets

| Méthode | Endpoint | Action |
|---------|----------|--------|
| `POST` | `/projects` | Créer (body: `{"name": "..."}`) |
| `GET` | `/projects` | Lister (query param: `status=ACTIVE\|ARCHIVED\|ALL`) |
| `GET` | `/projects/{id}` | Détail |
| `PUT` | `/projects/{id}` | Modifier (nom à ce stade) |
| `POST` | `/projects/{id}/archive` | Archiver |
| `POST` | `/projects/{id}/unarchive` | Désarchiver |
| `DELETE` | `/projects/{id}` | Supprimer (irréversible) |
| `POST` | `/projects/import` | Importer un dossier existant |
| `POST` | `/projects/{id}/workspaces` | Ajouter un dossier externe |
| `DELETE` | `/projects/{id}/workspaces/{wsId}` | Retirer un dossier externe |

### Conversations

| Méthode | Endpoint | Action |
|---------|----------|--------|
| `POST` | `/projects/{id}/conversations` | Démarrer (body: `{"message": "..."}`) |
| `GET` | `/projects/{id}/conversations` | Lister les conversations du projet |
| `GET` | `/conversations/{id}` | Détail + messages |
| `POST` | `/conversations/{id}/messages` | Envoyer un message dans une conversation existante |

---

## 5. Impact sur les composants existants

### AgentContext
`projectId` et `conversationId` sont déjà présents. Ils doivent maintenant toujours être
renseignés (non null) pour tout appel à un agent. Le `Dispatcher` est responsable de les
injecter depuis la tâche entrante.

### PersistentConsentCache (starter)
Validation ajoutée : si `consentDecision == ALLOW_PROJECT` et `projectId == null` →
`IllegalArgumentException`. Le scope est construit comme `"project:" + projectId` et ne
peut jamais valoir `"global"`.

### PathValidator (mm-core)
Nouveau cas : chemin dans un dossier externe déclaré → `isInDeclaredWorkspace() = true`.
`ToolExecutionGuard` consulte cette information avant de décider d'appliquer le HITL write.
La liste des dossiers externes est injectée depuis le `ProjectRepository` via l'`AgentContext`.

### TelegramMmController
- Toutes les notifications préfixées avec `[NomProjet]`
- Notion de "projet actif Telegram" : variable de session par `chatId`
- Commandes ajoutées : `/projects` (liste), `/switch <name>` (changer le projet actif)

### Dispatcher
Pas de changement architectural. Le virtual thread executor remplace simplement le
`ThreadPoolTaskExecutor`. L'`AgentContext` transmis avec chaque `TaskMessage` inclut
désormais un `projectId` et un `conversationId` valides.

---

## 6. Nouveaux ADR

### ADR-020 — Virtual threads Java 21 (Loom)
**Statut** : ✅ Acté

**Décision** : `spring.threads.virtual.enabled=true`. Le `ThreadPoolTaskExecutor` borné
est remplacé par l'exécuteur virtual-thread de Spring Boot 3.2+.

**Alternative écartée** : Pool classique borné avec `CallerRunsPolicy`.

**Justification** : KISS — une propriété suffit. Les virtual threads sont conçus pour des
charges I/O-bound (appels LLM, outils réseau) : légèreté native, pas de risque de rejet.
Le LLM étant le vrai goulot d'étranglement, pas les threads.

---

### ADR-021 — JdbcChatMemory pour la persistance conversationnelle
**Statut** : ✅ Acté

**Décision** : Spring AI `JdbcChatMemory`, scoped par `conversationId` (UUID de la
conversation). La mémoire conversationnelle survit aux redémarrages et est isolée par session.

**Alternative écartée** : `InMemoryChatMemory` (perd l'historique au redémarrage, et
partage accidentellement le contexte entre projets si le conversationId n'est pas changé).

**Justification** : SSOT — une conversation ouverte il y a 2 semaines doit être reprise avec
son contexte exact. L'isolation par `conversationId` est la seule garantie que le projet X
n'influence pas le projet Y.

---

### ADR-022 — Entité Project DB-driven (pas file-driven)
**Statut** : ✅ Acté

**Décision** : Une table `project` en SQLite est la source de vérité. La détection des
projets n'est pas un scan de filesystem.

**Alternative écartée** : Détection par présence de `project.md` dans le workspace
(file-driven, comme Cowork).

**Justification** : SSOT — le scan de filesystem est fragile (renommage manuel de dossier,
fichier absent, permissions). La DB garantit la cohérence du CRUD et permet des queries
propres (liste par statut, recherche). Le filesystem reste une projection de la DB.

---

### ADR-023 — Dossiers externes déclarés = bypass HITL write
**Statut** : ✅ Acté

**Décision** : Tout dossier ajouté explicitement dans `project_workspace` est considéré
comme un espace de travail autorisé. Le HITL write y est bypassé automatiquement.

**Alternative écartée** : HITL write obligatoire sur tous les chemins hors workspace interne.

**Justification** : L'utilisateur a *explicitement* déclaré ces dossiers comme espaces de
travail — c'est un consentement implicite structurel, plus fort et plus précis qu'un HITL
récurrent. La sécurité est toujours garantie par l'anti path-traversal (seuls les sous-chemins
du dossier déclaré sont autorisés).

---

### ADR-024 — Conversation à durée de vie indéfinie
**Statut** : ✅ Acté

**Décision** : Une conversation ne se ferme jamais automatiquement. L'utilisateur peut y
revenir à tout moment. Pas de timeout, pas de fermeture sur `DONE` de la dernière tâche.

**Justification** : KISS + UX — forcer la clôture d'une conversation crée des frictions inutiles
(l'utilisateur voudra souvent ajouter une remarque ou une tâche de suivi).

---

### ADR-025 — Titre de conversation généré par LLM, asynchrone
**Statut** : ✅ Acté

**Décision** : Le titre est `null` à la création. Un appel LLM léger (Haiku ou équivalent,
pas Cortex) est déclenché *après* la soumission de la première tâche et met à jour le
champ `title` quand il est prêt. L'UI affiche un placeholder en attendant.

**Alternative écartée** : Titre synchrone (bloque l'envoi), titre manuel (friction UX),
titre = premier message tronqué (pas de valeur ajoutée).

**Justification** : KISS — la solution la plus simple qui donne un bon résultat sans latence
perçue.

---

## 7. Roadmap d'implémentation — Évolution 2

Chaque milestone livre une tranche verticale fonctionnelle.

### E2-M1 — Virtual threads + Entité Project + CRUD REST
**Objectif** : Les projets existent en base et sont pilotables par API. Le moteur passe sur virtual threads.

**Livrables** :
- `spring.threads.virtual.enabled=true` + suppression du `ThreadPoolTaskExecutor` explicite
- Entité `Project` (JPA), `ProjectRepository`, migration Flyway V2
- `ProjectService` : create (sanitize + créer dossier), archive, unarchive, delete (DB + filesystem), import
- Validation : collision de `sanitizedName` → rejet avec message explicite
- `ProjectController` REST : CRUD complet + `/import` + gestion des dossiers externes
- Tests : cycle CRUD, collision de nom, suppression filesystem, import de dossier existant

**Hors scope** : Conversations, JdbcChatMemory, Telegram, PathValidator.

---

### E2-M2 — Entité Conversation + JdbcChatMemory + isolation mémoire
**Objectif** : Les conversations existent. Le switch de projet isole la mémoire.

**Livrables** :
- Entité `Conversation` (JPA), `ConversationRepository`, migration Flyway (table + Spring AI chat memory)
- `JdbcChatMemory` configuré, scoped par `conversationId`
- `AgentContext` : `projectId` et `conversationId` toujours renseignés
- `Dispatcher` : injecte `projectId`/`conversationId` depuis le `TaskMessage`
- `ConversationController` REST : créer, lister, lire, envoyer message
- Tests : isolation mémoire (projet A n'influence pas projet B), reprise après redémarrage

**Hors scope** : Titre LLM, dossiers externes, Telegram enrichi.

---

### E2-M3 — Dossiers externes + PathValidator étendu
**Objectif** : Marcel peut travailler dans des dossiers hors workspace sans demande HITL write.

**Livrables** :
- Table `project_workspace` + endpoints REST (add/remove dossier externe)
- `PathValidator` : méthode `isInDeclaredWorkspace(path, projectId)` → consulte DB
- `ToolExecutionGuard` : bypass HITL write si `isInDeclaredWorkspace() == true`
- Tests : path dans dossier externe → pas de HITL ; path hors tout dossier déclaré → HITL normal ;
  anti path-traversal actif dans les deux cas

---

### E2-M4 — Règle HITL Projet + validation PersistentConsentCache
**Objectif** : Un consentement ALLOW_PROJECT est toujours scopé à un projet identifié.

**Livrables** :
- `PersistentConsentCache` : validation `projectId != null` pour `ALLOW_PROJECT` → exception si null
- Scope stocké : `"project:<projectId>"` — le mot `"global"` est interdit pour ce niveau
- Tests : tentative de ALLOW_PROJECT sans projectId → exception ; rechargement au démarrage
  avec le bon scope ; isolation inter-projets des consentements persistés

---

### E2-M5 — Titre de conversation LLM + Telegram enrichi
**Objectif** : Les conversations ont un titre. Telegram est contextualisé par projet.

**Livrables** :
- Service de génération de titre : appel LLM asynchrone (modèle léger) après premier message,
  update `Conversation.title`, endpoint `GET /conversations/{id}` reflète le titre dès qu'il est prêt
- `TelegramMmController` : préfixe `[NomProjet]` dans toutes les notifications
- Commande `/projects` : liste les projets actifs avec leur nombre de conversations ouvertes
- Commande `/switch <name>` : change le projet actif de la session Telegram (stocké par `chatId`)
- Tests : titre généré et persisté ; switch Telegram isole bien les conversations

---

## 8. Points ouverts

- **Prompt de génération de titre** : quel modèle ? même provider que Cortex (coût), ou modèle
  plus léger séparé ? À trancher à E2-M5.
- **Projet actif Telegram** : état stocké en mémoire (perdu au redémarrage) ou en DB
  (table `telegram_session`) ? À trancher à E2-M5.
- **Futurs paramètres de projet** : description, tech stack, liste blanche de spécialistes —
  coutures à laisser ouvertes sans implémenter (champ JSON `config` dans la table `project` ?).

---

*Document rédigé le 2026-06-24 à l'issue de la session de conception.*
