# Contributing

## Setup

Turn the git hooks on once in every terminal you use git from (this checks the rules below):

```
sh .githooks/install.sh
```

Optionally, where Gradle runs (Code On The Go's terminal), run the unit tests before every push:
`git config qwerty.pushTests true`.

## Branches

- `main` always builds and is never committed to directly. Work happens on a branch.
- Name branches `type/short-name`, lower case with hyphens, using a commit type from below:
  `feat/safe-mode`, `fix/weather-timeout`, `docs/module-guide`.
- `main` is a straight line. Bring a branch up to date with `git rebase main`, then join it by
  fast-forward:

  ```
  git switch main
  git merge --ff-only feat/safe-mode
  git branch -d feat/safe-mode
  ```

## Commits

[Conventional Commits](https://www.conventionalcommits.org/):

```
type(scope): subject

Body: what changed and why, wrapped at 100 characters.
```

- **type**: `feat`, `fix`, `perf`, `refactor`, `style`, `test`, `docs`, `build`, `chore`, `revert`.
- **scope** (optional): `core`, `app`, a feature module (`weather`, `ai`, `herald`, ...), or
  `build`, `deps`, `hooks`, `fdroid`, `release`. Several are separated by commas: `fix(ai,news): ...`.
- Add `!` after the scope for a change that breaks modules built against the old core
  (a `ModuleApi.VERSION` bump): `feat(core)!: let widgets take key input`.
- **subject**: imperative, lower case, no full stop; the whole first line is at most 72 characters.
- The second line is blank.
- One logical change per commit.

## Never committed

Signing secrets (`release.properties`, `*.keystore`, `*.jks`), build output (`build/`, `*.apk`,
`*.dex`, `*.qmod`), `build-log.txt`, files over 1 MB, merge conflict markers, and anything that
looks like an API key or token.

## Pushing

- `main` is never force-pushed or deleted.
- Pushed branch names follow the rules above.
- Every commit being pushed is checked against the commit rules again, so one made with
  `--no-verify` is caught here. Fix it with `git rebase -i`.

## Releasing

GitHub Actions builds every push (`.github/workflows/ci.yml`: unit tests, a debug APK, lint).
A version tag publishes a release (`.github/workflows/release.yml`):

1. Raise `versionCode` and `versionName` in `app/build.gradle.kts`, and write
   `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
2. Join it to `main`, then tag that commit `v<versionName>` and push the tag:

   ```
   git tag -a v1.1 -m "QwertyLauncher 1.1"
   git push origin main v1.1
   ```

The release gets the APK and the signed modules, signed with the release key from the
repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and
`RELEASE_KEY_PASSWORD`. F-Droid picks up new tags by itself (`UpdateCheckMode: Tags` in
`fdroid/com.newhost.qwertylauncher.yml`).
