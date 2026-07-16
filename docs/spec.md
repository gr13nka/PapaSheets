# PapaSheets — офлайн Android-журнал строительных работ

## Контекст

Прораб ведёт на объекте журнал работ: дата + подрядчик + код локации + «вид работ» + одно фото. Раньше — Google Sheets («Июнь.xlsx», матрица дата × 28 подрядчиков), но Sheets задыхается от встроенных фото и неудобен с телефона. PapaSheets — нативное Android-приложение, полностью офлайн, без сервера: таблица живёт в приложении, Google Sheets больше не нужен. Папка `/Users/username/Documents/PapaSheets` пуста — greenfield.

Эталон структуры — реальный файл `~/Downloads/Copy of Июнь.xlsx` (разобран): колонка A = ДАТА (несколько строк на дату, строка = запись), freeze B3, 28 групп подрядчиков × 3 колонки Ф/Л/ВИД РАБОТ, ~316 строк и 25 фото за месяц, заполнение разреженное.

## Зафиксированные требования (ответы пользователя)

- Один телефон, всё локально; в будущем возможно несколько людей; изредка передать таблицу целиком → UUID везде, экспорт/импорт полной копии, путь к синхронизации не закрывать.
- Матрица дата × подрядчик как в примере; **отдельные журналы-месяцы**.
- **Ровно одно фото на запись.** Источник: камера из приложения + галерея; оригинал остаётся в галерее, в приложение — сжатая копия (проверенный формат: вписать в 1280×720/720×1280, JPEG q≈0.72).
- Ключевой UX: быстрый рендер сотен строк с фото-превью; тап по фото → полноэкранный лайтбокс с пинч-зумом; **сильный пинч-зум-аут всей матрицы** («кто что сделал» одним взглядом); закреплённые шапки (дата + подрядчики); сортировка строк по дате ↑↓. Больше ничего из Google Sheets не нужно.
- Форма: подрядчик — дропдаун из настраиваемого пула; локация — автодополнение (история + пресеты); «продолжить вчерашнее» (дублировать запись на сегодня, фото — новое); дата по умолчанию сегодня.
- Экспорт: **xlsx со встроенными фото в существующем формате матрицы**, CSV, полный бэкап-файл. UI на русском.

## Технические решения

- **Kotlin + Jetpack Compose** (выбор делегирован мне): требования целиком про производительность кастомного рендера — нативно надёжнее всего. minSdk 26, targetSdk 35, пакет `ru.papasheets`.
- Room (SQLite, ksp), Coil (только лайтбокс/форма), системная камера `TakePicture` + `PickVisualMedia` (без CameraX), kotlinx.serialization (бэкап). DI — ручной `AppGraph`, без Hilt. Сети нет вообще.
- **xlsx — своя стриминговая генерация ZIP+XML** (формат реверс-инжинирен). Apache POI отвергнут: multidex-масса, конфликты javax.xml, XSSF держит книгу в памяти → OOM на сотнях фото.

## Архитектура — 3 Gradle-модуля (deep modules)

```
app/          UI, навигация, ViewModel'ы, Room, фото-пайплайн, склейка
matrixgrid/   Android lib: движок зумируемой матрицы. Зависимости: ТОЛЬКО compose. Ни Room, ни Coil, ни файлов.
exportkit/    Pure JVM lib: xlsx/csv/backup кодеки. Без Android → быстрые JVM-тесты формата.
```

Узкие интерфейсы глубоких модулей:
1. `MatrixView(model: GridModel, state: MatrixState, thumbnails: ThumbnailSource, callbacks: MatrixCallbacks)` — скрывает виртуализацию, LOD, жесты, стики-шапки, кэши, hit-testing. Картинки через `ThumbnailSource { suspend fun load(key, targetPx): ImageBitmap? }`.
2. `PhotoStore` — `import(source): PhotoMeta` / `thumbFile(id)` / `mediumFile(id)` / `delete(id)` / `collectGarbage(referenced)`. Скрывает EXIF, сжатие, схему файлов, GC.
3. `XlsxWriter.write(snapshot: JournalSnapshot, photos: PhotoBytesProvider, out: OutputStream)` — скрывает OOXML/ZIP/EMU. `JournalSnapshot` — plain-модель exportkit, app мапит из Room.
4. `GridModelBuilder(records, contractors, sortDesc) -> GridModel` — чистая функция раскладки «несколько строк на дату» (строк в дне = max записей одного подрядчика в этот день; заполнение сверху вниз по createdAt).

Слои app: data (Room+репозитории) → domain → ui (VM+Compose). ViewModel'ы не видят DAO.

## Матрица — главный технический риск

**Единый Canvas-рендерер** (не дерево composable'ов — тысячи compose-нод при зум-ауте = тупик): один composable, `Modifier.pointerInput` + `drawWithCache`, всё рисуется в DrawScope.

- **Геометрия**: фиксированная высота строки, фиксированные ширины колонок по типу → координата↔ячейка за O(1), тривиальные hit-testing и виртуализация. Длинный текст эллипсируется (полный — по тапу в форме).
- **Виртуализация**: на кадр рисуется только видимый диапазон строк×колонок.
- **LOD 3 яруса**: LOD0 (zoom≥~0.7) фото-превью+локация+2–3 строки текста; LOD1 (~0.3–0.7) микро-превью+код локации, без текста; LOD2 (<~0.3) «картина месяца» — цветной блок на запись (цвет подрядчика), шапки-аббревиатуры, битмапы не грузятся.
- **Текст**: `TextMeasurer` только для видимых ячеек, LRU-кэш layout'ов, замер на дискретной шкале яруса + `canvas.scale()` между ярусами — никакого перемера в живом пинче.
- **Жесты**: свой детектор в `awaitEachGesture` (velocity!): pan+fling (`VelocityTracker`+`splineBasedDecay`), pinch вокруг центроида, tap/long-press/double-tap (fit month ↔ 1.0). Во время пинча — масштабирование готового слоя через `graphicsLayer`, полный перерендер по отпусканию.
- **Стики-шапки**: 4 квадранта с `clipRect` (тело / колонка дат / 2-строчная шапка подрядчиков / угол), у шапок минимальный читаемый кегль на любом зуме.
- **Recomposition-гигиена**: pan/zoom — `mutableFloatStateOf`, читаются ТОЛЬКО в draw/graphicsLayer-лямбдах; рекомпозиция — только при смене иммутабельного `GridModel`.
- **Битмапы**: LRU по байтам ~48–64 МБ, async-загрузка, плейсхолдер до готовности.

**Запасной вариант** (гейт в M4): LazyColumn, строка = один Canvas, общий горизонтальный scroll + Canvas-шапка; «картина месяца» — отдельный heatmap-экран. Тот же интерфейс `MatrixView` — переключение не трогает приложение.

## Фото-пайплайн (app/photos)

- Камера: temp в `cacheDir` через FileProvider → import; полный оригинал дополнительно в MediaStore `Pictures/PapaSheets` (галерея). Галерея: `PickVisualMedia` → import, оригинал не трогаем.
- `PhotoImporter`: bounds → `inSampleSize` → decode → EXIF-поворот (после downsample) → вписать в 1280×720/720×1280 → JPEG q=72 → `medium.jpg`; из него thumb 256px q=70. Возврат `PhotoMeta(id, width, height, bytes)` (нужно xlsx-якорям).
- Файлы: `filesDir/photos/{medium,thumb}/{uuid}.jpg`. Порядок транзакций: файл → insert; delete БД → файл; GC-страховка при старте (только файлы старше 24ч).
- Матрица — свой `BitmapThumbnailSource` (BitmapFactory+LRU), не Coil. Лайтбокс — Coil + свой `Modifier.zoomable` (~100 строк).

## Схема Room v1

PK везде TEXT UUID; `createdAt`/`updatedAt` millis; даты как epochDay. `exportSchema=true` с v1.

- `JournalEntity(id, year, month, title, createdAt)` — unique(year,month).
- `ContractorEntity(id, name, shortName, colorIndex, orderIndex, isArchived, createdAt)` — глобальный пул, сид 28 из Июнь.xlsx, архив вместо удаления.
- `RecordEntity(id, journalId FK, dateEpochDay, contractorId FK, locationCode, workText, photoId FK?, createdAt, updatedAt)` — photoId nullable в схеме, обязательность — валидацией формы.
- `PhotoEntity(id, width, height, sizeBytes, originUri?, createdAt)`.
- `LocationPresetEntity(id, code, orderIndex)`.

Индексы: Record(journalId,dateEpochDay), Record(contractorId), Record(photoId) unique. Автодополнение локаций = DISTINCT по истории (ORDER BY MAX(updatedAt) DESC LIMIT 15) UNION пресеты. «Продолжить вчерашнее» = записи (journalId, contractorId, date−1).

## Экспорт (exportkit)

- **xlsx**: стрим в `ZipOutputStream`, JPEG копируются в `xl/media/` без перекодирования. inlineStr-строки; `<mergeCells>` для групп в строке 1; `<pane xSplit="1" ySplit="2" topLeftCell="B3" state="frozen"/>`; фото `<xdr:oneCellAnchor>` с EMU (px×9525), высоты фото-строк в `<row ht>`. 1280×720=0.92 Mpx < лимита Sheets ~1.05 Mpx/картинку. Классы: `XlsxWriter, SheetXml, DrawingXml, StylesXml, ContentTypesXml, RelsXml, Emu, Xml` + `JournalSnapshot`, `PhotoBytesProvider`. JVM-тесты: генерация → распаковка → сверка XML с эталонами.
- **CSV**: RFC4180, разделитель `;`, UTF-8 **BOM** (кириллица в русском Excel).
- **Бэкап `.psbackup`**: zip = `manifest.json` (schemaVersion) + `data.json` (всё с UUID) + `photos/`. Импорт: upsert по UUID, конфликт → больший updatedAt. Это же — транспорт будущей синхронизации. Доставка: SAF `CreateDocument` + `ACTION_SEND`.

## Экраны (single-activity, Navigation Compose)

`journals` → `journal/{id}` → `lightbox/{recordId}`; `settings/contractors`, `settings/locations`. Форма записи — `ModalBottomSheet` внутри MatrixScreen, не destination.

1. **JournalListScreen** — карточки месяцев, FAB «Новый журнал», импорт бэкапа, настройки.
2. **MatrixScreen** — MatrixView во весь экран; topBar: сортировка ↑↓, экспорт; FAB «+ Запись»; тап по пустому слоту → форма с предзаполненными датой/подрядчиком.
3. **RecordSheet** — дата (default сегодня), подрядчик (dropdown), локация (автодополнение), вид работ (multiline), фото (камера/галерея), «Продолжить вчерашнее» (копирует подрядчика/локацию/текст, фото — новое). Без фото не сохраняем.
4. **LightboxScreen** — fullscreen medium-фото с пинч-зумом, подпись, «Редактировать запись».
5. **ContractorsScreen** — CRUD, drag-reorder, цвет, архив. **LocationsScreen** — CRUD пресетов.
6. **ExportDialog** — xlsx/CSV/бэкап + прогресс (xlsx на сотни фото — секунды, 50–75 МБ; предупреждение + опция «без фото»).

## Милстоуны (каждый — проверяемый на устройстве)

- **M0 скелет**: Gradle (settings, libs.versions.toml, app), Manifest, `App.kt`+AppGraph, MainActivity, Theme, AppNav, заглушка JournalList, strings.xml. ✓ APK ставится, русский экран «Журналы».
- **M1 данные + рабочий ввод (без фото/матрицы)**: AppDatabase, entities, DAO, репозитории, DefaultSeed (28 подрядчиков), JournalListVM, MonthPicker, временный DayListScreen, RecordSheet+VM. ✓ Создать журнал, CRUD записей с дропдауном и автодополнением, переживает перезапуск — **уже можно пользоваться в поле**.
- **M2 фото + лайтбокс**: PhotoStore, PhotoImporter, PhotoGc, CameraCapture, GalleryPick, FileProvider, LightboxScreen, ZoomableImage. ✓ Фото с камеры и из галереи, EXIF-поворот (проверить на Samsung), thumb+medium в filesDir, лайтбокс с зумом, оригинал в галерее.
- **M3 матрица v1 (zoom=1)**: модуль matrixgrid (GridModel, MatrixView, MatrixState, MatrixGeometry, MatrixRenderer, CellTextCache, ThumbnailSource, Callbacks), MatrixScreen+VM, GridModelBuilder, BitmapThumbnailSource, FakeDataSeeder (28×300). ✓ Стики-шапки, 2D-скролл+fling, тапы в форму/лайтбокс, плавно на 300 строках.
- **M4 зум + LOD (сердце заказа)**: Lod, MatrixGestures, ContractorPalette, 3 яруса рендера, graphicsLayer-зум в жесте, double-tap. ✓ Непрерывный пинч от heatmap месяца до ячеек с фото, шапки читаемы всегда, ~60fps на 28×300. **Гейт: буксует → запасной гибрид за тем же интерфейсом.**
- **M5 UX-доводка**: ContractorsScreen (drag-reorder), LocationsScreen, «Продолжить вчерашнее», сортировка ↑↓. ✓ Всё из раздела «Форма».
- **M6 экспорт xlsx+CSV**: exportkit + JVM-тесты, SnapshotBuilder, ExportInteractor, ExportDialog. ✓ xlsx открывается в Excel и Sheets (merged-шапка, freeze B3, фото на местах, эквивалент Июнь.xlsx), CSV без кракозябр, тесты зелёные.
- **M7 бэкап/импорт**: BackupWriter/Reader, Backup/ImportInteractor, меню. ✓ Экспорт → чистая установка → импорт → идентичные данные и фото.
- **M8 полировка**: onTrimMemory→сброс LRU, планирование GC, каскадное удаление журнала с подтверждением, empty states, иконка, R8-release. ✓ Чек-лист: месяц реальных данных, поворот, «Don't keep activities», прерывание камеры, идемпотентный повторный импорт.

## Риски

1. Память битмапов → LRU по байтам, декод под LOD-размер, medium не грузится в матрицу, RGB_565-опция.
2. Recomposition на жестах → чтение pan/zoom только в draw-лямбдах.
3. TextMeasurer — главная цена Canvas → LOD2 без текста, кэш, дискретные ярусы, ноль аллокаций в горячем пути.
4. xlsx-капризы Excel (rId/Content_Types строже Sheets), EMU-математика, размер файла 50–75 МБ → предупреждение и «без фото».
5. EXIF/Samsung; TakePicture при убитом процессе → temp-uri в SavedStateHandle.
6. PickVisualMedia на API 26 → проверить фолбэк GET_CONTENT.
7. Жест-конфликты tap/pan/pinch → slop-пороги, fling прерывается касанием.
8. Фиксированная высота строк режет длинный текст → принятый трейд-офф (полный текст по тапу); вариант «раскрытая строка» — не раньше M8.

## Верификация end-to-end

1. JVM-тесты exportkit (формат xlsx против эталонных XML из распакованного Июнь.xlsx — лежит в scratchpad сессии, при необходимости распаковать заново из `~/Downloads/Copy of Июнь.xlsx`).
2. Каждый милстоун: сборка `./gradlew :app:assembleDebug` + установка на устройство/эмулятор (`adb install`), ручная проверка по чек-листу милстоуна.
3. Перф-проверка матрицы на FakeDataSeeder 28×300 (fps в GPU-профиле).
4. Финал: реальный сценарий «день прораба» — 10 записей с фото, зум-аут «картина месяца», экспорт xlsx → открыть в Excel/Sheets и сверить с Июнь.xlsx.

## Предпосылки сборки (проверить в M0)

На маке нужны Android SDK + JDK17 (`brew install --cask android-commandlinetools temurin@17` при отсутствии), `sdkmanager` platform+build-tools, телефон с включённой отладкой по USB/Wi-Fi (или эмулятор). Пользователь код не пишет — все сборки и установки делает Claude Code через gradle/adb; передача APK папе — файлом (Telegram/Drive).

## После одобрения плана

Скоммитить этот план как spec в `docs/` нового git-репозитория PapaSheets (инициализировать репо, .gitignore для Android). Память: обновить `project_betterspreadsheets_photojournal.md` — проект заменён нативным PapaSheets.
