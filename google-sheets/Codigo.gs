/**
 * KDS Muhu → Google Sheets
 * Recibe las comandas despachadas desde la tablet del KDS y las guarda en las
 * pestañas "Comandas" y "Productos", con su turno (AM 07:00–14:30, PM 14:30–23:00)
 * y el tiempo de cocina y de barra. Si una comanda llega dos veces, se actualiza
 * (no se duplica).
 *
 * Instalación: Extensiones → Apps Script → pegar este código → Implementar →
 * Nueva implementación → Tipo: Aplicación web → Ejecutar como: Yo →
 * Quién tiene acceso: Cualquier usuario → Implementar → copiar la URL.
 */

const CLAVE = 'b64b96a9cf53f9560fc12b88';   // la misma que se pone en el KDS

function doPost(e) {
  let d;
  try { d = JSON.parse(e.postData.contents); } catch (err) { return salida({ ok: false, error: 'json' }); }
  if (d.clave !== CLAVE) return salida({ ok: false, error: 'clave' });

  const libro = SpreadsheetApp.getActiveSpreadsheet();
  const hojaC = libro.getSheetByName('Comandas');
  const hojaP = libro.getSheetByName('Productos');
  const lock = LockService.getScriptLock();
  lock.waitLock(20000);
  try {
    const ultima = hojaC.getLastRow();
    const filaDeClave = {};
    if (ultima > 1) {
      hojaC.getRange(2, 12, ultima - 1, 1).getValues()
        .forEach((v, i) => { if (v[0]) filaDeClave[String(v[0])] = i + 2; });
    }
    const nuevasC = [];
    const nuevasP = [];
    let actualizadas = 0;

    (d.filas || []).forEach(r => {
      const clave = r.fecha + '#' + r.id + '#' + r.entro;
      const dia = fecha(r.fecha, '00:00:00');
      const prods = r.productos || [];
      const turno = (r.turno === 'AM' || r.turno === 'PM') ? r.turno : (String(r.entro) < '14:30' ? 'AM' : 'PM');
      const fila = [r.id, dia, r.mesa || '', r.zona || '', r.titulo || '', r.encargado || '', r.mozo || '',
        fecha(r.fecha, r.entro), fecha(r.fecha, r.listo), Number(r.minutos),
        prods.map(p => p[0] + ' ' + p[1]).join(' | '), clave, turno,
        r.min_cocina == null ? '' : Number(r.min_cocina), r.min_barra == null ? '' : Number(r.min_barra)];
      if (filaDeClave[clave]) {
        hojaC.getRange(filaDeClave[clave], 1, 1, 15).setValues([fila]);
        actualizadas++;
        return;
      }
      filaDeClave[clave] = -1;
      nuevasC.push(fila);
      prods.forEach(p => nuevasP.push([dia, r.id, p[1], Number(String(p[0]).replace(',', '.')) || 1, Number(r.minutos), clave, turno]));
    });

    if (nuevasC.length) hojaC.getRange(hojaC.getLastRow() + 1, 1, nuevasC.length, 15).setValues(nuevasC);
    if (nuevasP.length) hojaP.getRange(hojaP.getLastRow() + 1, 1, nuevasP.length, 7).setValues(nuevasP);
    return salida({ ok: true, nuevas: nuevasC.length, actualizadas: actualizadas });
  } finally {
    lock.releaseLock();
  }
}

/** Prueba rápida desde el navegador: abre la URL y debe decir "KDS Muhu conectado". */
function doGet() {
  return salida({ ok: true, mensaje: 'KDS Muhu conectado' });
}

function fecha(dia, hora) {
  const d = String(dia).split('-').map(Number);
  const h = String(hora || '00:00:00').split(':').map(Number);
  return new Date(d[0], d[1] - 1, d[2], h[0] || 0, h[1] || 0, h[2] || 0);
}

function salida(o) {
  return ContentService.createTextOutput(JSON.stringify(o)).setMimeType(ContentService.MimeType.JSON);
}
