# PapaSheets — the full guide

Everything the [README](../README.md) leaves out. The invariants that anyone changing the code
has to keep are in [AGENTS.md](../AGENTS.md); the rules for changing the database schema, the
backup format and the xlsx output are in [evolution.md](evolution.md).

- [Building and installing](#building-and-installing)
- [What the app does](#what-the-app-does)
- [The matrix](#the-matrix)
- [Records and fields](#records-and-fields)
- [Photos](#photos)
- [Export and import](#export-and-import)
- [Localization](#localization)
- [Project structure](#project-structure)
- [Diagnostics](#diagnostics)
- [Credits](#credits)

## Building and installing

JDK 17 and an Android SDK with API 35. There is no `local.properties` in the repository; on
Linux the SDK usually sits in `~/.local/opt/android-sdk`, and `ANDROID_HOME` does not reach
every shell, so prefix the command when Gradle reports "SDK location not found":

```bash
ANDROID_HOME=~/.local/opt/android-sdk ./gradlew :app:assembleDebug
```

| Command | What it produces |
|---|---|
| `./gradlew :app:assembleDebug` | `app/build/outputs/apk/debug/app-debug.apk` |
| `./gradlew :app:testDebugUnitTest :exportkit:test :matrixgrid:test` | the JVM suites |
| `./gradlew :app:connectedDebugAndroidTest` | the migration suites; needs a phone or emulator |
| `./scripts/reinstall-phone.sh` | a debug build installed on a connected phone, data kept |
| `./scripts/reinstall-phone.sh --clean` | the same, from scratch |
| `./scripts/build-apk-for-sharing.sh` | a signed release APK in `dist/` |

Installing on a phone needs USB debugging turned on and the prompt confirmed on the phone's
screen. Release signing reads `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD` and
`RELEASE_KEY_ALIAS` from `local.properties`; without them the release variant still builds,
unsigned, so the repository compiles for anyone without the secrets.

Note that `--tests` does not work against `:app:test`, which is an aggregate task — use
`testDebugUnitTest`. Stale XML accumulates in `build/test-results` from renamed tests, so
counting tests from those files without deleting them first overstates the number.

## What the app does

A foreman keeps a work log on site: for each day, each contractor group gets a record with a
location, a work type and up to two photos. It replaced a Google Sheets spreadsheet that
choked on embedded photos and was awkward to use from a phone.

There is no server and no account. `AndroidManifest.xml` declares no `INTERNET` permission;
the single permission it does declare is `WRITE_EXTERNAL_STORAGE`, capped at API 28, used to
put a copy of a camera original into the gallery on older releases.

Each table is a month. New tables either start from a simple photo-and-description layout or
copy a previous table's structure — groups, columns, presets and value colours, but never its
records. A month may hold several named tables.

## The matrix

The matrix is a single `Canvas` rather than a tree of composables: at strong zoom-out
thousands of Compose nodes are a dead end. It draws at three levels of detail — a full cell,
a micro-thumbnail, and the "month picture" of coloured blocks — and only the visible range of
rows and columns is drawn each frame.

Two things the renderer rests on:

- **The X axis is a uniform grid.** Every contractor group repeats the same sub-columns, so a
  group's width is a constant of the model and "coordinate → group" stays a division.
- **The Y axis depends on the level.** At the closest level a row grows to fit its text; at
  the far levels the grid is uniform, or the month picture stops reading as a summary.

Text is measured when the model changes, not inside a gesture frame, through the same cache
the renderer then reads.

The position you stopped at survives closing the app: the viewport is a value (pan and zoom)
stored per table in `SharedPreferences`, together with the last table you opened, which the
app navigates to on launch. It is only stored for the default layout — matrix view, no filter
— because a viewport captured under a filter would point somewhere else the next day.

## Records and fields

The set of columns is data, not code. "Location" and "Work type" are rows in the `field_defs`
table like any column a user adds, which is what gives autocomplete, filtering and sorting by
any column, and custom columns in the xlsx and CSV output for free.

- Blank values are never stored. An empty string and a missing row mean the same thing, so
  clearing a field stays a deletion.
- A record's values are replaced wholesale, or a field the user cleared would come back on the
  next import.
- Groups and fields belong to a table. Copying a table copies its structure and never its
  records; editing the copy never changes the source.

Leaving the record form is saving. A swipe down, Back, or a tap on the scrim writes the record
as it stands, without requiring the mandatory fields — on site a form gets collapsed
mid-entry, and a started record is worth more than nothing. The explicit **Save** button still
enforces them. Two things cannot be saved: an empty form, and a record with no group, which
has nowhere to live. The dismiss button is therefore labelled **Don't save**, not Cancel,
because it and the swipe do opposite things.

After a save the form collapses into a bar at the bottom rather than disappearing, so a
foreman who collapsed it to check the table does not have to hunt for the record again.

A field value can be given a colour, and the matrix fills that field's sub-column with it —
"Plaster" orange everywhere it appears. The colour belongs to the pair (field, value), not to
the record, so recoloring happens once. Any field can have colours; there is no "main" field.
Renaming a value does not carry its colour over.

## Photos

Two photos per record. The cap is expressed by the schema — a record has two photo slots, each
with a unique index — rather than by a check in code.

Files live in `filesDir/photos/{medium,thumb}/<uuid>.jpg` and only their metadata is in the
database. A camera shot is downsampled, rotated by its EXIF orientation, fitted into
1280×720 and written as JPEG; the original stays in the gallery. Imported files are written
through a `.part` name and renamed, because an interrupted write would otherwise leave a
truncated JPEG that `exists()` accepts as finished.

The screen and the spreadsheet disagree on purpose. In the matrix, one photo sub-column holds
one or two thumbnails side by side, because widening a group for the photo count would break
the uniform grid. In the xlsx there are two photo columns, because a picture is anchored to
one cell with an explicit size and the second photo needs a second column.

## Export and import

Two export formats: xlsx with the photos embedded, and CSV. Both overwrite one file per table
per format in a folder you choose once through the system folder picker; the previous version
is moved into an "Archive" subfolder first, keeping the last three copies. The `.psbackup`
backup is different — it is written with a dated name each time, so its versions accumulate.

The xlsx is written with our own streaming ZIP and XML rather than Apache POI, which is bulky,
conflicts over `javax.xml`, and holds the whole workbook in memory — out of memory on hundreds
of photos. The order of elements inside a worksheet is fixed by the OOXML schema; reordering
them stops Excel opening the file, so the writer's output is pinned byte for byte by tests.

Reading handles spreadsheets we do not write ourselves: shared strings, dates as Excel serial
numbers, and pictures anchored across two cells. Import is two-phase — the preview is computed
from the same plan that is then executed, so the dialog cannot disagree with the result. It
creates a new table unless you pick an existing destination. The file type is decided by the
archive's contents, not its extension, because both `.psbackup` and `.xlsx` are ZIPs and names
from the system picker are unreliable.

A photo needs room in its cell. Google Sheets on a phone will not draw an embedded picture
that is cramped: the cell simply looks empty, though desktop Excel draws it. Fitting exactly
is not enough — a margin of at least 10 px is needed, measured on a device, and the row height
and the photo column width are derived from that one number. Microsoft Excel on a phone has
not been measured.

When someone reports that the photos are missing from a file, start with what they opened it
with. Twice now the file has been correct and the viewer simply did not draw the images.

## Localization

English and Russian. A fresh install follows the system language and falls back to English;
an explicit choice is stored by AppCompat and changed in Settings → Language. Stored names —
tables, groups, columns, values — are not translated when the language changes.

Exception text never reaches the interface. A failure is a typed reason, the UI picks a string
resource for it, and the exception itself goes to the log. Every new string goes into both
`values/strings.xml` and `values-ru/strings.xml`.

## Project structure

```
app/          Room, repositories, domain, Compose UI, photos   → depends on both others
matrixgrid/   the zoomable matrix engine, one Canvas renderer  → compose ui/foundation only
exportkit/    xlsx, CSV, backup, reading foreign xlsx          → pure JVM
```

`:exportkit` has no Android in it, which is what makes its format tests fast JVM tests.
`:matrixgrid` knows nothing about Room, Coil or files: it takes a finished model and a source
of thumbnails. Dependency injection is a hand-written `AppGraph` in `App.kt`, everything by
`lazy`, no Hilt.

## Diagnostics

The app writes a file log to `filesDir/logs/app.log` with size-based rotation, mirrors it to
logcat, and catches uncaught crashes before delegating to the previous handler. Settings →
Diagnostics → **Share logs** concatenates the rotation into one text file and hands it to the
system share sheet, which is how a log gets off a phone that has no network. The export path
is instrumented: start, finish, orphaned groups, missing photo metadata and missing files.

## Credits

Written in Kotlin with Jetpack Compose and Room. The xlsx format was reverse-engineered from
the spreadsheet the app replaced, kept in `docs/reference/iyun-xlsx/` as the reference.
