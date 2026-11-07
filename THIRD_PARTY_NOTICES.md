# Logiciels tiers

Pocketdock est distribué sous la licence Apache 2.0 (voir [LICENSE](LICENSE)). Il s'appuie sur des
logiciels tiers, distribués sous leurs propres licences, listés ici.

Le code de Pocketdock **lance** les programmes natifs ci-dessous comme des processus séparés
(QEMU, proot, docker). Il ne les lie pas. Leurs licences s'appliquent donc à ces programmes et
à leur redistribution dans l'APK, pas au code de Pocketdock.

## 1. Programmes natifs embarqués dans l'APK

Récupérés par [`scripts/fetch-native.py`](scripts/fetch-native.py) depuis les paquets
[Termux](https://termux.dev) (`packages-cf.termux.dev`) et depuis le client Docker statique
officiel. Ils sont placés dans `jniLibs/<abi>/` sous des noms `lib*.so`.

| Composant | Rôle | Licence | Sources |
|---|---|---|---|
| **QEMU** (`qemu-system-aarch64-headless`) | VM du moteur Docker | GPL-2.0 (certaines parties sous LGPL/BSD/MIT) | https://www.qemu.org/download/, recette Termux : https://github.com/termux/termux-packages |
| **proot** | machines Linux | GPL-2.0 | https://github.com/termux/proot |
| **Client docker** (`docker` 27.5.1, binaire statique) | terminal Docker, `docker` dans les machines | Apache-2.0 | https://github.com/docker/cli |
| Dépendances de QEMU et proot : glib, pixman, libslirp, zlib, zstd, pcre2, libiconv, libandroid-support, libtalloc… | bibliothèques | LGPL-2.1, MIT, BSD, Zlib… selon le composant | https://github.com/termux/termux-packages |

La liste **exacte** (paquets, versions, nom et empreinte SHA-256 du `.deb` d'origine, fichiers de
l'APK concernés, textes complets des licences inclus dans chaque paquet) est **générée à chaque
build**. Elle est :

- embarquée dans l'APK (`assets/licenses/native-<abi>.json`) et affichée dans l'application :
  **Moteur → Licences et mentions** ;
- publiée par la CI dans l'artefact **androdock-apk** (`THIRD_PARTY_NATIVE-<abi>.md`).

**Code source (GPL / LGPL).** Les binaires sont ceux des paquets Termux, sans modification. Les
recettes de construction et les correctifs appliqués par Termux sont dans
[termux/termux-packages](https://github.com/termux/termux-packages), les sources amont sur les
sites indiqués pour chaque composant. Pour obtenir les sources exactes correspondant à un APK
donné, ouvrez une issue sur ce dépôt en indiquant la version de l'APK.

## 2. Bibliothèques de l'application (Java / Kotlin)

| Bibliothèque | Licence |
|---|---|
| Kotlin (stdlib), kotlinx.coroutines 1.9.0, kotlinx.serialization 1.7.3 | Apache-2.0 |
| AndroidX : core-ktx, activity-compose, lifecycle, navigation-compose | Apache-2.0 |
| Jetpack Compose (BOM 2024.12.01), Material 3, Material Icons | Apache-2.0 |
| OkHttp 4.12.0, Okio | Apache-2.0 |
| Apache Commons Compress 1.27.1 (et Commons IO, Lang, Codec) | Apache-2.0 |
| XZ for Java 1.10 | 0BSD |
| Termux `terminal-view` et `terminal-emulator` v0.118.0 | Apache-2.0 |

Bibliothèques de test (non distribuées) : JUnit 4 (EPL-1.0), MockWebServer (Apache-2.0),
kotlinx-coroutines-test (Apache-2.0).

## 3. Téléchargés par l'appareil, non distribués dans l'APK

- **Alpine Linux** : noyau `linux-virt` (GPL-2.0), initramfs, modloop et paquets, dont Docker Engine
  (Apache-2.0), containerd, runc… Ils sont téléchargés depuis `dl-cdn.alpinelinux.org` au premier
  démarrage du moteur. Sources : https://alpinelinux.org et https://gitlab.alpinelinux.org/alpine/aports.
- **Rootfs des machines** (Alpine minirootfs, Ubuntu Base, ou URL fournie par l'utilisateur) :
  téléchargés à la création d'une machine, sous les licences de chaque distribution.
- **Images Docker** : téléchargées par l'utilisateur, sous leurs propres licences.

## Marques

Docker est une marque de Docker, Inc. OrbStack est une marque de ses détenteurs. Android est une
marque de Google LLC. Pocketdock n'est affilié à aucune de ces sociétés.
