#!/usr/bin/env node
// Собирает демонстрационный .psbackup для съёмки картинок в README: сентябрь 2026, шесть групп,
// английские названия, цвета значений и фото. Настоящих данных заказчика в репозитории нет и не
// должно быть, а на пустой таблице матрицу не снимешь.
//
//   node scripts/make-demo-backup.mjs <каталог-с-фото> [выходной-файл]
//
// Каталог с фото: m1.jpg…mN.jpg (medium, 1280x720) и t1.jpg…tN.jpg (thumb, 256px) — именно две
// версии, потому что импорт кладёт файлы как есть и превью из medium не строит. Формат бэкапа —
// v7, схема БД — v8; при их смене править здесь, сверяясь с exportkit/.../backup/BackupData.kt.

import { readFileSync, writeFileSync, readdirSync } from 'node:fs'
import { join } from 'node:path'
import { deflateRawSync, crc32 } from 'node:zlib'

const photoDir = process.argv[2]
const outFile = process.argv[3] ?? 'demo.psbackup'
if (!photoDir) {
  console.error('usage: node scripts/make-demo-backup.mjs <photo-dir> [out.psbackup]')
  process.exit(2)
}
const sourceCount = readdirSync(photoDir).filter(f => /^m\d+\.jpg$/.test(f)).length
if (sourceCount === 0) {
  console.error(`no mN.jpg in ${photoDir}`)
  process.exit(2)
}

/** Минимальный ZIP-писатель: deflate + central directory, без внешних зависимостей. */
function zip(entries) {
  const locals = [], central = []
  let offset = 0
  for (const { name, data } of entries) {
    const nameBuf = Buffer.from(name, 'utf8')
    const comp = deflateRawSync(data)
    const sum = crc32(data)
    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0); local.writeUInt16LE(20, 4); local.writeUInt16LE(0x800, 6)
    local.writeUInt16LE(8, 8); local.writeUInt16LE(0, 10); local.writeUInt16LE(0x21, 12)
    local.writeUInt32LE(sum, 14); local.writeUInt32LE(comp.length, 18); local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(nameBuf.length, 26)
    locals.push(local, nameBuf, comp)
    const dir = Buffer.alloc(46)
    dir.writeUInt32LE(0x02014b50, 0); dir.writeUInt16LE(20, 4); dir.writeUInt16LE(20, 6)
    dir.writeUInt16LE(0x800, 8); dir.writeUInt16LE(8, 10); dir.writeUInt16LE(0, 12); dir.writeUInt16LE(0x21, 14)
    dir.writeUInt32LE(sum, 16); dir.writeUInt32LE(comp.length, 20); dir.writeUInt32LE(data.length, 24)
    dir.writeUInt16LE(nameBuf.length, 28); dir.writeUInt32LE(offset, 42)
    central.push(dir, nameBuf)
    offset += 30 + nameBuf.length + comp.length
  }
  const cd = Buffer.concat(central)
  const end = Buffer.alloc(22)
  end.writeUInt32LE(0x06054b50, 0)
  end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10)
  end.writeUInt32LE(cd.length, 12); end.writeUInt32LE(offset, 16)
  return Buffer.concat([...locals, cd, end])
}

// Идентификаторы встроенных полей обязаны совпадать с BuiltInFields, иначе импорт заведёт
// «Локацию» и «Вид работ» второй раз, рядом со своими.
const LOCATION_ID = '00000000-0000-4000-8000-000000000001'
const WORK_ID = '00000000-0000-4000-8000-000000000002'
const JOURNAL_ID = '11111111-2222-4333-8444-000000000001'
const CREATED_AT = Date.UTC(2026, 8, 1)
const epochDay = d => Math.floor(Date.UTC(2026, 8, d) / 86400000)

let counter = 0
const nextId = () => `dddddddd-0000-4000-8000-${String(++counter).padStart(12, '0')}`

// Младшие биты линейного конгруэнтного генератора почти не меняются, и остаток по степени двойки
// от них вырождается в одно и то же значение — берём старшие.
let seed = 20260901
const rnd = n => Math.floor((((seed = (seed * 1103515245 + 12345) & 0x7fffffff) >>> 12) / 0x80000) * n) % n

const groups = [
  ['Glaziers', 'GLZ'], ['Tilers', 'TIL'], ['Plasterers', 'PLA'],
  ['Electricians', 'ELE'], ['Screed', 'SCR'], ['Roofers', 'ROF'],
]
const contractors = groups.map(([name, shortName], i) => ({
  id: `cccccccc-0000-4000-8000-${String(i).padStart(12, '0')}`,
  name, shortName, colorIndex: i, orderIndex: i,
  isArchived: false, createdAt: CREATED_AT, journalId: JOURNAL_ID,
}))

const fieldDefs = [
  { id: LOCATION_ID, title: 'Location', label: 'LOC', orderIndex: 0, isArchived: false,
    isBuiltIn: true, isRequired: false, suggestFromHistory: true, columnWidthDp: 56,
    maxLines: 2, showAtCompactLod: true, createdAt: CREATED_AT, journalId: JOURNAL_ID,
    fallbackDefinition: false },
  { id: WORK_ID, title: 'Work type', label: 'WORK TYPE', orderIndex: 1, isArchived: false,
    isBuiltIn: true, isRequired: true, suggestFromHistory: true, columnWidthDp: 168,
    maxLines: 0, showAtCompactLod: false, createdAt: CREATED_AT, journalId: JOURNAL_ID,
    fallbackDefinition: false },
]

const workByGroup = {
  Glaziers: ['Glazing', 'Frames set'],
  Tilers: ['Tiling', 'Grouting'],
  Plasterers: ['Plaster', 'Skim coat'],
  Electricians: ['Wiring', 'Sockets'],
  Screed: ['Screed', 'Levelling'],
  Roofers: ['Roofing', 'Membrane'],
}
const workColors = {
  Glazing: 0, 'Frames set': 0, Tiling: 1, Grouting: 1, Plaster: 3, 'Skim coat': 3,
  Wiring: 4, Sockets: 4, Screed: 2, Levelling: 2, Roofing: 5, Membrane: 5,
}
const locations = ['A-12', 'B-03', 'C-07', 'A-04', 'D-11', 'B-08', 'C-02', 'A-09']

const PHOTO_COUNT = 18
const photoIds = Array.from({ length: PHOTO_COUNT },
  (_, i) => `eeeeeeee-0000-4000-8000-${String(i).padStart(12, '0')}`)
const mediumFor = i => join(photoDir, `m${(i % sourceCount) + 1}.jpg`)
const thumbFor = i => join(photoDir, `t${(i % sourceCount) + 1}.jpg`)

const records = [], recordValues = []
let usedPhotos = 0
for (let day = 1; day <= 18; day++) {
  for (const contractor of contractors) {
    if (rnd(10) < 5) continue
    const work = workByGroup[contractor.name][rnd(2)]
    const rowsToday = rnd(10) < 2 ? 2 : 1
    for (let row = 0; row < rowsToday; row++) {
      const id = nextId()
      let photoId = null, photoId2 = null
      if (usedPhotos < PHOTO_COUNT && rnd(10) < 4) {
        photoId = photoIds[usedPhotos++]
        if (usedPhotos < PHOTO_COUNT && rnd(10) < 3) photoId2 = photoIds[usedPhotos++]
      }
      const at = CREATED_AT + day * 86400000 + row * 600000
      records.push({
        id, journalId: JOURNAL_ID, dateEpochDay: epochDay(day), contractorId: contractor.id,
        locationCode: null, workText: null, photoId, photoId2, createdAt: at, updatedAt: at,
      })
      recordValues.push({ recordId: id, fieldId: LOCATION_ID, value: locations[rnd(locations.length)] })
      recordValues.push({ recordId: id, fieldId: WORK_ID, value: work })
    }
  }
}

const data = {
  journals: [{ id: JOURNAL_ID, year: 2026, month: 9, title: 'September 2026', createdAt: CREATED_AT }],
  contractors,
  records,
  photos: photoIds.slice(0, usedPhotos).map((id, i) => ({
    id, width: 1280, height: 720, sizeBytes: readFileSync(mediumFor(i)).length,
    originUri: null, createdAt: CREATED_AT + i * 3600000,
  })),
  locationPresets: [],
  fieldPresets: locations.map((code, i) => ({ id: nextId(), fieldId: LOCATION_ID, code, orderIndex: i })),
  fieldDefs,
  recordValues,
  fieldValueColors: Object.entries(workColors).map(([value, colorIndex]) => ({ fieldId: WORK_ID, value, colorIndex })),
}

const entries = [
  { name: 'manifest.json', data: Buffer.from(JSON.stringify({
      formatVersion: 7, dbSchemaVersion: 8, appVersionName: '1.3', exportedAtMillis: Date.now() })) },
  { name: 'data.json', data: Buffer.from(JSON.stringify(data)) },
]
for (let i = 0; i < usedPhotos; i++) {
  entries.push({ name: `photos/medium/${photoIds[i]}.jpg`, data: readFileSync(mediumFor(i)) })
  entries.push({ name: `photos/thumb/${photoIds[i]}.jpg`, data: readFileSync(thumbFor(i)) })
}

writeFileSync(outFile, zip(entries))
console.log(`${outFile}: records=${records.length} photos=${usedPhotos}`)
