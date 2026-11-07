# Exemples

## Démo Compose (`demo/`)

Trois petits services officiels, disponibles pour arm64 : une page nginx (port 8080), `traefik/whoami`
(port 8081) et redis avec un healthcheck dont nginx attend qu'il soit prêt.

Sur le téléphone :

1. Télécharger [`pocketdock-demo.tar.gz`](pocketdock-demo.tar.gz?raw=1).
2. Tableau de bord → **Projets Compose** → **Importer un .tar.gz**, puis **up**.
3. Ouvrir http://127.0.0.1:8080 et http://127.0.0.1:8081 dans le navigateur.

L'archive se régénère avec `tar czf pocketdock-demo.tar.gz -C demo .`.
