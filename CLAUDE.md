# Instructions pour les agents (Claude, Codex, Gemini…)

## Tickets : ailleurs que dans ce dépôt

Ce dépôt est **public**. Les tickets du projet (format coagent-studio : `TSK-XXX.md`, index, journal,
timeline, Decision Records) vivent dans le dépôt **privé** `luraku/Tickets-coagent`, dossier
`pocketdock/` :

- `pocketdock/tickets/TSK-XXX.md`, `pocketdock/tickets/index.md`, `pocketdock/tickets/log.md`
- `pocketdock/.studio/timeline.jsonl`, `pocketdock/.studio/templates/ticket.md`, `pocketdock/.studio/START-HERE.md`

Règles :
- **Ne jamais recréer `tickets/` ni `.studio/` dans ce dépôt.** Créer, mettre à jour et clore les
  tickets dans `luraku/Tickets-coagent` (cloner ce dépôt à côté de celui-ci), et y commiter.
- Numéro du prochain ticket : forcer la base 10 (avec `$((n + 1))`, bash lit `030` en octal = 24) :
  `n=$(ls pocketdock/tickets | sed -n 's/^TSK-\([0-9]*\)\.md$/\1/p' | sort -n | tail -1); printf 'TSK-%03d\n' $((10#$n + 1))`
- Référencer les tickets dans les messages de commit de ce dépôt (`TSK-028 : …`), sans copier leur
  contenu : il peut être interne.
- Ne pas mettre dans ce dépôt d'informations privées (jetons, mots de passe réels, données de
  joueurs, contenu des tickets).

## Projet

- Nom public : **Pocketdock**. Identifiants techniques inchangés : paquet `dev.androdock`, classes
  `Androdock*`, pont JS `AndrodockBridge`. Dépôt GitHub : `luraku/Pocketdock` (ex-`luraku/Androdock`, l'ancien nom redirige).
- Lire `README.md` (architecture, build, tests) et `docs/control-api.md` (API de pilotage).
- Vérifications avant de pousser : `python3 scripts/fetch-native.py` puis
  `./gradlew testDebugUnitTest assembleDebug` ; le tableau de bord web se teste sans appareil avec
  `python3 scripts/dashboard-mock/server.py` et un navigateur.
- La CI e2e tourne sur un émulateur ; le scénario Compose `forever` (1 à 2 h) ne se lance qu'à la
  main (Actions → Build APK → Run workflow → `forever`).
