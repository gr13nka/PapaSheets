# PapaSheets — construction work log

Offline Android app (Kotlin + Compose). A site foreman keeps a log on site: date + contractor +
location + work type + photo. It replaced a Google Sheets spreadsheet that choked on embedded
photos and was awkward to use from a phone. There is no network at all and no server; everything
lives on the phone.

This file is the shared instruction set for every coding agent (Codex reads `AGENTS.md`, Claude Code
reads `CLAUDE.md`, which only imports this file). Edit this file, not `CLAUDE.md`.

The user does not write code — building, installing and verifying is done by the coding agent.

## Scope: Android only

**Only the Android app is being developed.** An iOS port (Kotlin Multiplatform + Compose
Multiplatform) was started on the separate branch `kmp-port` and is paused. On `main`:

- don't add KMP source sets, `expect`/`actual`, iOS targets or multiplatform dependencies;
- don't refactor code "to prepare for iOS" — Android APIs (Room, SAF, `SharedPreferences`,
  FileProvider) are used directly and that is fine;
- don't touch the `kmp-port` branch or its worktree unless the user explicitly asks.

## Localization

The UI is localized into Russian and English. A fresh install follows the system language (other
locales fall back to English); an explicit choice is stored by AndroidX AppCompat and changed in
"Settings → Language". An install upgraded from a version without localization (1.3 and earlier) gets
an explicit Russian on its first launch — the one-off step `applyFirstLaunchLanguage`, with a marker in
its own prefs, so it does not override a later choice of "System default".
Stored names of journals, fields, contractors and record values are not translated on switching.
Exception text never reaches the UI: the failure cause is a type (`XlsxImportReason`,
`BackupFormatReason`), the UI layer picks a string resource for it, and the exception itself is written
to `AppLog`.

User-facing strings live in `app/src/main/res/values/strings.xml` (English) and
`values-ru/strings.xml` (Russian); every new string goes into both. Russian UI labels quoted below
(e.g. «Ф», «Архив») are what the foreman actually sees.

## Build, test, install

```bash
./gradlew :app:testDebugUnitTest :exportkit:test :matrixgrid:test   # JVM tests
./gradlew :app:connectedDebugAndroidTest                            # needs an emulator/phone
./gradlew :app:assembleDebug                                        # APK for yourself

./scripts/reinstall-phone.sh          # fresh build onto your own phone (data is kept)
./scripts/reinstall-phone.sh --clean  # same, but from scratch
./scripts/build-apk-for-sharing.sh    # signed release into dist/ — what gets sent to people
```

Installing on a phone requires "USB debugging" and confirming the prompt on the phone's screen.

### Environment pitfalls — read before "fixing a bug"

- **Gradle output may be cut by a filter.** On machines with RTK (the Claude Code hook rewrites shell
  commands through `rtk`), "BUILD FAILED" without details is no mystery: rerun as
  `rtk proxy "./gradlew ... --console=plain"` to get the real error text. Without RTK, just add
  `--console=plain`.
- **`--tests` does not work** with the `:module:test` task (it is an aggregate). Use
  `testDebugUnitTest`.
- **`build/test-results` directories accumulate stale XML** from deleted and renamed tests. Counting
  tests from them without `rm -rf */build/test-results` overstates the number.
- **A killed daemon corrupts the KSP cache and incremental compilation.** The symptom is misleading:
  dozens of `Unresolved reference` errors for files of the same module that are definitely there. The
  cure is `./gradlew --stop` and `rm -rf app/build/kspCaches app/build/tmp/kotlin-classes`, not a code
  change.
- **Memory.** The Mac can't hold two agents and an emulator at once — the gradle daemon dies
  ("daemon disappeared"), and at worst the system crashes. One agent at a time.
- **AVD `Pixel7` is corrupted**: the APK installs with "Success", and a second later the system can't
  find the package with an I/O error. `-wipe-data` doesn't help. Use `Pixel7smoke`.
- **"SDK location not found" ≠ SDK not installed.** On the linux machine it lives in
  `~/.local/opt/android-sdk` (plus a symlink `~/Android/Sdk` to the same place), there is no
  `local.properties` in the repo, and `ANDROID_HOME` doesn't reach every shell. The cure is the prefix
  `ANDROID_HOME=~/.local/opt/android-sdk ./gradlew ...`, not reinstalling the SDK. Without this
  variable the scripts in `scripts/` fall back to the macOS path `~/Library/Android/sdk` and don't find
  `adb`.

## Architecture

Three modules — the boundary is drawn by dependencies, not by layers:

| Module | What's inside | Dependencies |
|---|---|---|
| `:app` | Room, repositories, domain, Compose UI, photos | both others |
| `:matrixgrid` | zoomable matrix engine, a single Canvas renderer | only compose ui/foundation |
| `:exportkit` | xlsx/CSV/backup, reading foreign xlsx | pure JVM, `kotlinx-serialization-json` |

`:exportkit` has no Android — hence its fast JVM format tests. `:matrixgrid` knows nothing about Room,
Coil or files: from outside it accepts a ready model and a thumbnail source.

DI is a hand-written `AppGraph` in `App.kt`, everything via `by lazy`, no Hilt. In Compose it is
provided through `LocalAppGraph`.

### Matrix

A single Canvas, not a tree of composables: at strong zoom-out, thousands of compose nodes are a dead
end. Three levels of detail (`Lod`): full cell → micro-thumbnail → "month picture" as colored blocks.

Two invariants everything rests on:

- **The X axis is a uniform grid.** Sub-columns are the same in every contractor group, so a group's
  width is a model constant, and "coordinate → group" stays an O(1) division. Give different
  contractors different sub-column sets and virtualization and hit-testing fall apart.
- **The Y axis depends on the level.** At LOD0 a row grows to fit its text (`RowMetrics.Variable`); at
  far levels the grid is always uniform — otherwise the "month picture" stops reading as a summary.
  All Y methods take the zoom and get metrics via `metrics(zoom)`, the single source; otherwise the
  renderer and gestures diverge at the threshold.

Text measurement happens on model change, not in a gesture frame, and through the same
`CellTextCache` the renderer then uses — so the measurements aren't extra, they are moved ahead of
time.

**The place you stopped at survives closing the app.** The viewport position is a value
(`MatrixViewport`: pan + zoom); `MatrixState` takes it in its constructor and returns it via
`viewport()`. There is nothing to clamp it with at that point (no geometry yet); the first
`updateViewport` pulls it into bounds — the same path that restores after screen rotation. The
position is stored by `LastPlace` (`SharedPreferences`, per journal — the second prefs consumer after
`ExportFolder`), which also remembers the last opened journal that `AppNav` navigates to on launch.
Two rules:

- **Read `viewport()` only outside composition** (lifecycle callback, `onDispose`). From a composition
  body the screen subscribes to snapshot floats and recomposes on every scroll frame.
- **The position is saved only in the default layout** (`JournalQuery.isDefaultMatrixLayout`: matrix,
  empty filter). View and filter live in screen memory and reset on launch, so a viewport captured
  under a filter would point at different days tomorrow. Date order in the matrix is not part of this —
  it is always ascending; column sorting in "List" doesn't affect it.

### Table-first entry

New tables offer a simple photo/description layout or reuse of a previous table's structure.
The unfiltered matrix shows every day of its month, even without records. Empty slots are view
models, not database records. Header taps edit groups/columns; body taps carry a field/photo target
into the record sheet while prefilling date and group. Photo controls never launch automatically.
An occupied photo cell reserves two slots, so its empty second slot remains tappable.

### Record fields — data, not code

The set of columns is defined by the `field_defs` table, not by constants. "Location" and "Work type"
are rows in it like any user field. This gives for free: autocomplete (a field flag), filtering and
sorting by any column, custom columns in xlsx and CSV.

Invariants:

- **No empty values are stored.** `value.isBlank()` → no row. An empty string and a missing row are the
  same thing, so "clear the field" stays a deletion and the table doesn't accumulate junk. The rule
  lives in `RecordRepository` and is repeated in the migration and in the backup upgrade — three places
  that must agree.
- **A record's value set is replaced wholesale** (delete before insert). Otherwise a field the user
  cleared comes back on the next import: the archive has no row, and upsert doesn't delete.
- **Structure belongs to a table.** Groups and fields carry `journalId`; new tables get fresh ids.
  Schema 8 and backup format 7 upgrade legacy global definitions using the same deterministic
  `LegacyTableStructure.id` mapping. `BuiltInFields` only describes historical formats; there is no
  global seed. Migration and backup imports must agree on ids to avoid duplicate columns.
- **Copy structure, never content.** `TableStructureRepository` transactionally copies groups,
  fields (including archived definitions), presets and value colors, but no records or photos.
  Multiple named tables may share a month. Ownership is checked when writing record values.
- **A field has no machine key.** It had one; it was never useful and actively harmful: a UNIQUE index
  on it broke backup merging (on conflict Room does an UPDATE by id, doesn't find the row and silently
  loses the definition). Don't introduce such a key again.

### Settings

Two screens instead of one menu, split by scope: ⚙ on the journal list opens **"Settings"** — what
concerns the whole app (language, backup, import, logs); ⋮ in a journal opens **"Table settings"**
(`settings/{journalId}/table`) — that table's name and structure. Groups and fields belong to that
journal; editing a copied table never changes its source. Within a table, every group still repeats
one identical column layout, preserving constant-width grid geometry.

- **The UI says "group", the code says "contractor".** Renaming the strings didn't touch
  `ContractorEntity`, `ContractorRepository` or the CSV header — changing identifiers for the sake
  of UI wording wasn't worth it. Backup format 7 adds ownership, not a terminology rename.
- **Column width is a preset, not dp.** `ColumnWidth` (NARROW/MEDIUM/WIDE/EXTRA_WIDE =
  56/112/168/224) gives the foreman a named slider instead of picking pixels; `field_defs.columnWidthDp`
  still stays a dp number, and a value outside the presets (old backup, manual input) is shown as the
  nearest one and rewritten only by an explicit slider move — otherwise merely opening the editor would
  round someone else's value.
- **The "Group settings" preview is a real `MatrixView`, not a drawing of one.** `buildGroupPreview`
  uses the same `FieldDefEntity.toGridField()` and value coloring as `buildGridModel`, so width, text
  wrapping and color in the preview physically cannot diverge from the journal.
- **A field edit is written immediately**, via `FieldRepository.edit` — a screen without "Save", by the
  same rule as the record form ("leaving the form is saving"). Each operation re-reads the row from the
  DAO under one `Mutex`: edits arrive faster than the screen gets the updated list back, and a draft
  from a stale copy would overwrite the whole field on top of what was just typed.

### Value colors

The foreman marks a field value with a color ("Plaster" — orange), and in the matrix that field's
sub-column is filled with that color. The color is assigned right in the record form — the circle to
the right of the field.

- **The color key is the pair (field, value)**, table `field_value_colors`. The color belongs to the
  value, not the record: all records with "Plaster" are colored the same, and recoloring them happens
  once. It works as a key because values are already normalized on input
  (`RecordRepository.replaceValues` trims and stores no blanks); `FieldValueColorRepository.setColor`
  must trim the same way, otherwise the color lands on a string no record has and silently never shows.
- **Any field can have colors, not only "Work type".** This follows directly from sub-column fill: each
  one is colored by its own value, and the question "which field is the main one" never comes up — not
  in the model, not in the renderer, not in the form. There is no separate flag on `field_defs`, and
  none should be added.
- **In `GridCell` the color arrives as a ready palette index** (`valueColors` parallel to `values`),
  computed in `buildGridModel`. The renderer walks all visible cells every gesture frame, and looking up
  the color by value text there is not allowed — for the same reason text measurement was moved out of
  the frame.
- **The fill is drawn even for fields hidden at LOD1** (`showAtCompactLod = false`, like "Work type"):
  the flag holds back text measurement and drawing — the expensive part — not the sub-column itself.
  Exactly where the text can no longer be read, color remains the only cue. LOD2 is untouched: it is the
  per-contractor "month picture", with no sub-columns.
- **One palette for contractors and values** (`MatrixPalette` in `:matrixgrid`, formerly
  `ContractorPalette`). They differ in placement and opacity: contractor tint 6% over the whole cell,
  value fill 22% over its sub-column (`MatrixColors.valueFillAlpha`; the list duplicates the same
  constant in `RecordList` — they must match).
- **Renaming a value doesn't carry the color over** — the row becomes an orphan, the new spelling stays
  uncolored. `field_presets` live the same way: the link is by text, and tracking its edits would be
  separate machinery for a rare case. Orphans are harmless; `ON DELETE CASCADE` removes them with the
  field.
- **Colors don't go into xlsx.** `StylesXml` has no fills at all (spec, M6), and the writer tests are a
  byte-for-byte regression gate. Color is a way to read the matrix on a phone; it has no business in the
  file for the client.

### Record form

`RecordSheet` is a sheet over the matrix or the list; its fields come from `field_defs`, so a field
the foreman creates appears in the form by itself.

- **Leaving the form is saving.** Swipe down, "Back", tapping the scrim and the "⌄" chevron write the
  record to the DB as is, without requiring mandatory fields: on site the form gets collapsed mid-entry,
  and a started record is worth more than nothing. Mandatory fields are still required by the explicit
  "Save" — a reminder costs nothing there. What to do is decided by `recordCloseAction` next to
  `validateRecord`, using its result: "what counts as a filled record" must live in one place. Two
  exceptions — an empty form has nothing to write (otherwise a missed tap on a matrix cell would spawn
  empty rows), and without a contractor the record has nowhere to live (FK to `contractors`), so the
  form stays open with highlighting. For the same reason the dismiss button is labeled **"Don't save"**,
  not "Cancel": it and the swipe do opposite things, and the label must show that.
- **After saving, the form collapses into a bar at the bottom** (`MinimizedRecordBar`) instead of
  disappearing: the foreman collapses it to check the table, and shouldn't then have to hunt for the
  started record in the matrix. Tap expands it, "×" dismisses the bar (nothing to lose — the record is
  already in the DB). The bar lives in the `Scaffold`'s `bottomBar`, so it lifts the FAB and the content
  above itself.
- **After the first save the host swaps the form mode `Create` → `Edit`** (`JournalScreen`). Without
  this, collapsing the same form a second time would create a duplicate: `persisting` only guards
  against concurrent calls, and `recordId` in `RecordEditViewModel` is immutable — "the mode is set once
  when the ViewModel is created" still holds; the key changes, not the field. That's why
  `RecordRepository.createRecord` returns the id: finding the just-created record by content is
  impossible, content isn't unique.
- **Opening any other record clears the collapsed state; deleting the collapsed record removes the
  bar.** Otherwise the bar would show someone else's caption or expand into a form with nothing to
  load — an endless spinner.
- **A repeated DB write call is ignored** (`persisting` in `RecordEditViewModel`). Triggering close twice
  in a row is easy — a second "Back" before the sheet slides away — and on create it would add a second
  identical record.
- **Input height follows the field's own `maxLines`**, not "multiline means tall": "Location" (2) takes
  one line and grows to two; the three-line block is left to uncapped fields like "Work type". The cap
  is read the same way as in the matrix (`GridField.lineCap`): `<= 0` — none.
- **A contractor is created without leaving the form** — the "+ Create new group" item at the bottom of
  the list opens the shared `ContractorDialog` (`ui/common`, the second caller is "Column groups",
  `ContractorsScreen`) and immediately selects the new one. That's why `ContractorRepository.create`
  returns the id: finding yourself by name is unreliable, names aren't unique.

### Export and import

- **xlsx is written with our own ZIP+XML**; Apache POI was rejected: bulk, `javax.xml` conflicts, OOM on
  hundreds of photos. The order of elements inside `<worksheet>` is fixed by the OOXML schema —
  reordering breaks opening in Excel. Column widths are derived from dp via `Widths`; there must be no
  magic numbers.
- **Reading foreign xlsx** (`exportkit/xlsx/read`) supports what we don't write ourselves:
  `sharedStrings`, dates as Excel serials, `twoCellAnchor`. The ZIP is opened via `ZipFile`, not as a
  stream: media come after the sheet, random access is needed. SAX is closed to external entities — the
  file comes from outside.
- **Sheet layout is known in one place** — `MatrixSheetLayout`, shared by writer and reader. It is a
  value (number of «Ф» (photo) columns and number of fields), not a set of functions: with the second
  photo slot, the number of «Ф» columns stopped being a format constant. Our own files are written with
  `PHOTO_COLUMNS_PER_GROUP`, but **old ones with a single «Ф» are still read**: the reader determines
  their count from the header (length of the run of «Ф» labels) rather than assuming it. The backward
  compatibility gate is `XlsxReaderReferenceTest` on the client's reference file (one «Ф»): it must pass
  untouched.
- **xlsx import is two-phase**: the preview is computed from the same plan that is then executed, so
  the dialog cannot lie. Matching is extracted into `XlsxImportPlanner` without Android and covered by
  tests. Import creates a new table unless the user explicitly selects an existing destination;
  changing the destination recomputes the preview. A matching month does not imply a matching table.
- **Export overwrites one file per table and format in a remembered folder.** The folder is chosen once
  (`OpenDocumentTree` + `takePersistableUriPermission`, stored in `SharedPreferences`); from then on the
  file binding is keyed by folder URI, journal id and MIME type. `ExportFileNames` sanitizes names
  and adds a numeric suffix on collision, so equally named tables cannot overwrite each other or
  claim an existing unbound file. Repeated export keeps the binding even after a table rename.
  The previous version goes into an
  «Архив» ("Archive") subfolder before being overwritten (last 3 copies); the stream is opened in
  `"wt"` mode, not `"w"` — otherwise an export shorter than the previous one would leave the tail of the
  old ZIP. All of SAF is hidden behind `ExportFolder`; rotation is the pure function `ExportArchive`
  under unit tests. The `.psbackup` backup meanwhile stays on `CreateDocument` with a dated name: its
  versions accumulate on purpose.
- **File type is determined by archive contents**, not by extension: both `.psbackup` and `.xlsx` are
  ZIPs, and names from the system file picker are unreliable.
- **There are two kinds of export: xlsx with photos and CSV.** The "xlsx without photos" option was
  meant for file size, turned out unneeded and was removed (2026-08-06) along with the writer's
  `photos = null` mode. A sheet without a drawing still comes out — from a journal that has no photos.
- **The complaint "there are no photos in the file" ≠ the photos are missing from the file.** Confirmed
  twice on real cases: the file is correct, the images are there, and the viewer doesn't draw them.
  Start the investigation with "what did you open it with?" and comparing `ext cx/cy` in drawing1.xml
  against the column width in `<cols>` — not with digging through the format.
- **A picture needs room in its cell.** Google Sheets on a phone doesn't draw an embedded picture that
  is cramped: the cell looks empty, though desktop Excel draws anything. "Fits exactly" doesn't count —
  a margin of ≥10px is needed (measured 2026-08-06: 5.33px too little, 10.33px enough,
  `PhotoFitTest.MIN_MARGIN_PX`). Hence two linked quantities, and both must include the
  `PHOTO_PADDING_PT` margin around the photo square: row height `PHOTO_ROW_HEIGHT_PT` and **the width of
  the «Ф» column, which is computed from it**, not from the square's side. While there was no horizontal
  margin, exactly the landscape shots disappeared — their long side runs across the column. The
  invariant is held by `PhotoFitTest`; the full analysis and the refuted earlier wording of the rule are
  in docs/evolution.md ("xlsx").

### Photos

`filesDir/photos/{medium,thumb}/<uuid>.jpg`, only metadata in the DB. Order of operations: file → DB
row; delete from DB → file; plus garbage collection at startup with a one-day grace period.
On import the file is written via `.part` with a rename: an interrupted write would otherwise leave a
truncated JPEG that `exists()` would accept as done — the photo broken forever, silently.

**Two photos per record, not one.** A record has two slots (`RecordEntity.photoId`/`photoId2`), both
with a UNIQUE index and FK SET NULL. The cap of "two" is deliberate (a piece of work needs a second
angle, more was never needed) and expressed by the schema, not by a code check; it lives in exactly
three places: the `RecordEntity` slots, `PHOTO_COLUMNS_PER_GROUP` in `MatrixSheetLayout` and
`MAX_PHOTOS_PER_CELL` in `:matrixgrid`. Above the storage layer photos are a gapless list
(`GridCell.thumbKeys`, `SnapshotCell.photoIds`, `ParsedCell.photos`): slots fill in order, "second
without first" is impossible. There is still no reference counting on a record's photo references —
both slots must be accounted for wherever photos are collected for deletion
(`RecordDao.photoIdsForJournal` takes a `UNION` of both; skipping the second would orphan files
forever).

**Screen and sheet diverge on purpose.** In the matrix — one «Ф» sub-column with one or two thumbnails
side by side (group width is a model constant; widening it for the photo count is not allowed, or
virtualization and hit-testing fall apart). In xlsx — two «Ф» columns: `oneCellAnchor` anchors a
picture to one cell with an explicit size, so the second photo needs a second column.

Garbage collection (`PhotoStore.collectGarbage`) does NOT count photos per record — it only reconciles
files with the `photos` table in both directions. It is not reference counting, and the second slot
doesn't affect it.

### Logs

A file log for offline diagnostics: `AppLog` (`ru.papasheets.logging`) writes to
`filesDir/logs/app.log` with size-based rotation (pure `LogRotationPolicy` under unit tests), mirrors to
logcat and intercepts uncaught crashes (delegating to the previous handler). The "Send logs" item in
"Settings" (⚙ on the journal list, "Diagnostics" section) concatenates the rotation into one txt and
hands it off via `ACTION_SEND` (FileProvider, `cache-path` `logshare`). The export path is
instrumented: start/finish, orphaned contractors, missing photo metadata and files — the log always
comes BEFORE the exception is thrown; error behavior is unchanged. `XlsxWriter.write` takes an optional
`log: (String) -> Unit`, which is forbidden from affecting the output bytes (pinned by a test); ZIP entry
time is fixed (1980-01-01), so the writer's output is reproducible byte for byte.

## Rules that must not be broken

Details are in `docs/evolution.md`; the essence here:

1. **Any entity change = version bump + a real `Migration` + a test in androidTest.**
   `fallbackToDestructiveMigration` is forbidden. DDL is copied from the exported schema in
   `app/schemas/`, not written from memory: Room checks the `identityHash` and crashes on first launch.
2. **Backup: must read backward, can't read forward.** All knowledge of old versions lives in
   `BackupUpgrade`; nothing above it knows about them. New DTO fields — only with a default value.
3. **Expectations in format tests are not adjusted to fit new code.** xlsx tests are a byte-for-byte
   regression gate; if one fails, figure out what changed in the output instead of editing the number.
4. **Clean up the schema before rollout, not after.** While there is no real data, a mistake costs a
   reinstall; with the first real journal the same change becomes an irreversible risk.
5. **Measure a third-party viewer's behavior on a device, don't derive it from docs.** The photo
   visibility rule in Sheets was once written here confidently and turned out wrong: the 2026-07-22
   measurement changed two quantities at once, and the effect was attributed to the wrong one. The cost
   was the released version 1.2, which didn't fix the bug. If a decision depends on how Sheets/Excel
   will render something — first an A/B file with ONE variable (the adb recipe is in
   `docs/device-checklist.md`), then choose the layout.
6. **Work ends with a documentation update, not the last code commit.** At the end of every task go
   through five files and update those the task touched:
   - `AGENTS.md` — if an invariant, the set of screens/formats changed, or a new rule appeared;
   - `docs/evolution.md` — if the schema, backup format or xlsx changed, or something new was learned
     about viewer behavior;
   - `docs/device-checklist.md` — if something appeared that can only be checked by hand on a phone;
   - `docs/spec.md` — if the feature set changed;
   - `docs/GUIDE.md` — if what a human reader has to know to build or use the app changed. Reference
     detail goes here, never into `README.md`.

   **Fix a refuted statement, don't append next to it.** A wrong paragraph left "for history" will be
   read as truth next time — which is exactly what happened with the visibility rule. If the entry
   matters as history, strike it through and say right next to it how exactly it turned out wrong.

## Signing key — don't lose it

`keystore/papasheets-release.keystore` + passwords in `local.properties` (`RELEASE_STORE_PASSWORD`,
`RELEASE_KEY_PASSWORD`, `RELEASE_KEY_ALIAS`). Both are outside git. Lose them and updating the app
installed on people's phones becomes impossible altogether — only uninstalling along with the data. A
backup copy is mandatory.

## androidTest subtleties

- **Don't close the database returned by `MigrationTestHelper`** — the rule owns it. Double-freeing
  the native handle crashes the whole instrumentation run without a java stack trace: tests "don't
  start", even though the code is fine.
- With Room, close Room itself, not the connection it handed out.
- **Foreign keys are off during migrations**: Room enables them in `onOpen`, and the framework calls
  `onOpen` after `onUpgrade`. The safety of recreating tables relies on this (`DROP TABLE` with keys on
  would perform a cascading DELETE). The assumption is implicit, so it is pinned by the test
  `migratedDatabase_opensWithRoom`.

## What remains unverified

- Zoom smoothness and row auto-height during a live pinch — an emulator with a software GPU doesn't
  show it.
- **Auto-height bloats matrix rows**: one long record stretches the whole row across all columns, and
  the overview is lost. Candidate for a cap of 6–8 lines of text.
- Importing the real "Copy of Июнь.xlsx" ("June") — the reference `docs/reference/iyun-xlsx/` has both
  `sharedStrings` and the photos themselves cut out, so the path "a photo from a foreign file ended up in
  a record" has never been run live.
- **Photo visibility in Microsoft Excel on a phone.** The room rule was measured on Google Sheets (Excel
  isn't installed on the test Nothing Phone), while the foreman views the journal in Excel on a Samsung.
  The 1.3 geometry is known to be no worse than before, but whether Excel has the same threshold is
  unverified.

## Documents

`README.md` is the landing page — short, image-led, no reference detail; depth for a human reader
lives in `docs/GUIDE.md`, and documentation edits go there rather than into the README.

- `docs/GUIDE.md` — building, installing, and how the app works, for a human reader.
- `docs/spec.md` — the original spec and milestones M0–M8.
- `docs/evolution.md` — rules for changing the schema, backup format and xlsx.
- `docs/device-checklist.md` — manual on-device checks.
- `docs/reference/iyun-xlsx/` — the client's real file, unzipped; the format reference.
