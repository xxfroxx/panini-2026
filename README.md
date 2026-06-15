# panini-2026

Album Panini World Cup 2026.

## Web

La web estatica usa:

- `index.html`: dashboard
- `repe.html`: repetidas
- `falta.html`: faltantes
- `config.js`: URL central de Apps Script para JSONP

## Apps Script

`apps-script/Code.js` contiene el soporte de escritura para el MVP Android:

- `doPost(e)` con `action=addSticker`
- validacion con `PropertiesService.getScriptProperties().getProperty("APP_SECRET")`
- normalizacion de IDs (`col 05`, `col-05`, `FWC9`, `00`)
- suma de `CANTIDAD`
- no escribe en `TIENE`; la app solo modifica `CANTIDAD`
- limpieza de cache (`panini_dashboard`, `panini_repe`, `panini_missing`, `panini_full`)

Para desplegarlo, copia las funciones de `apps-script/Code.js` en tu proyecto Apps Script sin eliminar el `doGet(e)` existente. Luego crea la Script Property:

- Nombre: `APP_SECRET`
- Valor: un string largo privado

Despues despliega una nueva version del web app.

## Android MVP

El proyecto Android esta en `android/`.

Antes de compilar:

1. Crea `android/local.properties` desde `android/local.properties.example`.
2. En `android/local.properties`, define `APPS_SCRIPT_URL` con la URL `/exec` de Apps Script.
3. En `android/local.properties`, define `APP_SECRET` con el mismo valor configurado en Apps Script.
4. No subas `android/local.properties` al repo; esta ignorado por Git.

La app es Kotlin + Jetpack Compose, minimo SDK 26, y permite entrada manual con lista temporal para guardar varios IDs. No incluye OCR, camara ni escaneo automatico.
