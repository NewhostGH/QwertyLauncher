# QwertyLauncher

A terminal-style Android home screen made for phones with a physical QWERTY keyboard
(built on a Unihertz Titan 2). Text on a strict character grid, pictures in coloured ASCII,
and everything reachable from the keyboard.

- Type on home to search apps, contacts, shortcuts and files, or run a command (`help` lists them)
- Tiles: clock, alarm, weather, sun and moon, agenda, favourites, suggestions, notifications,
  music, battery, system, network, sensors, news, todo, games
- Sir Alex of Quertia, a herald who lives on your home screen
- Optional AI assistant: self-hosted Ollama, or Anthropic, OpenAI, Gemini or OpenRouter

## Modules

Every feature is a module, shipped as a zip (`.qmod`):

```
module.json    {"id": "games", "name": "games", "version": 1, "api": 1,
                "entry": "com.example.GamesModule", "permissions": []}
classes.dex    the module's own code, built against :core
```

The launcher loads it at run time, against its own core, Kotlin, coroutines and androidx.core.
A module can't bring Android resources or manifest entries: it can use only the permissions and
components the launcher declares. Build one from a `:feature:*` project with
`./gradlew :feature:<name>:signModule` (output in `feature/<name>/build/qmod/signed/`), then add
it with the `module import` command. `signModule` signs it with the release key in
`release.properties`; `packModule` makes the same zip unsigned.

Imported modules run with all the launcher's permissions, so `module import` checks who signed
one: the launcher's own key, the same key as the version installed, a key it hasn't seen (shown
by fingerprint), or a different key than before (a warning). It refuses a zip changed after
signing, and an unsigned one unless "allow unsigned modules" is on in config. Only install
modules you trust.

## Building

```
./gradlew assembleRelease
```

Needs JDK 17+ and the Android SDK (compileSdk 36). Release builds are signed only when a
`release.properties` (storeFile, storePassword, keyAlias, keyPassword) is present; it and the
keystore are never committed.

## Contributing

Branch, commit and push rules, and the git hooks that check them: [CONTRIBUTING.md](CONTRIBUTING.md).

## License

GPL-3.0-or-later. See [LICENSE](LICENSE).
