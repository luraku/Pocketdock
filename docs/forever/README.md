# Tester forever_docker sur Pocketdock

| Niveau | Services | Où | Prérequis |
|---|---|---|---|
| 1 | `mysql` + `web` (build local) | CI d'Pocketdock, automatique | aucun |
| 2 | + `bnetserver` (connexion Battle.net TLS, ports 1119/8081) | CI d'Pocketdock | image arm64 publiée sur GHCR |
| 3 | stack complète, `worldserver` compris | ton téléphone | image arm64 + données extraites du client |

## Niveau 2 : ce qu'il faut faire dans forever_docker

Compiler TrinityCore dans la VM émulée d'Pocketdock prendrait des heures et 8 Go de RAM. On le
compile donc une fois, nativement, sur un runner GitHub ARM, et Pocketdock télécharge l'image.

1. Copier [`build-image.yml`](build-image.yml) dans `forever_docker/.github/workflows/image.yml`,
   et [`docker-compose.androdock.yml`](docker-compose.androdock.yml) à la racine de `forever_docker`.
2. Pousser, puis **Actions → Image TrinityCore → Run workflow**. Compter environ 1 h (arm64 et
   amd64 en parallèle).
3. L'image hérite de la visibilité publique du dépôt : rien à faire. Si elle était privée :
   **profil GitHub → Packages → forever-trinitycore → Package settings → Change visibility**.
4. Vérifier : `docker buildx imagetools inspect ghcr.io/luraku/forever-trinitycore:latest` doit
   lister `linux/arm64` et `linux/amd64`.

État : fait le 2026-11-07 (PR luraku/forever_docker#1), image `ghcr.io/luraku/forever-trinitycore:latest`
publiée pour arm64 et amd64. Le premier build a révélé que deux patches de `docker/patches/` ne
s'appliquaient plus sur la branche forever amont (correctifs intégrés en amont) : corrigé dans la même PR.

Le scénario e2e du niveau 2 ajoute `bnetserver`, avec des certificats générés par
`scripts/gen-certs.sh`. Attention : bnetserver attend la ligne de son royaume dans
`auth.realmlist`, que crée `worldserver` à son premier démarrage. Il faut donc aussi lancer
worldserver, qui s'arrête ensuite faute de données client.

## Niveau 3 : sur le téléphone

- Image arm64 du niveau 2.
- Données du client extraites (`docker compose run --rm extractor` sur un PC), dans le dossier
  `data/` du projet avant de faire l'archive.
- VM avec au moins 4 Go de RAM (onglet Moteur). Le premier démarrage importe la base TDB :
  compter de longues minutes en émulation.
