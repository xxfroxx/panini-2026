const SHEETS = [
  '01_PANINI',
  '02_GRUPO_A',
  '03_GRUPO_B',
  '04_GRUPO_C',
  '05_GRUPO_D',
  '06_GRUPO_E',
  '07_GRUPO_F',
  '08_STICKERS_COCA_COLA',
  '09_GRUPO_G',
  '10_GRUPO_H',
  '11_GRUPO_I',
  '12_GRUPO_J',
  '13_GRUPO_K',
  '14_GRUPO_L',
  '15_FIFA_WORLD_CUP_CHAMPIONS'
];

function doGet(e) {
  const view = e && e.parameter && e.parameter.view
    ? e.parameter.view
    : 'dashboard';

  const callback = e && e.parameter && e.parameter.callback;
  const cache = CacheService.getScriptCache();
  const cacheKey = 'panini_' + view;

  const cached = cache.get(cacheKey);

  let data;

  if (cached) {
    data = JSON.parse(cached);
    data.source = 'cache';
  } else {
    data = buildPaniniData(view);
    data.source = 'sheet';

    // 300 segundos = 5 minutos
    cache.put(cacheKey, JSON.stringify(data), 300);
  }

  const output = callback
    ? callback + '(' + JSON.stringify(data) + ');'
    : JSON.stringify(data);

  return ContentService
    .createTextOutput(output)
    .setMimeType(
      callback
        ? ContentService.MimeType.JAVASCRIPT
        : ContentService.MimeType.JSON
    );
}

function buildPaniniData(view) {
  const stickers = getAllStickers();

  const total = stickers.length;
  const owned = stickers.filter(s => s.hasSticker).length;
  const missing = stickers.filter(s => s.missing).length;
  const missingList = stickers.filter(s => s.missing);
  const duplicatesList = stickers.filter(s => s.duplicate);
  const duplicateItems = duplicatesList.length;
  const totalDuplicates = stickers.reduce((sum, s) => sum + s.availableToTrade, 0);
  const progress = total > 0
    ? Math.round((owned / total) * 10000) / 100
    : 0;

  const summary = {
    total,
    owned,
    missing,
    duplicateItems,
    totalDuplicates,
    progress
  };

  if (view === 'repe') {
    return {
      updatedAt: new Date().toISOString(),
      summary: {
        duplicateItems,
        totalDuplicates
      },
      duplicatesList
    };
  }

  if (view === 'missing') {
    return {
      updatedAt: new Date().toISOString(),
      summary: {
        missing
      },
      missingList
    };
  }

  if (view === 'dashboard') {
    return {
      updatedAt: new Date().toISOString(),
      summary,
      duplicatesList
    };
  }

  return {
    updatedAt: new Date().toISOString(),
    summary,
    stickers,
    missingList,
    duplicatesList
  };
}

function getAllStickers() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const stickers = [];

  SHEETS.forEach(sheetName => {
    const sheet = ss.getSheetByName(sheetName);
    if (!sheet) return;

    const values = sheet.getDataRange().getValues();
    if (!values || values.length < 2) return;

    const headers = values[0];

    const idIndex = headers.indexOf('ID');
    const typeIndex = headers.indexOf('TIPO');
    const quantityIndex = headers.indexOf('CANTIDAD');

    const contextIndex = headers.indexOf('SELECCION') !== -1
      ? headers.indexOf('SELECCION')
      : headers.indexOf('SECCION');

    const nameIndex = headers.indexOf('NOMBRE_APELLIDO') !== -1
      ? headers.indexOf('NOMBRE_APELLIDO')
      : headers.indexOf('DESCRIPCION');

    if (idIndex === -1 || quantityIndex === -1) return;

    for (let i = 1; i < values.length; i++) {
      const row = values[i];
      const id = row[idIndex];

      if (!id) continue;

      const quantity = Number(row[quantityIndex]) || 0;

      const type = typeIndex !== -1 ? String(row[typeIndex] || '') : '';
      const rawName = nameIndex !== -1 ? String(row[nameIndex] || '') : '';

      let displayName = rawName;

      if (type && type !== 'NORMAL') {
        displayName = type + ' - ' + rawName;
      }

      stickers.push({
        id: String(id),
        section: sheetName,
        context: contextIndex !== -1 ? row[contextIndex] : '',
        type,
        name: displayName,
        rawName,
        quantity,
        hasSticker: quantity > 0,
        missing: quantity === 0,
        duplicate: quantity > 1,
        availableToTrade: Math.max(0, quantity - 1)
      });
    }
  });

  return stickers;
}