# API de contrôle Pocketdock

Pocketdock expose une API HTTP locale pour être piloté sans interface : tests e2e, agents de
codage, scripts, autres applications de l'appareil.

- Adresse : `http://127.0.0.1:7777/v1` (boucle locale de l'appareil uniquement)
- Activation : **Moteur → API de contrôle**. Active par défaut sur les builds debug, désactivée
  sur les builds release.
- Authentification : en-tête `Authorization: Bearer <jeton>` sur toutes les routes sauf
  `/v1/health` et `/v1/pair`. Deux sortes de jetons :
  - le **jeton principal** (tous les droits), généré au premier lancement dans
    `files/control/token` ; il est affiché dans le tableau de bord, **Moteur → API de contrôle** ;
  - un **jeton par application**, obtenu par une demande d'accès que l'utilisateur accepte
    (ci-dessous), avec des droits limités et révocable à tout moment.

## Applications tierces : demande d'accès

Une application du téléphone obtient son propre jeton en trois temps :

1. `POST /v1/pair` (sans jeton) `{"name": "Mon appli", "scopes": ["read", "run"]}` → `202`
   `{"id": "…", "state": "PENDING", "poll": "/v1/pair/<id>"}`. Pocketdock affiche une notification ;
   l'utilisateur accepte (en retirant éventuellement des droits) ou refuse, dans les 5 minutes.
   Au plus 3 demandes en attente à la fois (`429` au-delà).
2. `GET /v1/pair/<id>` toutes les 2 à 3 secondes : `PENDING`, puis `APPROVED` avec
   `"token": "pdk_…"` et les `scopes` accordés, ou `DENIED` / `EXPIRED`. **Le jeton n'est remis
   qu'une fois** : l'application doit le conserver.
3. Ensuite, toutes les requêtes avec `Authorization: Bearer pdk_…`. Une route hors des droits
   accordés répond `403 {"error": "droit « run » requis", "required": "run"}` ; un jeton révoqué,
   `401`.

| Droit | Permet |
|---|---|
| `read` | toutes les lectures (`GET`) : état, conteneurs, images, stacks, tâches, journaux, réglages |
| `run` | `engine/start`, `containers/run`, `images/pull`, import et `up`/`start`/`restart`/`pull` d'une stack |
| `manage` | arrêt et suppression : `down`/`stop`/`DELETE` d'une stack, `start`/`stop`/`restart`/`kill` et suppression d'un conteneur via `/v1/docker` |
| `admin` | tout le reste (vaut tous les droits) : `exec`, API Docker complète, machines, réglages, arrêt et réinitialisation du moteur |

Le nom affiché à l'utilisateur est celui que déclare l'application : Pocketdock ne peut pas
vérifier quelle application parle sur `127.0.0.1`. Seule l'empreinte SHA-256 des jetons est
enregistrée (`files/control/clients.json`).

Gestion (jeton principal uniquement, utilisée par le tableau de bord) :

| Méthode | Route | Effet |
|---|---|---|
| GET | `/v1/clients` | applications autorisées, demandes en attente, liste des droits |
| POST | `/v1/clients/pending/<id>/approve` | accepte (`{"scopes": [...]}` facultatif, sous-ensemble de la demande) |
| POST | `/v1/clients/pending/<id>/deny` | refuse |
| DELETE | `/v1/clients/<id>` | révoque : le jeton cesse de fonctionner |

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

Les jetons protègent contre les autres applications de l'appareil, qui peuvent toutes joindre
`127.0.0.1`. Donnez à chaque application son propre jeton, avec les droits minimaux, plutôt que
le jeton principal. Ne l'active pas en release sans raison. Voir aussi TSK-019 (sécurisation de
l'API Docker elle-même).
