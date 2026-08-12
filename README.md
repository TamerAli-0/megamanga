# MegaManga

Manga, manhwa and manhua sources for [OmniStream](https://github.com/TamerAli-0), packaged as a
single `.omni` plugin.

## Adding the repo in OmniStream

Settings → Plugins → Repositories → Add, then paste:

```
https://raw.githubusercontent.com/TamerAli-0/megamanga/builds/repo.json
```

Install **MegaManga** from the browser tab. The app ships with no manga sources; everything
comes from this plugin.

## Building

```bash
./gradlew :megamanga:packagePlugin
```

The artifact lands in `dist/megamanga.omni`.

The plugin compiles against the OmniStream plugin ABI, published from the app repo with:

```bash
./gradlew :plugin-api:publishToMavenLocal :plugin-api-android:publishToMavenLocal
```

Toolchain must match the app (AGP 8.13.2 / Kotlin 2.2.21 / Gradle 9.3.1) — the ABI jars carry
Kotlin 2.2 metadata and an older compiler cannot read them.

## Layout

- `main` — source
- `builds` — published `megamanga.omni` and `repo.json`

## Credits

Parsing logic comes from [kotatsu-parsers](https://github.com/KotatsuApp/kotatsu-parsers)
(Apache-2.0).
