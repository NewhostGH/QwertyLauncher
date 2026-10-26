# Changelog

Every release, newest first. The format follows [Keep a Changelog](https://keepachangelog.com/),
and versions are the app's `versionName`. Each release's short notes for F-Droid are in
`fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters at most).

## [Unreleased]

## [1.0.1] - 2026-10-26

### Fixed

- The bundled modules build the same everywhere, so F-Droid's build matches the release APK.

## [1.0] - 2026-10-25

First release.

### Added

- A terminal-style home screen on a strict character grid, with a choice of monospace fonts.
- Type on home to search apps, contacts, app shortcuts and files, or run a command (`help`
  lists them): calculator, timers, alarms, torch, web search and more.
- Tiles: clock, next alarm, weather, sun and moon, calendar agenda, favourites, app
  suggestions, notifications with reply, music, battery, system, network, sensors, news, todo.
- Games: snake, tetris and 2048.
- Sir Alex of Quertia, a herald who wanders the home screen.
- An optional AI assistant: self-hosted Ollama, or Anthropic, OpenAI, Gemini or OpenRouter.
- Every feature is a module; `module import` adds one from a zip, checks who signed it, and
  a module that keeps crashing the launcher is held back.
- `config` exports and imports settings.
- Screen reader support: panes read their keys once as they open, toggles say their state,
  and abbreviations are spoken as meant.

[Unreleased]: https://github.com/NewhostGH/QwertyLauncher/compare/v1.0.1...HEAD
[1.0.1]: https://github.com/NewhostGH/QwertyLauncher/compare/v1.0...v1.0.1
[1.0]: https://github.com/NewhostGH/QwertyLauncher/releases/tag/v1.0
