# AzerothCore sur Pocketdock (WotLK 3.3.5a)

Serveur [AzerothCore](https://www.azerothcore.org) complet (base MySQL, authserver, worldserver) sur le
téléphone, pour un client **3.3.5a (build 12340)** installé sur le même téléphone. Test « ultime »
de Pocketdock : 5 services, healthcheck, dépendances `service_completed_successfully`, volumes,
plusieurs Go de données.

## 1. Images arm64 (une fois)

Les images officielles `acore/*` n'existent qu'en amd64. Le workflow **Images AzerothCore arm64**
(Actions → Run workflow) compile AzerothCore sur une machine arm64 et publie
`ghcr.io/luraku/pocketdock-azerothcore:{db-import,client-data,authserver,worldserver}`.

Le paquet hérite de la visibilité publique du dépôt : rien à régler. (Sur un fork privé, il faudrait
le rendre public : Packages → `pocketdock-azerothcore` → Package settings → Change visibility.)

## 2. Régler le moteur

Onglet **Moteur** : **Mémoire 6144 Mo** au moins (8192 si possible), processeurs au maximum,
**disque 32 Go** au moins, puis redémarrer le moteur. Le worldserver charge les cartes en mémoire ;
avec 2 Go il serait tué faute de mémoire.

## 3. Lancer la stack

1. Télécharger [`pocketdock-azerothcore.tar.gz`](pocketdock-azerothcore.tar.gz?raw=1).
2. Tableau de bord → **Stacks** → **Importer** → **Importer et démarrer**.

Le premier lancement est long (prévoir une à plusieurs heures, téléphone branché) : téléchargement
des images, des données client (~1,5 Go, puis décompression), création des bases (dbimport), puis
chargement du monde. Suivre la sortie en direct, et les journaux de `ac-worldserver` jusqu'à
« WORLD: World Initialized ». Les lancements suivants sautent toutes ces étapes.

## 4. Créer un compte

Moteur → **Terminal** :

```sh
docker attach ac-worldserver
account create MonCompte MonMotDePasse
account set gmlevel MonCompte 3 -1
```

Sortir de la console sans arrêter le serveur : **Ctrl+P** puis **Ctrl+Q**.

## 5. Connecter le client

Dans le dossier du client, `Data/frFR/realmlist.wtf` (ou `enUS`, selon la langue) :

```
set realmlist 127.0.0.1
```

Les ports 3724 (authentification) et 8085 (monde) sont redirigés vers `127.0.0.1` du téléphone.
Le royaume « AzerothCore » pointe déjà sur `127.0.0.1:8085`.

## Notes

- Mot de passe MySQL par défaut `pocketdock` (base non exposée hors de la VM) ; pour le changer,
  ajouter un fichier `.env` avec `DOCKER_DB_ROOT_PASSWORD=…` avant le premier lancement.
- Les données client sont téléchargées par l'image `client-data` d'AzerothCore (archive
  communautaire `wowgaming/client-data`). Aucun fichier du jeu n'est inclus dans ce dépôt.
- Archive régénérée par `tar czf pocketdock-azerothcore.tar.gz -C azerothcore docker-compose.yml README.md`
  depuis `docs/examples`.
