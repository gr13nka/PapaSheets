#!/usr/bin/env bash
# Собрать APK, который можно передать другому человеку (папе, прорабу) файлом.
#
#   ./scripts/build-apk-for-sharing.sh                 собрать подписанный release
#   ./scripts/build-apk-for-sharing.sh --skip-tests    без прогона тестов (не надо)
#   ./scripts/build-apk-for-sharing.sh --new-keystore  создать НОВЫЙ ключ подписи (см. предупреждение)
#
# Отличие от reinstall-phone.sh: та сборка debug и живёт на своём телефоне, эта уходит к людям.
# Поэтому здесь release (меньше, быстрее, с R8), подпись постоянным ключом и обязательные тесты:
# APK, отданный человеку, назад не отзовёшь.
set -euo pipefail
cd "$(dirname "$0")/.."

SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
KEYSTORE="keystore/papasheets-release.keystore"
APK="app/build/outputs/apk/release/app-release.apk"
ALIAS="papasheets"
DIST="dist"

SKIP_TESTS=0
NEW_KEYSTORE=0
for arg in "$@"; do
    case "$arg" in
        --skip-tests) SKIP_TESTS=1 ;;
        --new-keystore) NEW_KEYSTORE=1 ;;
        *) echo "Неизвестный аргумент: $arg" >&2; exit 2 ;;
    esac
done

have_secret() { grep -qs "^$1=" local.properties || [[ -n "${!1:-}" ]]; }

if [[ "$NEW_KEYSTORE" == "1" ]]; then
    # Ключ подписи — это то, чем Android отличает «обновление» от «другого приложения».
    # Потеряв его, обновить уже установленное приложение нельзя никогда: только снести с данными.
    echo "ВНИМАНИЕ: создаётся НОВЫЙ ключ подписи."
    echo "Все, у кого стоит версия, подписанная старым ключом, обновиться НЕ смогут —"
    echo "им придётся сносить приложение вместе с данными."
    echo "Делай это только пока приложением никто не пользуется."
    read -r -p "Продолжить? [y/N] " answer
    [[ "$answer" == "y" || "$answer" == "Y" ]] || { echo "Отменено."; exit 1; }

    read -r -s -p "Придумай пароль для ключа (запомни его!): " pw; echo
    [[ ${#pw} -ge 6 ]] || { echo "Пароль короче 6 символов keytool не примет." >&2; exit 1; }

    mkdir -p "$(dirname "$KEYSTORE")"
    [[ -f "$KEYSTORE" ]] && mv "$KEYSTORE" "$KEYSTORE.old-$(date +%Y%m%d%H%M%S)"
    # PKCS12: пароль ключа обязан совпадать с паролем хранилища, поэтому он тут один.
    "${JAVA_HOME:-/usr}/bin/keytool" -genkeypair -v \
        -keystore "$KEYSTORE" -storetype PKCS12 \
        -alias "$ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
        -storepass "$pw" -keypass "$pw" \
        -dname "CN=PapaSheets, OU=, O=, L=, S=, C=RU"

    # local.properties не в git — там и место паролям.
    touch local.properties
    sed -i '' '/^RELEASE_STORE_PASSWORD=/d;/^RELEASE_KEY_PASSWORD=/d;/^RELEASE_KEY_ALIAS=/d' local.properties
    { echo "RELEASE_STORE_PASSWORD=$pw"; echo "RELEASE_KEY_PASSWORD=$pw"; echo "RELEASE_KEY_ALIAS=$ALIAS"; } >> local.properties
    unset pw
    echo "Ключ создан: $KEYSTORE"
    echo
    echo "ОБЯЗАТЕЛЬНО сделай копию файла $KEYSTORE и пароля вне этой папки."
    echo "Ни ключ, ни пароли в git не попадают — потеряешь их вместе с маком."
    echo
fi

if ! have_secret RELEASE_STORE_PASSWORD || ! have_secret RELEASE_KEY_PASSWORD || ! have_secret RELEASE_KEY_ALIAS; then
    echo "Нет паролей подписи — release соберётся неподписанным, а такой APK телефон не поставит." >&2
    echo >&2
    if [[ -f "$KEYSTORE" ]]; then
        echo "Ключ на месте ($KEYSTORE), не хватает только паролей. Допиши в local.properties:" >&2
        echo "    RELEASE_STORE_PASSWORD=<пароль хранилища>" >&2
        echo "    RELEASE_KEY_PASSWORD=<пароль ключа>" >&2
        echo "    RELEASE_KEY_ALIAS=<алиас, скорее всего $ALIAS>" >&2
        echo >&2
        echo "Пароль вспомнить не удаётся? Тогда ключ бесполезен — запусти с --new-keystore." >&2
    else
        echo "Ключа тоже нет. Запусти с --new-keystore, скрипт создаст его и пропишет пароли." >&2
    fi
    exit 1
fi

if [[ "$SKIP_TESTS" == "0" ]]; then
    echo "Гоняю тесты (APK уходит людям — назад не отзовёшь)…"
    ./gradlew :app:testDebugUnitTest :exportkit:test :matrixgrid:test
fi

echo "Собираю release…"
./gradlew :app:assembleRelease
[[ -f "$APK" ]] || { echo "APK не собрался: $APK" >&2; exit 1; }

# Проверяем, что он реально подписан: неподписанный APK телефон молча откажется ставить.
APKSIGNER=$(find "$SDK/build-tools" -name apksigner -type f 2>/dev/null | sort -V | tail -1)
if [[ -n "$APKSIGNER" ]]; then
    "$APKSIGNER" verify "$APK" >/dev/null 2>&1 || { echo "APK не подписан как следует — ставить его нельзя." >&2; exit 1; }
    echo "Подпись на месте."
fi

VERSION=$(grep -o 'versionName = "[^"]*"' app/build.gradle.kts | head -1 | cut -d'"' -f2)
STAMP=$(date +%Y-%m-%d)
mkdir -p "$DIST"
OUT="$DIST/PapaSheets-$VERSION-$STAMP.apk"
cp "$APK" "$OUT"

echo
echo "Готово: $OUT ($(du -h "$OUT" | cut -f1))"
echo
echo "Отправляй этот файл (Telegram, почта, Drive). На телефоне получателя:"
echo "  1. открыть файл;"
echo "  2. Android спросит разрешение ставить приложения из этого источника — разрешить;"
echo "  3. установить."
echo
echo "Если у человека уже стоит версия, подписанная ДРУГИМ ключом, обновление не встанет:"
echo "ему придётся снести приложение вместе с данными (сначала выгрузить бэкап из приложения)."
