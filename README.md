# NDAY Player

Неофициальный Android-клиент для просмотра видео с [nday.club](https://nday.club) на Amazon Fire TV (Android 7)
и обычных телефонах/планшетах (Android 5.0+). Замена старому приложению `com.nday`, которое больше не поддерживается.

## Возможности

- Вход по TV-коду или QR (как в оригинальном приложении), на телефоне — кнопка открытия сайта.
- Все видео, подборки, «Вы смотрели» (по прогрессу с сервера), видео по годам.
- Плеер на ExoPlayer: DASH + Widevine/ClearKey, продолжение с места остановки, синхронизация прогресса с сайтом.
- Скорость 1.0× / 1.3× / 1.5× / 1.75× / 2.0× без изменения высоты голоса; звук всегда декодируется в PCM,
  чтобы скорость работала и при выводе на ресивер.

## Управление

| Пульт Fire TV | Телефон | Действие |
|---|---|---|
| OK / Play-Pause | двойной тап по центру, ⏯ | пауза |
| ◀ ▶, Rewind / FF (удержание ускоряет) | двойной тап слева/справа, ⏪ ⏩, полоса прогресса | перемотка |
| ▲ ▼ | — | скорость |
| ☰ | кнопка скорости | список скоростей |

Все клавиши пульта обрабатываются на уровне экрана, панель управления для них не нужна.

## Сборка

Тулчейн: JDK 8, Android Gradle Plugin 3.2.1, Gradle 4.6 (wrapper), SDK platform 26, build-tools 28.0.3.

Токены API nday.club в репозитории не хранятся. Их нужно взять из оригинального APK `com.nday`
(`assets/index.android.bundle`) и положить в `local.properties` (или в переменные окружения
`NDAY_GUESTTOKEN` / `NDAY_TVCODETOKEN`):

```properties
sdk.dir=/path/to/android-sdk
# гостевой токен (getData() в бандле) и токен для getTVCod / getTVCodCheck
nday.guestToken=...
nday.tvCodeToken=...
```

```sh
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
