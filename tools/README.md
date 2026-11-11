# Tools

- `reference/a747.py` — reference implementation of [docs/protocol.md](../docs/protocol.md):
  parsing, clean-up, track splitting, statistics and export. It is the oracle for both apps.
- `gen_fixtures.py` — builds synthetic logger images (no real tracks) and the golden outputs
  in [`fixtures/`](../fixtures). Run after any change to the reference implementation:

```bash
python3 tools/gen_fixtures.py        # no dependencies
git diff --stat fixtures/
```

Both apps' test suites read `fixtures/*/image.bin` + `meta.json` and must reproduce every
golden file byte for byte.

Other tools:

- `build_tz.py` — rebuilds `shared/timezones.bin` from timezone-boundary-builder (needs shapely).
- `i18n/build.py` — checks translations and writes `shared/i18n/*.json` and the Xcode string catalog.
- `i18n/android.py` — writes Android `strings.xml` for all languages from `shared/i18n`.
- `compose_screenshots.py` — lays raw Mac and Android screenshots out as the README pictures
  (`docs/images/hero.png`, `waypoints.png`): window shadows, phone frames (needs Pillow).
