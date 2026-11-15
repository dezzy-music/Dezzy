// Dezzy · Fase 11 · piezas repetidas de los bocetos (barra de estado, gestos, mini reproductor, mapa)
(function () {
  var SB = '<div class="statusbar"><span>9:41</span><span class="ic"><span class="ms s16 f">signal_cellular_alt</span>' +
    '<span class="ms s16 f">wifi</span><span class="ms s16 f">battery_full</span></span></div>';
  var MINI = '<span class="art a4"></span><div class="tx"><div class="t1">De Música Ligera</div><div class="t2">Soda Stereo</div></div>' +
    '<span class="icon-btn"><span class="ms f s28">pause</span></span><span class="icon-btn"><span class="ms f">skip_next</span></span>' +
    '<span class="bar"><i></i></span>';
  var LAND = '<svg class="land" viewBox="0 0 360 700" aria-hidden="true"><path d="M20,60 L110,28 L210,24 L300,52 L318,110 L280,140 ' +
    'L262,190 L250,232 L228,250 L205,262 L192,290 L210,300 L198,322 L182,338 L196,356 L220,370 L268,384 L322,426 L334,476 L306,536 ' +
    'L268,584 L236,650 L218,690 L206,650 L198,590 L186,526 L176,466 L172,414 L188,384 L176,366 L156,350 L132,334 L106,300 L84,262 ' +
    'L56,212 L34,156 Z"/></svg><div class="grid"></div>';
  document.querySelectorAll('.phone').forEach(function (p) {
    p.insertAdjacentHTML('afterbegin', SB);
    p.insertAdjacentHTML('beforeend', '<div class="gesture"><i></i></div>');
  });
  document.querySelectorAll('[data-mini]').forEach(function (m) { m.classList.add('mini'); m.innerHTML = MINI; });
  document.querySelectorAll('.map').forEach(function (m) { m.insertAdjacentHTML('afterbegin', LAND); });
})();
