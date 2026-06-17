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

const PANINI_CACHE_KEYS = [
  'panini_dashboard',
  'panini_repe',
  'panini_missing',
  'panini_full'
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

function doPost(e) {
  try {
    const request = parseJsonRequest(e);
    const configuredSecret = PropertiesService.getScriptProperties().getProperty('APP_SECRET');

    if (!configuredSecret || request.secret !== configuredSecret) {
      return jsonResponse({
        ok: false,
        status: 'unauthorized',
        message: 'No autorizado.'
      });
    }

    if (request.action !== 'addSticker') {
      if (request.action === 'addStickerBatch') {
        return jsonResponse(addStickerBatch(request.stickerIds, request.confirmDuplicates || {}));
      }

      return jsonResponse({
        ok: false,
        status: 'invalid_action',
        message: 'Acción no soportada.'
      });
    }

    return jsonResponse(addSticker(request.stickerId, request.confirmDuplicate === true));
  } catch (error) {
    return jsonResponse({
      ok: false,
      status: 'error',
      message: error && error.message ? error.message : 'Error inesperado.'
    });
  }
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
    const columns = getStickerColumns(headers);

    if (columns.id === -1 || columns.quantity === -1) return;

    for (let i = 1; i < values.length; i++) {
      const row = values[i];
      const id = row[columns.id];

      if (!id) continue;

      const quantity = Number(row[columns.quantity]) || 0;
      const sticker = buildStickerObject(row, columns, sheetName, quantity);

      stickers.push(sticker);
    }
  });

  return stickers;
}

function addSticker(inputStickerId, confirmDuplicate) {
  const stickerId = normalizeStickerId(inputStickerId);

  if (!stickerId) {
    return {
      ok: false,
      status: 'invalid_id',
      message: 'ID de cromo inválido.'
    };
  }

  const found = findStickerRow(stickerId);

  if (!found) {
    return {
      ok: false,
      status: 'not_found',
      message: 'No se encontró el ID ' + stickerId + '.'
    };
  }

  const oldQuantity = Number(found.row[found.columns.quantity]) || 0;

  if (oldQuantity > 0 && !confirmDuplicate) {
    return {
      ok: false,
      status: 'already_exists',
      requiresConfirmation: true,
      message: 'Este cromo ya existe. ¿Quieres sumar +1?',
      sticker: buildStickerResponse(found, oldQuantity, oldQuantity)
    };
  }

  const newQuantity = oldQuantity + 1;
  found.sheet.getRange(found.rowNumber, found.columns.quantity + 1).setValue(newQuantity);

  SpreadsheetApp.flush();
  clearPaniniCache();

  return {
    ok: true,
    status: oldQuantity === 0 ? 'added' : 'incremented',
    message: oldQuantity === 0
      ? 'Cromo agregado correctamente.'
      : 'Cantidad actualizada correctamente.',
    sticker: buildStickerResponse(found, oldQuantity, newQuantity)
  };
}

function addStickerBatch(inputStickerIds, confirmDuplicates) {
  const stickerIds = Array.isArray(inputStickerIds) ? inputStickerIds : [];
  const index = buildStickerIndex();
  const results = [];
  let hasUpdates = false;

  stickerIds.forEach(inputStickerId => {
    const stickerId = normalizeStickerId(inputStickerId);

    if (!stickerId) {
      results.push({
        id: '',
        status: 'invalid_id',
        message: 'ID de cromo inválido.'
      });
      return;
    }

    const found = index[stickerId];

    if (!found) {
      results.push({
        id: stickerId,
        status: 'not_found',
        message: 'No se encontró el ID ' + stickerId + '.'
      });
      return;
    }

    const oldQuantity = Number(found.row[found.columns.quantity]) || 0;
    const confirmDuplicate = confirmDuplicates && confirmDuplicates[stickerId] === true;

    if (oldQuantity > 0 && !confirmDuplicate) {
      results.push({
        id: stickerId,
        status: 'already_exists',
        requiresConfirmation: true,
        message: 'Este cromo ya existe. ¿Quieres sumar +1?',
        oldQuantity,
        newQuantity: oldQuantity
      });
      return;
    }

    const newQuantity = oldQuantity + 1;
    found.sheet.getRange(found.rowNumber, found.columns.quantity + 1).setValue(newQuantity);
    found.row[found.columns.quantity] = newQuantity;
    hasUpdates = true;

    results.push({
      id: stickerId,
      status: oldQuantity === 0 ? 'added' : 'incremented',
      message: oldQuantity === 0
        ? 'Cromo agregado correctamente.'
        : 'Cantidad actualizada correctamente.',
      oldQuantity,
      newQuantity
    });
  });

  if (hasUpdates) {
    SpreadsheetApp.flush();
    clearPaniniCache();
  }

  return {
    ok: true,
    status: 'batch_processed',
    message: 'Lista procesada.',
    results,
    summary: buildBatchSummary(results)
  };
}

function buildStickerIndex() {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  const index = {};

  SHEETS.forEach(sheetName => {
    const sheet = ss.getSheetByName(sheetName);
    if (!sheet) return;

    const values = sheet.getDataRange().getValues();
    if (!values || values.length < 2) return;

    const headers = values[0];
    const columns = getStickerColumns(headers);

    if (columns.id === -1 || columns.quantity === -1) return;

    for (let i = 1; i < values.length; i++) {
      const row = values[i];
      const stickerId = normalizeStickerId(row[columns.id]);
      if (!stickerId) continue;

      index[stickerId] = {
        sheet,
        sheetName,
        row,
        rowNumber: i + 1,
        columns
      };
    }
  });

  return index;
}

function buildBatchSummary(results) {
  return results.reduce((summary, item) => {
    if (item.status === 'added') {
      summary.added++;
    } else if (item.status === 'incremented') {
      summary.incremented++;
    } else if (item.status === 'already_exists') {
      summary.alreadyExists++;
    } else if (item.status === 'not_found') {
      summary.notFound++;
    } else {
      summary.errors++;
    }

    return summary;
  }, {
    added: 0,
    incremented: 0,
    alreadyExists: 0,
    notFound: 0,
    errors: 0
  });
}

function findStickerRow(stickerId) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();

  for (let sheetIndex = 0; sheetIndex < SHEETS.length; sheetIndex++) {
    const sheetName = SHEETS[sheetIndex];
    const sheet = ss.getSheetByName(sheetName);
    if (!sheet) continue;

    const values = sheet.getDataRange().getValues();
    if (!values || values.length < 2) continue;

    const headers = values[0];
    const columns = getStickerColumns(headers);

    if (columns.id === -1 || columns.quantity === -1) continue;

    for (let i = 1; i < values.length; i++) {
      const row = values[i];

      if (normalizeStickerId(row[columns.id]) === stickerId) {
        return {
          sheet,
          sheetName,
          row,
          rowNumber: i + 1,
          columns
        };
      }
    }
  }

  return null;
}

function getStickerColumns(headers) {
  return {
    id: headers.indexOf('ID'),
    type: headers.indexOf('TIPO'),
    quantity: headers.indexOf('CANTIDAD'),
    context: headers.indexOf('SELECCION') !== -1
      ? headers.indexOf('SELECCION')
      : headers.indexOf('SECCION'),
    name: headers.indexOf('NOMBRE_APELLIDO') !== -1
      ? headers.indexOf('NOMBRE_APELLIDO')
      : headers.indexOf('DESCRIPCION')
  };
}

function buildStickerObject(row, columns, sheetName, quantity) {
  const type = columns.type !== -1 ? String(row[columns.type] || '') : '';
  const rawName = columns.name !== -1 ? String(row[columns.name] || '') : '';

  let displayName = rawName;

  if (type && type !== 'NORMAL') {
    displayName = type + ' - ' + rawName;
  }

  return {
    id: String(row[columns.id]),
    section: sheetName,
    context: columns.context !== -1 ? row[columns.context] : '',
    type,
    name: displayName,
    rawName,
    quantity,
    hasSticker: quantity > 0,
    missing: quantity === 0,
    duplicate: quantity > 1,
    availableToTrade: Math.max(0, quantity - 1)
  };
}

function buildStickerResponse(found, oldQuantity, newQuantity) {
  const sticker = buildStickerObject(found.row, found.columns, found.sheetName, newQuantity);

  return {
    id: normalizeStickerId(sticker.id),
    name: sticker.name,
    context: sticker.context,
    oldQuantity,
    newQuantity
  };
}

function normalizeStickerId(input) {
  if (input === null || input === undefined) {
    return '';
  }

  return String(input)
    .trim()
    .toUpperCase()
    .replace(/[\s_-]+/g, '');
}

function clearPaniniCache() {
  const cache = CacheService.getScriptCache();

  PANINI_CACHE_KEYS.forEach(key => {
    cache.remove(key);
  });
}

function parseJsonRequest(e) {
  if (!e || !e.postData || !e.postData.contents) {
    return {};
  }

  return JSON.parse(e.postData.contents);
}

function jsonResponse(payload) {
  return ContentService
    .createTextOutput(JSON.stringify(payload))
    .setMimeType(ContentService.MimeType.JSON);
}
