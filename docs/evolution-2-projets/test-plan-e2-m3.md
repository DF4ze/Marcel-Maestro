# Plan de test — E2-M3 : Dossiers externes + PathValidator étendu

**Date :** 2026-06-24
**Prérequis :** E2-M1 ✅ (table `project_workspace`, endpoints REST add/remove) + E2-M2 ✅ (`AgentContext.projectId` toujours renseigné)

---

## 1. Vue d'ensemble

E2-M3 introduit deux comportements nouveaux à valider sur trois couches :

| Couche | Ce qui change | Comment valider |
|---|---|---|
| Unit (mm-core) | `PathValidator` accepte les chemins de dossiers externes | `mvn -pl mm-core test` |
| Intégration (mm-spring-boot-starter) | `JpaWorkspaceRegistry` interroge la DB | `mvn -pl mm-spring-boot-starter test` |
| End-to-end (API + agent) | Bypass HITL write sur chemin déclaré | Postman + logs Marcel |

---

## 2. Prérequis avant le test E2E

### 2.1 `TaskController` ✅ corrigé

`TaskSubmitRequest` accepte désormais `projectId` et `conversationId` (optionnels, rétro-compatibles).
Le `POST /api/tasks` propage ces champs dans `AgentContext` : le bypass HITL peut se déclencher.

```json
{
  "content": "...",
  "projectId": "{{projectId}}",
  "conversationId": "{{conversationId}}"
}
```

Les anciens appels sans `projectId` continuent de fonctionner (champ `null` → `"none"`).

### 2.2 Dossier de test sur la machine Windows

Créer un dossier réel qui servira de workspace externe déclaré, par exemple :

```
C:\Users\<nom>\Documents\Marcel-Test-Workspace\
```

Ce dossier n'a pas besoin d'exister physiquement pour la déclaration API, mais pour qu'un outil `write_file` puisse réellement y écrire, il doit exister.

### 2.3 Application lancée

```bash
mvn -pl mm-app spring-boot:run
# ou
java -jar mm-app/target/mm-app-*.jar
```

Port par défaut : `8080`.

---

## 3. Tests automatisés (à lancer avant le E2E)

```bash
# Litmus mm-core (ADR-003 — zéro infrastructure)
mvn -pl mm-core test

# Tests d'intégration du starter (JpaWorkspaceRegistry)
mvn -pl mm-spring-boot-starter test

# Suite complète
mvn verify
```

**Résultats attendus :**

| Test | Classe | Résultat attendu |
|---|---|---|
| Chemin relatif workspace interne | `PathValidatorTest` | PASS |
| Traversal vers parent | `PathValidatorTest` | PASS (exception) |
| Chemin dans workspace externe déclaré | `PathValidatorTest` | PASS |
| Path traversal dans workspace externe | `PathValidatorTest` | PASS (exception) |
| Sans projectId → externe ignoré | `PathValidatorTest` | PASS |
| Sans registry → externe rejeté | `PathValidatorTest` | PASS |
| Bypass HITL sur workspace déclaré | `ToolExecutionGuardTest` | PASS (hitlCount=0) |
| Workspace interne → HITL normal | `ToolExecutionGuardTest` | PASS |
| Hors tout workspace → HITL déclenché | `ToolExecutionGuardTest` | PASS |
| Traversal dans externe → rejeté PathValidator | `ToolExecutionGuardTest` | PASS |
| projectId null → pas de bypass | `ToolExecutionGuardTest` | PASS |
| Sans registry → pas de bypass | `ToolExecutionGuardTest` | PASS |
| Chemin sous dossier déclaré | `JpaWorkspaceRegistryTest` | PASS |
| Path traversal hors dossier | `JpaWorkspaceRegistryTest` | PASS |
| Projet sans dossier → false | `JpaWorkspaceRegistryTest` | PASS |
| projectId null → false | `JpaWorkspaceRegistryTest` | PASS |

---

## 4. Tests API (Postman)

Importer `e2m3-postman-collection.json` (dans ce même dossier).

La collection utilise des variables d'environnement :

| Variable | Valeur type | Description |
|---|---|---|
| `baseUrl` | `http://localhost:8080` | URL de base |
| `projectId` | *(généré)* | Renseigné automatiquement après création |
| `workspaceId` | *(généré)* | Renseigné automatiquement après ajout workspace |
| `externalWorkspacePath` | `C:\Users\<nom>\Documents\Marcel-Test-Workspace` | Dossier réel sur ta machine |
| `conversationId` | *(généré)* | Renseigné automatiquement après création conversation |

### Dossier 1 — Setup (ordre obligatoire)

| # | Requête | Résultat attendu |
|---|---|---|
| 1.1 | `POST /projects` — créer projet "Test E2M3" | 201 + `id` renseigné dans la variable |
| 1.2 | `POST /projects/{{projectId}}/workspaces` — déclarer le dossier externe | 201 + `wsId` renseigné |
| 1.3 | `GET /projects/{{projectId}}` — vérifier le projet | 200 + champ `workspaces` contient le dossier déclaré |

### Dossier 2 — Gestion des workspaces

| # | Requête | Résultat attendu |
|---|---|---|
| 2.1 | `POST /projects/{{projectId}}/workspaces` — ajouter un second dossier | 201 |
| 2.2 | `GET /projects/{{projectId}}` — lister les workspaces | 200 + 2 workspaces |
| 2.3 | `DELETE /projects/{{projectId}}/workspaces/{{workspaceId}}` — retirer le premier | 204 |
| 2.4 | `GET /projects/{{projectId}}` — vérifier la suppression | 200 + 1 workspace restant |
| 2.5 | `POST /projects/projet-inexistant/workspaces` — projet inexistant | 404 |
| 2.6 | `POST /projects/{{projectId}}/workspaces` — path vide | 400 |

### Dossier 3 — Bypass HITL E2E *(nécessite correction `TaskController`)*

> **Objectif** : envoyer une tâche qui demande à l'agent d'écrire dans le dossier externe — vérifier dans les logs qu'aucune popup HITL n'est déclenchée.

| # | Requête | Log attendu | Résultat |
|---|---|---|---|
| 3.1 | `POST /projects/{{projectId}}/conversations` — créer conversation | — | 201 |
| 3.2 | `POST /api/tasks` avec `projectId` et contenu "Écris un fichier test.txt dans `{{externalWorkspacePath}}`" | `HITL write bypassé` dans les logs | 202 |
| 3.3 | Vérifier `{{externalWorkspacePath}}/test.txt` créé | Pas de popup HITL | Fichier présent |
| 3.4 | `POST /api/tasks` avec chemin hors tout workspace | `HITL` déclenché | Popup HITL ou refus selon config |

**Lecture des logs à surveiller :**

```
# Bypass confirmé :
INFO  ToolExecutionGuard : Chemin '...' dans workspace déclaré pour le projet '...' — HITL write bypassé

# Chemin autorisé par PathValidator :
DEBUG PathValidator : Chemin autorisé (workspace externe déclaré) : ... -> ...

# Si HITL normal (chemin hors workspace déclaré) :
DEBUG ToolExecutionGuard : Vérification HITL pour l'outil '...' (risque HIGH)
```

### Dossier 4 — Sécurité path-traversal

| # | Requête | Résultat attendu |
|---|---|---|
| 4.1 | `POST /api/tasks` — chemin `{{externalWorkspacePath}}\..\..\..\Windows\System32\test.txt` | PathValidator rejette (path violation dans le résultat d'outil) |
| 4.2 | `POST /api/tasks` — chemin absolu hors workspace et hors externe déclaré | HITL déclenché (pas de bypass) |

---

## 5. Scénarios de régression

Vérifier que les comportements E2-M1/M2 ne sont pas cassés.

| Scénario | Comportement attendu |
|---|---|
| Tâche sans `projectId` (ancien `POST /api/tasks`) | HITL s'applique normalement (pas de bypass) |
| Chemin dans workspace interne | HITL selon `RiskLevel` (inchangé) |
| Supprimer un projet → ses workspaces externes disparaissent | `GET /projects/{{projectId}}` → `workspaces: []` |
| Créer deux projets avec le même nom | 409 Conflict (inchangé) |

---

## 6. Checklist finale avant merge

- [ ] `mvn verify` vert (tous les modules)
- [ ] `mvn -pl mm-core test` vert — litmus ADR-003
- [ ] Aucun import `jakarta.persistence` dans `mm-core`
- [ ] Log `HITL write bypassé` visible sur chemin de dossier déclaré
- [ ] Pas de popup HITL en écrivant dans le dossier externe déclaré
- [ ] Popup HITL toujours présente pour un chemin hors tout workspace
- [ ] Path traversal (`../../`) rejeté même sur dossier déclaré
- [ ] `JpaWorkspaceRegistryTest` : 6 tests verts
