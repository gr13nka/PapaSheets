#!/usr/bin/env bash
# Поставить свежую сборку на свой телефон (для проверки, не для передачи другим).
#
#   ./scripts/reinstall-phone.sh            собрать debug и поставить на телефон
#   ./scripts/reinstall-phone.sh --clean    то же, но сначала снести приложение вместе с данными
#   ./scripts/reinstall-phone.sh --no-build поставить уже собранный APK, не пересобирая
#
# Сначала пробуем обновление поверх — так переживают и записи, и фото, и заодно проверяются
# миграции базы на настоящих данных (самая ценная проверка, какая тут вообще бывает).
# Сносим только если обновиться нельзя: Android не даёт заменить приложение сборкой с другой
# подписью, а debug и release подписаны разными ключами.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
ADB="$SDK/platform-tools/adb"
APK="app/build/outputs/apk/debug/app-debug.apk"
PKG="ru.papasheets"

BUILD=1
CLEAN=0
for arg in "$@"; do
    case "$arg" in
        --clean) CLEAN=1 ;;
        --no-build) BUILD=0 ;;
        *) echo "Неизвестный аргумент: $arg" >&2; exit 2 ;;
    esac
done

[[ -x "$ADB" ]] || { echo "adb не найден: $ADB" >&2; exit 1; }

# Телефон, а не эмулятор: ставить проверочную сборку в эмулятор смысла нет.
DEVICE=$("$ADB" devices | awk 'NR>1 && $2=="device" && $1 !~ /^emulator-/ {print $1; exit}')
if [[ -z "$DEVICE" ]]; then
    if "$ADB" devices | grep -q "unauthorized"; then
        echo "Телефон виден, но отладка не разрешена." >&2
        echo "Подтверди на экране телефона запрос «Разрешить отладку по USB» и запусти скрипт снова." >&2
    else
        echo "Телефон не найден. Проверь: кабель (не только зарядный), «Отладка по USB» в разделе" >&2
        echo "«Для разработчиков», и что телефон разблокирован." >&2
    fi
    exit 1
fi

if [[ "$BUILD" == "1" ]]; then
    echo "Собираю debug…"
    ./gradlew :app:assembleDebug
fi
[[ -f "$APK" ]] || { echo "APK не найден ($APK) — запусти без --no-build" >&2; exit 1; }

wipe_and_install() {
    echo "Сношу старую версию вместе с данными…"
    "$ADB" -s "$DEVICE" uninstall "$PKG" >/dev/null 2>&1 || true
    "$ADB" -s "$DEVICE" install "$APK"
}

if [[ "$CLEAN" == "1" ]]; then
    wipe_and_install
else
    echo "Ставлю на $DEVICE (с сохранением данных)…"
    # install -r сохраняет данные, но падает при смене подписи или понижении версии.
    if ! "$ADB" -s "$DEVICE" install -r "$APK" 2>/tmp/papasheets-install.err; then
        cat /tmp/papasheets-install.err >&2
        echo >&2
        echo "Обновить поверх не вышло — обычно это другая подпись (стояла release-сборка)." >&2
        echo "Единственный способ поставить эту сборку — снести приложение ВМЕСТЕ С ДАННЫМИ." >&2
        echo >&2
        echo "Если в приложении есть что-то нужное, сначала выгрузи бэкап и ответь «нет»." >&2
        read -r -p "Снести и поставить заново? [y/N] " answer
        [[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "Отменено, приложение не тронуто."; exit 1; }
        wipe_and_install
    fi
fi

echo
echo "Готово. Приложение «Журнал работ» на телефоне."
echo "Логи, если что-то падает:  $ADB -s $DEVICE logcat --pid=\$($ADB -s $DEVICE shell pidof $PKG)"
