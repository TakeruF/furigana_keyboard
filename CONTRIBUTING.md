# Contributing

Thanks for your interest in Furigana Keyboard. This document covers the things
that are easy to get wrong in this repository: Git LFS, the licensing of the
bundled dictionary data, and how to run the same checks CI runs.

## Licensing of contributions

This project's own source code is licensed under the Apache License 2.0. By
submitting a contribution, you agree that it is licensed under the same terms
(inbound = outbound). Do not paste code from sources whose license is unknown or
incompatible.

Not every file here is Apache 2.0. `NOTICE` records the exact breakdown, but the
two that matter most in review:

- `app/src/main/cpp/zinnia/` is third-party BSD code. Keep the existing
  copyright headers and `COPYING` intact.
- `app/src/main/assets/reading.db` is derived from EDRDG data and stays under
  CC BY-SA 4.0. Anything derived from it inherits CC BY-SA and needs EDRDG
  attribution. Do not relicense it or fold its contents into Apache-licensed
  source.

Never commit signing keys, `key.properties`, or the dictionary-update private
key. `.gitignore` already excludes them; do not override it.

## Git LFS is required

`reading.db` is stored in Git LFS. Install LFS before cloning, or the file will
land as a small text pointer and the app will fail at runtime:

```
git lfs install
git clone <repo-url>
```

If you cloned before installing LFS, run `git lfs pull`.

## Running the checks

CI (`.github/workflows/ci.yml`) is the source of truth. It runs three jobs, and
you can reproduce them locally:

Android and reading-data tools:

```
python3 -m unittest discover -s tools/tests -v
./gradlew :app:testPlayDebugUnitTest :app:testDirectDebugUnitTest \
          :app:lintPlayDebug :app:lintDirectDebug
```

About site (from `about/`):

```
npm ci
npm test
npm run lint
```

iOS (from `ios/`) needs XcodeGen; the Xcode project is generated, not committed:

```
xcodegen generate
```

Then build and test the `FuriganaKeyboard` scheme against an iOS simulator.

See the README for build prerequisites and for regenerating reading data.

## Pull requests

- Keep changes focused, and match the surrounding code style.
- Android and iOS share behavior. If you change conversion or recognition logic
  on one platform, say in the PR whether the other platform needs the same
  change.
- Regenerating `reading.db` or `handwriting-ja.model` produces large binaries.
  Open an issue before submitting a PR that rewrites them, so we can agree on
  the approach.
- Note any change to bundled third-party assets, so `NOTICE` and the licenses in
  `app/src/main/assets/licenses/` can be kept accurate.

## Reporting bugs and vulnerabilities

Use GitHub issues for bugs. Do **not** file security vulnerabilities publicly;
follow `SECURITY.md` and email `support@hanlu.app`.

When reporting an input or recognition bug, do not include passwords or text you
entered into other apps.
