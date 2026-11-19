# VPNApp для Android

Исходники Android-мини-приложения TnDtun. Клиент на Kotlin сохраняет и импортирует профили, показывает состояние соединения и управляет раздельной маршрутизацией. В проект входят Android VPN Service, адаптеры транспортов и Java-компоненты AmneziaWG.

Снимок соответствует исходникам Android 0.1.4 (build 38). Настройки серверов заменены примерами, проверка пользователя проходит вручную. Для подключения введите параметры собственного сервера. Панель подписок и готовый профиль DNS требуют отдельной настройки.

## Локальная сборка

Откройте папку [V2rayNG](V2rayNG/) в Android Studio. Нужны Android SDK 37, JDK 17 и Gradle Wrapper проекта. Для сборки потребуются совместимый `libv2ray.aar` и нативные библиотеки transport helpers для нужной архитектуры, размещённые локально в `V2rayNG/app/libs/` и её подпапках ABI. Это снимок исходников, готовые библиотеки и APK в него не входят.

Клиент основан на [v2rayNG](https://github.com/2dust/v2rayNG). Лицензия Android-приложения — [GPL-3.0](LICENSE), лицензии компонентов сохранены в [списке уведомлений](V2rayNG/app/src/main/res/raw/licenses.xml), [AmneziaWG](V2rayNG/third_party/amneziawg-android/COPYING) и [нативном коде](native/amnezia/).

Другие клиенты VPNApp: [iOS](https://github.com/Stepan-Gribov/vpn-ios) · [Windows](https://github.com/Stepan-Gribov/vpn-windows).
