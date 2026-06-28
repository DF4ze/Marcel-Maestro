# Plan de test manuel — CodingAgentAdapter (étapes 2+3+5)

> ⚠️ **OBSOLÈTE (2026-06-28).** Les endpoints REST décrits ici
> (`/api/coding-agent-tasks` et `/internal/manual/coding-agents/*`) ainsi que le
> `TaskDispatcher` et le `TaskRouter` ont été **supprimés**. Le routage déterministe a
> été recentré dans le `TaskQualifier` (règles + repli LLM) et le seul chemin nominal est
> désormais : conversation → `submit_task` (qualifié) → Dispatcher → spécialiste Claude/Codex.
> Ce document est conservé pour mémoire. Voir `docs/analyse-chaine-telegram-cortex-agents.md`.

## Objet

Valider manuellement le sous-système `CodingAgentAdapter` sur deux axes complémentaires :

1. la validation directe des adaptateurs CLI `ClaudeCodeAgent` et `CodexAgent` ;
2. la validation du flux REST asynchrone `TaskDispatcher` + `TaskRouter`.

L'objectif n'est pas seulement de vérifier qu'une requête répond en HTTP, mais de prouver que :

- le binaire CLI est bien résolu ;
- le mission brief est injecté avec le bon contexte ;
- le bon agent est choisi selon la catégorie ;
- le sous-processus démarre dans le bon répertoire ;
- le rapport `<MARCEL_REPORT>...</MARCEL_REPORT>` est extrait correctement ;
- le stop asynchrone est propre et déterministe.

## Endpoints à tester

### 1. Endpoints de validation manuelle des agents CLI

Actifs uniquement avec le profil Spring `manual-coding-agent-test` :

- `GET /internal/manual/coding-agents/preflight`
- `POST /internal/manual/coding-agents/{agentId}/execute`

Ces endpoints servent à tester immédiatement Claude et Codex et à récupérer directement un `AgentReport`.

### 2. Endpoints du flux asynchrone CodingAgentAdapter

Actifs dans l'application normale :

- `POST /api/coding-agent-tasks`
- `DELETE /api/coding-agent-tasks/{taskId}`

Ces endpoints valident le comportement de soumission asynchrone et de STOP, mais ne retournent pas encore de endpoint de consultation du rapport final. Pour l'instant, le résultat final se lit dans les logs applicatifs.

## Pré-requis

1. Avoir Postman.
2. Importer la collection `docs/evolution-4-coding-agents/postman-coding-agent-adapter-step-5.json`.
3. Avoir au moins un CLI installé :
   - Windows : `claude.cmd` et/ou `codex.cmd`
   - Linux : `claude` et/ou `codex`
4. Vérifier que le binaire est accessible dans le `PATH`, ou configurer explicitement :
   - `mm.agents.claude.binary`
   - `mm.agents.codex.binary`
5. Préparer un répertoire de travail réel :
   - `D:/Documents/Spring/Marcel-Maestro`

## Démarrage recommandé

### Campagne A — test complet des agents CLI

Lancer l'application avec le profil manuel :

```powershell
$env:SPRING_PROFILES_ACTIVE="manual-coding-agent-test"
mvn -pl mm-app spring-boot:run
```

### Campagne B — test du flux REST asynchrone

Lancer l'application normalement :

```powershell
mvn -pl mm-app spring-boot:run
```

## Piège Postman sous Windows

Ne pas envoyer un chemin Windows avec antislashs bruts dans le JSON :

```json
"workingDirectory": "D:\Documents\Spring\Marcel-Maestro"
```

Ce JSON est invalide. Utiliser à la place :

```json
"workingDirectory": "D:/Documents/Spring/Marcel-Maestro"
```

ou :

```json
"workingDirectory": "D:\\Documents\\Spring\\Marcel-Maestro"
```

## Ce qu'il faut comprendre avant de tester

Le point de vérité de l'exécution CLI n'est pas le texte libre du terminal, mais le bloc final :

```xml
<MARCEL_REPORT>{"status":"DONE", ...}</MARCEL_REPORT>
```

Sans ce bloc, Marcel classera la sortie en `TROUBLE`.

## Ordre recommandé

1. `1 - Manual Preflight`
2. `2 - Manual Claude`
3. `3 - Manual Codex`
4. `4 - Async Dispatcher`
5. `5 - Error Cases`

## 1. Test de preflight manuel

### Requête

`GET /internal/manual/coding-agents/preflight`

### Attendus

1. `HTTP 200`
2. présence des objets `claude` et `codex`
3. pour chaque agent :
   - `configuredBinary` cohérent avec la config active ;
   - `timeoutMinutes = 30` sauf surcharge locale ;
   - `resolved = true` si le binaire est disponible ;
   - `resolvedPath` renseigné si trouvé

### Interprétation

- si `resolved = false`, l'exécution de cet agent échouera immédiatement en `KO`
- si un seul binaire est installé, il suffit de tester cet agent-là

## 2. Test nominal manuel Claude

### Requête

`POST /internal/manual/coding-agents/claude/execute`

Payload :

```json
{
  "title": "Validation manuelle Claude",
  "description": "Analyse le contexte ci-dessous puis termine IMPERATIVEMENT par un bloc <MARCEL_REPORT> JSON valide. Si tout est lisible, retourne status DONE et un summary court.",
  "projectMd": "# PROJECT\nProjet de test manuel CodingAgentAdapter",
  "roadmapResultMd": "# ROADMAP RESULT\nEtape 5 en validation manuelle",
  "c3Facts": [
    "Le projet cible est Marcel-Maestro",
    "Le test porte sur l adaptateur Claude"
  ],
  "workingDirectory": "D:/Documents/Spring/Marcel-Maestro"
}
```

### Attendus

1. `HTTP 200`
2. réponse JSON de type `AgentReport`
3. `status = DONE` si Claude respecte le contrat
4. `summary` non vide
5. `factsDiscovered` et `decisions` présents, même si vides

### Logs à vérifier

- `ManualCodingAgentController execute`
- `ClaudeCodeAgent démarré`
- `ClaudeCodeAgent brief`
- `ClaudeCodeAgent sortie brute`
- `ClaudeCodeAgent terminé`

### Si le résultat est `TROUBLE`

Les causes probables sont :

1. Claude n'a pas terminé par un bloc `<MARCEL_REPORT>`
2. le JSON dans ce bloc est invalide
3. le process a retourné un `exitCode != 0` malgré un rapport `DONE`

Dans ce cas, lire `blocker` dans la réponse : il contient la sortie brute tronquée.

## 3. Test nominal manuel Codex

### Requête

`POST /internal/manual/coding-agents/codex/execute`

Payload :

```json
{
  "title": "Validation manuelle Codex",
  "description": "Analyse le contexte ci-dessous puis termine IMPERATIVEMENT par un bloc <MARCEL_REPORT> JSON valide. Si tout est lisible, retourne status DONE et un summary court.",
  "projectMd": "# PROJECT\nProjet de test manuel CodingAgentAdapter",
  "roadmapResultMd": "# ROADMAP RESULT\nEtape 5 en validation manuelle",
  "c3Facts": [
    "Le projet cible est Marcel-Maestro",
    "Le test porte sur l adaptateur Codex"
  ],
  "workingDirectory": "D:/Documents/Spring/Marcel-Maestro"
}
```

### Attendus

1. `HTTP 200`
2. réponse JSON de type `AgentReport`
3. `status = DONE` si Codex respecte le contrat
4. `summary` non vide

### Logs à vérifier

- `ManualCodingAgentController execute`
- `CodexAgent démarré`
- `CodexAgent brief`
- `CodexAgent sortie brute`
- `CodexAgent terminé`

## 4. Test du flux asynchrone Dispatcher + Router

Ce bloc ne teste pas la restitution immédiate d'un `AgentReport`. Il teste :

- la soumission HTTP ;
- la génération d'un `taskId` ;
- le routage par `category` ;
- la prise en charge du STOP ;
- les logs du `TaskDispatcher`.

### 4.A Soumission `CODING`

#### Requête

`POST /api/coding-agent-tasks`

Payload :

```json
{
  "title": "Soumission async coding",
  "description": "Analyse le contexte et termine par un MARCEL_REPORT JSON valide.",
  "category": "CODING",
  "projectMd": "# PROJECT\nFlux async",
  "roadmapResultMd": "# ROADMAP RESULT\nTest CODING",
  "c3Facts": [
    "Le routeur doit choisir Claude pour CODING"
  ],
  "workingDirectory": "D:/Documents/Spring/Marcel-Maestro"
}
```

#### Attendus

1. `HTTP 202`
2. présence d'un `taskId`
3. dans les logs :
   - `POST /api/coding-agent-tasks`
   - `TaskDispatcher submit`
   - `ClaudeCodeAgent démarré`
   - `TaskDispatcher terminé`

### 4.B Soumission `BUILD`

#### Requête

Même endpoint, mais :

```json
{
  "title": "Soumission async build",
  "description": "Analyse le contexte et termine par un MARCEL_REPORT JSON valide.",
  "category": "BUILD",
  "projectMd": "# PROJECT\nFlux async",
  "roadmapResultMd": "# ROADMAP RESULT\nTest BUILD",
  "c3Facts": [
    "Le routeur doit choisir Codex pour BUILD"
  ],
  "workingDirectory": "D:/Documents/Spring/Marcel-Maestro"
}
```

#### Attendus

1. `HTTP 202`
2. présence d'un `taskId`
3. dans les logs :
   - `TaskDispatcher submit`
   - `CodexAgent démarré`
   - `TaskDispatcher terminé`

### 4.C STOP d'une tâche asynchrone

#### Déroulé

1. lancer une soumission async
2. copier le `taskId`
3. appeler `DELETE /api/coding-agent-tasks/{taskId}` immédiatement

#### Attendus

1. `HTTP 204`
2. logs :
   - `TaskDispatcher stop`
   - `DELETE /api/coding-agent-tasks/{taskId}`
3. résultat attendu côté exécution :
   - la tâche n'est plus considérée comme active
   - un rapport `KO` est enregistré en mémoire avec un message de type `Tâche interrompue par STOP`

### Limite actuelle importante

Le flux `/api/coding-agent-tasks` ne fournit pas encore d'endpoint REST pour relire le rapport final. Le verdict détaillé se vérifie donc via les logs applicatifs et, pour l'instant, pas via Postman seul.

## 5. Cas d'erreur à valider

### A. Binaire introuvable

#### Préparation

Modifier localement la config, par exemple :

```yaml
mm:
  agents:
    codex:
      binary: codex-introuvable
```

Redémarrer l'application.

#### Requête

`POST /internal/manual/coding-agents/codex/execute`

#### Attendus

1. `HTTP 200`
2. `status = KO`
3. `summary` contient `codex CLI introuvable`

### B. Répertoire de travail invalide

#### Requête

```json
{
  "title": "Working dir invalide",
  "description": "Retourne un MARCEL_REPORT JSON valide.",
  "projectMd": "# PROJECT\nTest",
  "roadmapResultMd": "# ROADMAP RESULT\nTest",
  "c3Facts": [],
  "workingDirectory": "D:/dossier/inexistant/mm"
}
```

#### Attendus

1. `HTTP 200`
2. `status = TROUBLE` ou `KO` selon le mode d'échec effectif
3. `blocker` ou `summary` contient un indice sur l'échec

### C. Agent inconnu

#### Requête

`POST /internal/manual/coding-agents/inconnu/execute`

#### Attendus

1. `HTTP 200`
2. `status = KO`
3. `summary` contient `agentId inconnu`

## Comment dérouler concrètement la campagne

### Passage 1 — vérifier l'infrastructure

1. démarrer l'application avec `manual-coding-agent-test`
2. lancer `GET preflight`
3. confirmer que l'agent que tu veux tester a `resolved = true`

Si ce n'est pas le cas, inutile d'aller plus loin avant d'avoir corrigé le PATH ou la config binaire.

### Passage 2 — vérifier le contrat de rapport

1. lancer le test manuel Claude ou Codex
2. vérifier `status`, `summary`, `factsDiscovered`, `decisions`
3. si `TROUBLE`, lire `blocker`
4. vérifier les logs `brief`, `sortie brute`, `terminé`

Le point critique ici est la présence d'un vrai bloc `<MARCEL_REPORT>`.

### Passage 3 — vérifier le routage métier

1. redémarrer sans profil spécial si nécessaire
2. envoyer une tâche async en `CODING`
3. vérifier dans les logs que Claude est choisi
4. envoyer une tâche async en `BUILD`
5. vérifier dans les logs que Codex est choisi

Le HTTP seul ne suffit pas ici. Le vrai verdict est dans le couple `category` demandé et agent réellement démarré.

### Passage 4 — vérifier l'arrêt

1. soumettre une tâche async
2. appeler immédiatement le `DELETE`
3. vérifier que la réponse est `204`
4. confirmer dans les logs que le stop a bien été pris en compte

## Lecture des résultats

### Cas réussi

- `resolved = true` au preflight
- au moins un agent renvoie un `AgentReport` structuré
- le routage `CODING -> Claude` et `BUILD -> Codex` est visible dans les logs
- le STOP retourne `204` et laisse une trace explicite

### Cas non conforme mais exploitable

- `status = TROUBLE`
- la sortie brute est lisible dans `blocker`
- le problème vient du format de sortie du CLI et non du runner lui-même

### Cas d'infrastructure

- `status = KO`
- binaire absent, agent inconnu, working directory invalide, ou exécution non démarrable

## Verdict attendu

La feature est acceptable si :

1. `preflight` résout correctement les binaires présents
2. au moins un agent retourne un `AgentReport` structuré
3. les cas `KO` et `TROUBLE` sont propres et lisibles
4. le flux async retourne bien `202` à la soumission et `204` au stop
5. les logs permettent de confirmer le routage réel et la fin d'exécution
