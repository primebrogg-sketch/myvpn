# MyVPN — стартовый проект

Это рабочий каркас Android-приложения (Kotlin + Jetpack Compose), а не готовый
к публикации продукт. Он компилируется по структуре, но **реальный VPN-туннель
заработает только после того, как вы подключите ядро sing-box** — см. ниже.

## Что уже сделано

| Компонент | Файл | Статус |
|---|---|---|
| UI (список серверов, кнопка подключения) | `ui/MainActivity.kt` | готово |
| ViewModel, состояние подключения | `ui/VpnViewModel.kt` | готово |
| Парсинг share-ссылок (vless/ss/hysteria2/trojan) | `data/ServerConfig.kt` | готово |
| Пул серверов + health-check по TCP-латентности | `vpn/ServerPool.kt` | готово |
| Генератор конфига sing-box (JSON) с auto-failover (`urltest`) | `vpn/SingBoxConfigBuilder.kt` | готово |
| Android `VpnService`, поднятие TUN-интерфейса | `vpn/MyVpnService.kt` | **каркас — ядро не подключено, см. TODO в файле** |

## Почему протокол/крипто не написаны с нуля

Самодельная обфускация трафика почти всегда быстро распознаётся DPI и не даёт
того эффекта, ради которого всё затевалось. Вместо этого проект использует
**sing-box** — открытое, активно поддерживаемое ядро, которое реализует:

- **VLESS + Reality** — маскирует TLS-хендшейк под реальный сайт (сейчас
  наиболее устойчивый к DPI-блокировкам вариант);
- **Hysteria2** — поверх QUIC/UDP, устойчив к шейпингу трафика;
- **Shadowsocks / Trojan** — для менее агрессивных сетей.

Автопереключение между серверами тоже отдано ядру: в `SingBoxConfigBuilder`
все ваши серверы оборачиваются в `urltest`-outbound, который сам постоянно
проверяет, какой из них жив и быстр, и переключается без вашего участия.
`ServerPool.kt` в приложении нужен на уровень выше — для UI-списка и для
подрезки огромных подписок ещё до передачи в sing-box.

## Получение ядра sing-box (обязательный шаг)

Готового `.aar` в открытом доступе на Maven Central нет, собирается вручную:

```bash
git clone https://github.com/SagerNet/sing-box
cd sing-box
go install golang.org/x/mobile/cmd/gomobile@latest
gomobile init
gomobile bind -target=android -androidapi=26 \
  -o libbox.aar ./experimental/libbox
```

Готовый `libbox.aar` положите в `app/libs/`, затем в `app/build.gradle.kts`
раскомментируйте строку:

```kotlin
implementation(files("libs/libbox.aar"))
```

После этого в `MyVpnService.kt` замените `startSingBoxCore(...)` на реальный
вызов `Libbox.newService(configJson, platformInterface)` — точное имя классов
может немного отличаться между релизами sing-box, сверяйтесь с javadoc
внутри aar. Проще всего свериться с эталонной реализацией
[sing-box-for-android](https://github.com/SagerNet/sing-box-for-android) —
файл `BoxService.kt` там показывает актуальный API один в один.

## Источник серверов

Сейчас `ServerPool` создаётся с пустым списком подписок — вы просили только
клиент. Добавить сервера можно двумя способами:

1. **Вручную из UI** — поле в `MainActivity` принимает `vless://…`, `ss://…`,
   `hysteria2://…` ссылки.
2. **Подписка** — передайте URL(-ы) в конструктор `ServerPool(subscriptionUrls = listOf(...))`
   в `VpnViewModel.kt`. Формат — обычный текст/base64 со share-ссылками по
   одной на строку (стандарт v2rayN/NekoBox).

## Сборка

Открыть папку в Android Studio (Koala+), дождаться Gradle sync, Run.
Понадобится минимум Android 8.0 (API 26) на устройстве — ограничение
`VpnService` + современного TLS-стека.

## Дальнейшие шаги, которые стоит сделать сами

- Подключить реальный `Libbox`-вызов в `MyVpnService` (см. TODO).
- Добавить постоянное хранилище серверов (сейчас список живёт только в памяти).
- Обработать `onRevoke()` / потерю сети — переподключение уже частично
  прикрыто `urltest`, но приложению стоит показывать статус пользователю.
- Если нужен обход whitelisting (не просто DPI-блокировок, а разрешённых
  списков доменов) — добавьте вариант выхода через CDN (Cloudflare
  Workers/Pages) поверх Reality; это отдельная тема, спросите отдельно, если
  понадобится.
