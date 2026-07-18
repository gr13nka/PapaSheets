#!/usr/bin/env bash
# Установка «Журнала работ» на телефон, подключённый по USB (с включённой отладкой).
# Запуск:  ./scripts/install-phone.sh          — поставить уже собранный release-APK
#          ./scripts/install-phone.sh --build  — сначала пересобрать release
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
APK="app/build/outputs/apk/release/app-release.apk"

if [[ "${1:-}" == "--build" ]]; then
    ./gradlew :app:assembleRelease
fi

if [[ ! -f "$APK" ]]; then
    echo "APK не найден ($APK) — запусти со флагом --build" >&2
    exit 1
fi

# Берём первое реальное устройство (эмуляторы не считаются)
DEVICE=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
if [[ -z "$DEVICE" ]]; then
    if "$ADB" devices | grep -q "unauthorized"; then
        echo "Телефон найден, но отладка не разрешена — подтверди запрос «Разрешить отладку по USB» на экране телефона и запусти скрипт снова." >&2
    else
        echo "Телефон не найден. Проверь: кабель, включённую «Отладку по USB» (Настройки → Для разработчиков)." >&2
    fi
    exit 1
fi

echo "Устанавливаю на $DEVICE ..."
"$ADB" -s "$DEVICE" install -r "$APK"
echo "Готово. Ищи на телефоне приложение «Журнал работ»."
