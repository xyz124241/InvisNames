# InvisNames

Paper-Plugin (1.21+). Gilt für jeden Spieler mit dem Effekt „Unsichtbarkeit“:

- **Nametag** über dem Kopf wird versteckt
- **Kill-Nachricht**: Name wird durch `-----` (oder Magic-Zeichen) ersetzt
- **Tabliste**: Rang + DeluxeTags-Tag + Ping bleiben, der Name fällt weg, der Kopf wird schwarz

## Bauen (einmalig)
Voraussetzung: Java 21 + Maven. Im Ordner:

    mvn package

Die fertige Datei liegt dann in `target/InvisNames.jar` -> in den `plugins`-Ordner des Servers.

## Benötigte Plugins (optional, aber für Rang/Tag nötig)
PlaceholderAPI, LuckPerms (`%luckperms_prefix%`), DeluxeTags (`%deluxetags_tag%`).
Falls ihr das Plugin „TAB“ nutzt, überschreibt es evtl. die Tabliste -> dann bitte melden.

## Schwarzer Kopf in der Tabliste (einmalig einrichten)
Minecraft erlaubt nur Skins, die von Mojang signiert/gehostet sind. Daher:
1. `black-skin.png` (liegt im Ordner) auf https://mineskin.org hochladen.
2. Das Ergebnis liefert **Value** und **Signature** (Texture Data).
3. In `plugins/InvisNames/config.yml` bei `black-skin.value` und `black-skin.signature` einfügen.
4. `/invisnames reload`

## Magic-Zeichen statt Bindestriche
In der config: `kill-message.replacement: "<obfuscated>XXXXX</obfuscated>"`

## JAR ohne Installation bauen (GitHub)
1. Kostenloses Konto auf github.com, dann "New repository" (privat reicht).
2. Den kompletten Inhalt dieses Ordners hochladen ("Add file" -> "Upload files"), auch den versteckten Ordner `.github`.
3. Tab "Actions" -> der Build läuft automatisch (ca. 1 Minute).
4. Auf den fertigen Lauf klicken -> unten bei "Artifacts" `InvisNames` herunterladen -> ZIP entpacken -> `InvisNames.jar`.
