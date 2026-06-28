# Plan de test manuel — CodingAgentAdapter (Étape 5)

## Objet

Valider manuellement les adaptateurs CLI `ClaudeCodeAgent` et `CodexAgent`, ainsi que les
composants associés :

- construction du mission brief ;
- résolution cross-platform des binaires ;
- exécution du subprocess dans le bon répertoire ;
- extraction du bloc `<MARCEL_REPORT>...</MARCEL_REPORT>` ;
- mapping de sortie vers `AgentReport`.

## Limite importante

Cette implémentation n'est pas encore branchée dans un flux métier Marcel existant.
Il n'y a donc pas de scénario fonctionnel standard via `/projects/...` ou `/api/tasks`.

Pour rendre le test manuel réaliste avec Postman, un endpoint local de validation a été
ajouté sous profil Spring dédié :

- `GET /internal/manual/coding-agents/preflight`
- `POST /internal/manual/coding-agents/{agentId}/execute`

Ce contrôleur n'est actif **que** si l'application démarre avec :

```powershell
$env:SPRING_PROFILES_ACTIVE="manual-coding-agent-test"
mvn -pl mm-app spring-boot:run
```

## Pré-requis

1. Démarrer `mm-app` avec le profil `manual-coding-agent-test`.
2. Importer la collection Postman `docs/evolution-4-coding-agents/postman-coding-agent-adapter-step-5.json`.
3. Vérifier que le CLI à tester est installé sur la machine :
   - Windows : `claude.cmd` et/ou `codex.cmd`
   - Linux : `claude` et/ou `codex`
4. Vérifier que le binaire ciblé est accessible dans le `PATH`, ou configurer explicitement :
   - `mm.agents.claude.binary`
   - `mm.agents.codex.binary`
5. Préparer un répertoire de travail réel à donner dans la requête, par exemple :
   - `D:/Documents/Spring/Marcel-Maestro`

## Piège Postman sous Windows

Dans le body JSON, **ne pas** injecter un chemin Windows avec antislashs bruts via une
variable Postman, par exemple :

```json
"workingDirectory": "D:\Documents\Spring\Marcel-Maestro"
```

Ce JSON est invalide, car `\D` n'est pas un escape JSON reconnu. Le symptôme côté serveur est :

`HTTP 400` avec un log Spring du type :

`HttpMessageNotReadableException: JSON parse error: Unrecognized character escape 'D'`

Utiliser à la place :

```json
"workingDirectory": "D:/Documents/Spring/Marcel-Maestro"
```

ou, si tu saisis le chemin en dur, une version doublement échappée :

```json
"workingDirectory": "D:\\Documents\\Spring\\Marcel-Maestro"
```

## Ce qu'il faut comprendre avant de tester

Le point de vérité du test n'est pas le texte libre du CLI mais le bloc final :

```xml
<MARCEL_REPORT>{"status":"DONE", ...}</MARCEL_REPORT>
```

Sans ce bloc, Marcel classera la réponse en `TROUBLE`.

## Ordre recommandé

1. `1 - Preflight`
2. `2 - Claude`
3. `3 - Codex`
4. `4 - Cas d'erreur`

## 1. Preflight

### Requête

`GET /internal/manual/coding-agents/preflight`

### Attendus

1. `HTTP 200`
2. Présence des deux objets `claude` et `codex`
3. Pour chaque agent :
   - `configuredBinary` correspond à la config active
   - `timeoutMinutes = 30` sauf surcharge locale
   - `resolved = true` si le binaire est trouvable
   - `resolvedPath` contient le chemin absolu si résolu

### Interprétation

- Si `resolved = false`, le test d'exécution échouera en `KO` avant même de lancer le CLI.
- Si un seul binaire est installé, tester uniquement cet agent.

## 2. Test nominal Claude

### Requête

`POST /internal/manual/coding-agents/claude/execute`

Payload type :

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
2. Réponse JSON `AgentReport`
3. `status = DONE` si Claude respecte le contrat demandé
4. `summary` non vide
5. `factsDiscovered` et `decisions` présents, au moins comme tableaux vides

### Vérifications en logs

Chercher dans le log applicatif :

- `ClaudeCodeAgent démarré`
- `ClaudeCodeAgent brief`
- `ClaudeCodeAgent sortie brute`
- `ClaudeCodeAgent terminé`

### Si le résultat est `TROUBLE`

Ca signifie généralement l'un des cas suivants :

1. Claude a répondu sans bloc `<MARCEL_REPORT>`
2. le JSON du bloc est invalide
3. le process a renvoyé un `exitCode != 0` avec un rapport `DONE`

Dans ce cas, regarder le `blocker` retourné par l'API : il contient la sortie brute tronquée.

## 3. Test nominal Codex

### Requête

`POST /internal/manual/coding-agents/codex/execute`

Même payload que pour Claude, en adaptant éventuellement le texte :

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
2. Réponse JSON `AgentReport`
3. `status = DONE` si Codex respecte le contrat
4. `summary` non vide

### Vérifications en logs

Chercher :

- `CodexAgent démarré`
- `CodexAgent brief`
- `CodexAgent sortie brute`
- `CodexAgent terminé`

## 4. Cas d'erreur à valider

### A. Binaire introuvable

But : vérifier le comportement `KO`.

#### Préparation

Modifier localement `application.yml` pour pointer vers un faux binaire, par exemple :

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

But : vérifier qu'un échec de lancement remonte en `TROUBLE`.

#### Requête

Envoyer un `workingDirectory` inexistant :

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
2. `status = TROUBLE` ou `KO` selon le mode d'échec effectif du CLI
3. `blocker` ou `summary` contient un indice sur l'échec de démarrage

### C. Agent inconnu

#### Requête

`POST /internal/manual/coding-agents/inconnu/execute`

#### Attendus

1. `HTTP 200`
2. `status = KO`
3. `summary` contient `agentId inconnu`

## Lecture des résultats

### Cas réussi

- `status = DONE`
- `summary` exploitable
- logs démarrage / fin présents
- `resolved = true` au preflight

### Cas non conforme mais explicable

- `status = TROUBLE`
- sortie brute visible dans `blocker`
- utile pour ajuster plus tard le prompt ou les arguments CLI

### Cas d'infrastructure

- `status = KO`
- binaire absent, agent inconnu, ou prérequis non satisfaits

## Ce qu'il ne faut pas conclure trop vite

Un `TROUBLE` n'implique pas forcément que le runner cross-platform est cassé.
Très souvent, cela signifie seulement que le CLI testé n'a pas respecté le format de
rapport demandé.

## Vérification complémentaire hors Postman

Si tu veux un contrôle de non-régression local du code ajouté :

1. compiler isolément le package `specialist.coding`
2. lancer les tests unitaires associés quand le build global `mm-app` sera réparé

## Verdict attendu en fin de campagne

La feature est considérée acceptable si :

1. `preflight` résout correctement les binaires présents ;
2. au moins un agent (`claude` ou `codex`) retourne un `AgentReport` structuré ;
3. les cas `KO` et `TROUBLE` sont propres, lisibles et déterministes ;
4. les logs permettent de comprendre rapidement l'échec.
