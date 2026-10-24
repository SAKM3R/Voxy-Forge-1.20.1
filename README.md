# Voxy для Forge 1.20.1 + Embeddium

Неофициальный порт LOD-мода [Voxy](https://github.com/MCRcortex/voxy) (автор — **MCRcortex**) на
**Minecraft 1.20.1 + Forge** с **Embeddium** вместо Sodium. Основан на NeoForge 1.21.1-порте
[falling-colud/voxy-forged](https://github.com/falling-colud/voxy-forged) (Sodium 0.8 backport с исправлениями
цвета LOD, кривизны мира и воды).

*Unofficial Forge 1.20.1 + Embeddium port of Voxy, based on the NeoForge 1.21.1 port voxy-forged.*

Voxy дорисовывает мир далеко за пределами обычной дальности прорисовки упрощёнными воксельными LOD'ами.

## Скачать

Готовый jar — на странице [Releases](../../releases). Положите его в папку `mods`.

| Требование | Версия |
|---|---|
| Minecraft | 1.20.1 |
| Forge | 47.1 или новее (собрано на 47.3.0) |
| Embeddium | 0.3.25 – 0.3.x (собрано на 0.3.31) |
| Oculus | необязательно; для шейдеров — 1.8.0 |
| Java | 17 или новее, **рекомендуется 21** |
| Видеокарта | OpenGL 4.6 с compute-шейдерами и `GL_ARB_indirect_parameters` |

На Java 21 Voxy использует аппаратные инструкции `Integer/Long.expand/compress`, на Java 17 — их программную
замену (работает, но мешинг LOD'ов немного медленнее).

Fabric-моды и Sinytra Connector не нужны.

## Настройки

**Настройки → Настройки графики** (экран Embeddium) → раздел **Voxy**, три вкладки:

- **General** — включение, потоки, приём чанков, отрисовка, дальность LOD, качество.
- **Fog & Clouds** — туман на LOD'ах, пещерный туман, дальность облаков.
- **Advanced** — дальняя генерация, центральная «ванильная» полоса, граница LOD, кривизна мира.

Тот же экран открывает кнопка **Config** у Voxy в списке модов. Настройки хранятся в `config/voxy-config.json`.

Команды: `/voxy reload`, `/voxy import ...`, `/voxy distantgen status|pause|resume|reset`,
`/voxy colorfix on|off`, `/voxy curvefix on|off`.

**Дальняя генерация** (одиночная игра / хост LAN): мир за пределами дальности прорисовки генерируется в фоне
кольцами вокруг игрока и сразу превращается в LOD'ы. Прогресс сохраняется между сессиями.

## Шейдеры (Oculus)

С **Oculus 1.8.0** LOD'ы рисуются через шейдерпак, если в нём есть поддержка Voxy (файл `voxy.json` в архиве).
Она есть, например, в Complementary Reimagined / Unbound (r5.9+), Photon и Solas. На Complementary Reimagined
r5.9.3 это проверяется автоматически при каждой сборке.

- Шейдерпак без `voxy.json` — LOD'ы под ним не рисуются (вместо криво наложенной картинки), обычная отрисовка не меняется.
- После включения/выключения Voxy или его отрисовки шейдерпак перезагружается сам.

## Отличия от NeoForge-версии

- **Шейдеры** работают через **Oculus 1.8** (Iris для Forge 1.20.1) вместо Iris.
- **Меню настроек** сделано на API Embeddium (`OptionGUIConstructionEvent`) вместо config API Sodium 0.8.
- **Облака** продлеваются через событие Embeddium `ModifyCloudRenderingEvent`.
- **Палитры чанков** от Canary/Radium и других модов читаются через общий интерфейс `Palette`,
  без жёсткой зависимости от Lithium.
- Не перенесены интеграции, которых нет на Forge 1.20.1: ModMenu, Flashback, Nvidium, строка в F3 нового формата.

## Сборка из исходников

Нужен JDK 21 (Gradle сам скачает его, если не найдёт).

```
gradlew build
```

Готовый jar — `build/libs/voxy-<версия>+forge-1.20.1.jar` (файл без `-slim`: в нём уже лежат RocksDB, LMDB,
ZSTD, LZ4 и MixinExtras).

`gradlew runClient` запускает игру из исходников с Embeddium. `gradlew runClient -Psmoketest` — автоматический
прогон: создаёт мир, летает в нём ~75 секунд, пишет состояние Voxy в лог (`VOXY_SMOKE ...`) и выходит.
Этот же прогон GitHub Actions делает на программном GPU (Mesa llvmpipe) при каждом пуше — без шейдеров и с
Oculus + Complementary Reimagined (`-PwithOculus` подключает Oculus к запуску из исходников).

## Лицензия

Voxy распространяется автором под **All Rights Reserved**. Этот порт сделан для личного использования; все права
на Voxy принадлежат MCRcortex. Пожалуйста, уважайте условия автора и не выкладывайте jar на сторонние сайты.
