# API de contrôle Pocketdock

Pocketdock expose une API HTTP locale pour être piloté sans interface : tests e2e, agents de
codage, scripts, autres applications de l'appareil.

- Adresse : `http://127.0.0.1:7777/v1` (boucle locale de l'appareil uniquement)
- Activation : **Moteur → API de contrôle**. Active par défaut sur les builds debug, désactivée
  sur les builds release.
- Authentification : en-tête `Authorization: Bearer <jeton>` sur toutes les routes sauf
  `/v1/health`. Le jeton est généré au premier lancement dans `files/control/token`. On peut
  le copier depuis l'onglet Moteur.

## Depuis un ordinateur (adb)

```sh
adb forward tcp:7777 tcp:7777
TOKEN=$(adb shell run-as dev.androdock cat files/control/token)   # build debug
curl -s -H "Authorization: Bearer $TOKEN" http://127.0.0.1:7777/v1/status | jq
```

Pour changer d'onglet à l'écran (captures, démonstrations) :

```sh
adb shell am start -n dev.androdock/.MainActivity --es tab images   # containers|images|machines|engine
```

## Tableau de bord web

L'API sert aussi un tableau de bord moderne (pods, conteneurs, images, volumes, machines,
moteur) sur `http://127.0.0.1:7777/`. Dans l'app, il s'ouvre avec l'icône **Tableau de bord**
de la barre du haut. Depuis un ordinateur, après `adb forward tcp:7777 tcp:7777`, on ouvre
`http://127.0.0.1:7777/#token=<jeton>` (le jeton est mémorisé dans le navigateur).

Pour le développer sans appareil : `python3 scripts/dashboard-mock/server.py`, puis
`http://127.0.0.1:7777/#token=demo` (API factice avec des données réalistes).

## Routes

| Méthode | Route | Effet |
|---|---|---|
| GET | `/`, `/app.js`, … | tableau de bord web (fichiers statiques, sans jeton) |
| GET | `/v1/health` | `{"ok":true}`, sans jeton |
| GET | `/v1/status` | version, ABI, état du moteur (`STOPPED`, `PREPARING`, `BOOTING`, `RUNNING`, `STOPPING`, `ERROR`), ports redirigés, machines |
| POST | `/v1/engine/start` | démarre la VM Docker (202, suivre `/v1/status`) |
| POST | `/v1/engine/stop` | arrêt propre de la VM (202) |
| POST | `/v1/engine/reset` | supprime le disque de données Docker (202) |
| GET | `/v1/engine/console?tail=N` | dernières lignes de la console série de la VM |
| * | `/v1/docker/<chemin>` | relais transparent vers l'API Docker Engine (`/v1/docker/containers/json?all=1`, `/v1/docker/version`…) ; 503 si le moteur ne tourne pas |
| POST | `/v1/exec` | `{"cmd":"docker ps","timeoutSec":300,"stdin":"…"}` : commande shell Android avec le client `docker` dans le PATH, renvoie `exitCode`, `stdout`, `stderr`, `timedOut`, `durationMs` |
| POST | `/v1/containers/run` | `{"image","name","ports":["8080:80"],"env":[],"volumes":[],"command","restartAlways"}` : crée et démarre, télécharge l'image si besoin (201) |
| POST | `/v1/images/pull` | `{"ref":"postgres:16-alpine"}` |
| GET | `/v1/compose` | projets Compose importés et dernières opérations |
| PUT | `/v1/compose/<nom>` | corps = archive `.tar.gz` du dossier du projet : déposée dans la VM (`/mnt/data/projects/<nom>`) |
| POST | `/v1/compose/<nom>/up` | `{"build":true,"services":["mysql","web"],"adaptHealthchecks":true}` → tâche (202). Par défaut, les healthchecks sont adaptés à la VM émulée (start_period ≥ 30 min, via la configuration résolue `.androdock-compose.json`) ; aussi `down` (`{"volumes":true}`), `restart`, `stop`, `start`, `pull`, `cmd` (`{"args":["ps"]}`) |
| DELETE | `/v1/compose/<nom>?volumes=1` | `down` puis suppression des fichiers du projet → tâche |
| GET | `/v1/jobs/<id>?tail=N` | état d'une tâche (`RUNNING`, `SUCCEEDED`, `FAILED`), code de sortie, sortie |
| GET | `/v1/machines` | machines, installation en cours (`installing`), dernière erreur (`lastError`), distributions disponibles |
| POST | `/v1/machines` | `{"name":"dev","distro":"alpine"\|"ubuntu"\|"custom","url":"https://…"}` : création asynchrone (202) |
| DELETE | `/v1/machines/<nom>` | supprime la machine |
| POST | `/v1/machines/<nom>/exec` | `{"cmd":"apk add git","timeoutSec":600}` : commande dans la machine (proot, root, `DOCKER_HOST` préconfiguré) |
| GET | `/v1/settings` | réglages du moteur |
| PATCH/PUT | `/v1/settings` | modification partielle : `cpus`, `memoryMb`, `diskGb`, `exposeOnLan`, `keepAwake`, `autoStart`, `controlApi` |

Les erreurs renvoient `{"error":"…"}` avec 400 (requête invalide), 401 (jeton), 404 (route ou
machine inconnue), 409 (conflit) ou 503 (moteur arrêté).

## Tests e2e

`scripts/e2e/run.py` enchaîne : installation, API, moteur, `hello-world`, nginx avec
redirection de port, machine Alpine, Docker depuis la machine, captures de chaque onglet, puis
arrêt. Il écrit `report.json`, `summary.md`, les captures, `logcat.txt` et `console.txt`. La
CI le lance sur un émulateur x86_64 (job `e2e`, artefact `e2e-report`).

## Sécurité

Le jeton protège contre les autres applications de l'appareil, qui peuvent toutes joindre
`127.0.0.1`. Ne l'active pas en release sans raison. Voir aussi TSK-019 (sécurisation de
l'API Docker elle-même).
