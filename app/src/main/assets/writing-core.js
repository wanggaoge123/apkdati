/* writing-core.js —— 统一「手写开宝箱」文字模块核心（全游戏共享）
 * 说明：本文件 100% 复用《文字模块接入指南》代码块①，识别算法/画布/判定逻辑未做任何改动。
 * 仅对外 API 保持：WritingCore.init / open / close / setParams。
 * 与安卓对接在 android-bridge.js 中完成（注入题库、回传结果、受锁死控制）。
 */
(function (global) {
  'use strict';

  /* ---------- 可调参数（对齐用，勿随意改）---------- */
  var HW_PASS_RATIO = 0.7;     // 匹配上 70% 以上标准笔画就算对
  var HW_SIM_THRESHOLD = 0.4;  // 单笔相似度阈值，越低越宽容
  var HW_PENALTY_TIMES = 5;    // 写错后需连续正确书写的遍数

  /* ---------- 模块状态 ---------- */
  var MODE = 'char';
  var hwChestOpen = false;
  var hwWordIndex = 0;
  var hwCurrent = null;
  var hwChars = [];
  var hwWriters = [];
  var hwStats = [];
  var hwStdData = {};
  var hwCurStroke = -1;
  var hwPenaltyMode = false;
  var hwPenaltyCount = 0;

  var hooks = { onPass: null, onSkip: null, onReward: null };
  var dom = {};

  /* ---------- DOM 引用 ---------- */
  function cacheDom() {
    dom.modal   = document.getElementById('hw-modal');
    dom.pinyin  = document.getElementById('hw-pinyin');
    dom.status  = document.getElementById('hw-status');
    dom.answer  = document.getElementById('hw-answer');
    dom.cell    = document.getElementById('hw-cell');
    dom.grid    = document.getElementById('hw-grid');
    dom.submit  = document.getElementById('hw-submit');
    dom.rewrite = document.getElementById('hw-rewrite');
    dom.help    = document.getElementById('hw-help');
  }

  /* ---------- 词库接口（统一）---------- */
  function getWordList() {
    return (typeof global.WORD_LIST !== 'undefined' && global.WORD_LIST) ? global.WORD_LIST : [];
  }
  function isRandomMode() {
    return (typeof global.IS_RANDOM_MODE !== 'undefined') ? !!global.IS_RANDOM_MODE : true;
  }
  function pickWord() {
    var list = getWordList();
    if (!list.length) return { hanzi: '天', pinyin: 'tiān' };
    if (isRandomMode()) return list[Math.floor(Math.random() * list.length)];
    var w = list[hwWordIndex % list.length];
    hwWordIndex++;
    return w;
  }

  /* ---------- 初始化 ---------- */
  function init(opts) {
    opts = opts || {};
    MODE = (opts.mode === 'word') ? 'word' : 'char';
    hooks.onPass = opts.onPass || null;
    hooks.onSkip = opts.onSkip || null;
    hooks.onReward = opts.onReward || null;
    cacheDom();
    if (dom.submit) dom.submit.addEventListener('click', function () { hwJudge(); });
    if (dom.rewrite) dom.rewrite.addEventListener('click', function () {
      if (!hwChestOpen) return;
      dom.status.textContent = '已清空，重新写';
      clearAll();
    });
    if (dom.help) dom.help.addEventListener('click', function () {
      if (!hwChestOpen) return;
      if (dom.answer) dom.answer.classList.remove('hidden');
      dom.status.textContent = '照着上面的字写一遍，写完点「提交」';
    });
  }

  /* ---------- 打开宝箱 ---------- */
  function open() {
    var offline = (typeof HanziWriter === 'undefined');
    if (offline) {
      // 离线降级：不再直接发奖关闭，而是正常显示"要写的字"，让用户手写；
      // 提交时 charPass 在 stdReady=false 时直接判对（见 charPass），实现离线宽松判定。
      dom.status.textContent = '⚠ 手写库未加载（离线模式）：请凭空写出下面的字，写完点「提交」';
    }
    hwChestOpen = true;
    hwPenaltyMode = false;
    hwPenaltyCount = 0;
    hwCurrent = pickWord();
    // 对接安卓：把当前出题字词暴露给外部桥，便于回传安卓写 Room（不影响识别算法）
    try { global.__currentHanzi = hwCurrent.hanzi; global.__currentMode = MODE; } catch (e) {}
    if (dom.pinyin) dom.pinyin.textContent = hwCurrent.pinyin;
    if (dom.answer) { dom.answer.textContent = hwCurrent.hanzi; dom.answer.classList.add('hidden'); }
    if (MODE === 'word') {
      hwChars = (hwCurrent.hanzi || '天').split('');
      if (dom.cell) dom.cell.style.display = 'none';
      if (dom.grid) dom.grid.style.display = 'flex';
      if (dom.status) dom.status.textContent = '请在每个格子里写出对应的字，写完点「提交」';
    } else {
      hwChars = [(hwCurrent.hanzi || '天').charAt(0)];
      if (dom.cell) {
        // 离线降级（无 HanziWriter）时保持 flex 居中显示"要写的字"，不要强制 block
        if (typeof HanziWriter === 'undefined') {
          dom.cell.style.display = 'flex';
          dom.cell.style.alignItems = 'center';
          dom.cell.style.justifyContent = 'center';
        } else {
          dom.cell.style.display = 'block';
        }
      }
      if (dom.grid) dom.grid.style.display = 'none';
      if (dom.status) dom.status.textContent = '请在田字格里写出：' + hwCurrent.pinyin;
    }
    if (dom.modal) dom.modal.classList.remove('hidden');
    requestAnimationFrame(function () { buildCells(); });
  }

  function close() {
    if (dom.modal) dom.modal.classList.add('hidden');
    hwChestOpen = false;
  }

  /* ---------- 建格（单字1格 / 词语多格）---------- */
  function buildCells() {
    hwWriters = [];
    hwStats = [];
    if (MODE === 'word' && dom.grid) {
      dom.grid.innerHTML = '';
      for (var i = 0; i < hwChars.length; i++) {
        var cell = document.createElement('div');
        cell.className = 'hw-cell';
        cell.id = 'hw-cell-' + i;
        var idx = document.createElement('span');
        idx.className = 'hw-idx';
        idx.textContent = (i + 1);
        cell.appendChild(idx);
        dom.grid.appendChild(cell);
        resetCell(i);
      }
    } else if (dom.cell) {
      resetCell(0, dom.cell);
    }
  }

  function resetCell(i, cellEl) {
    var cell = cellEl || document.getElementById('hw-cell-' + i);
    if (!cell) return;
    cell.querySelectorAll('svg').forEach(function (s) { s.remove(); });
    var ch = hwChars[i];
    if (!ch) return;
    var rect = cell.getBoundingClientRect();
    var size = Math.max(120, Math.min(240, Math.floor((rect.width || 200) - 10)));
    // 离线降级：HanziWriter 不可用时，不创建 SVG 轨迹，仅在格内显示"要写的字"文字，
    // 用户凭空手写、提交时按离线宽松判定（charPass 在 stdReady=false 时返回 true）。
    if (typeof HanziWriter === 'undefined') {
      cell.textContent = ch;
      cell.style.display = 'flex';
      cell.style.alignItems = 'center';
      cell.style.justifyContent = 'center';
      cell.style.fontSize = (size * 0.6) + 'px';
      cell.style.color = '#333';
      var st0 = { userStrokes: [], stdReady: false };
      hwStats[i] = st0;
      return;
    }
    var writer = HanziWriter.create(cell, ch, {
      width: size, height: size, padding: 10,
      showCharacter: false, showOutline: false,
      strokeColor: '#2c7be5', radicalColor: '#2c7be5', highlightColor: '#4caf50',
      drawingWidth: 18, strokeAnimationSpeed: 2, delayBetweenStrokes: 60
    });
    var st = { userStrokes: [], stdReady: !!hwStdData[ch] };
    hwStats[i] = st;
    hwWriters[i] = writer;
    var svg = cell.querySelector('svg');
    if (svg) {
      svg.addEventListener('pointerdown', (function (idx) {
        return function (e) { try { e.preventDefault(); } catch (err) {} startUserStroke(idx, e, svg); };
      })(i));
      svg.addEventListener('pointermove', (function (idx) {
        return function (e) { extendUserStroke(idx, e, svg); };
      })(i));
      svg.addEventListener('pointerup', (function (idx) {
        return function () { if (hwCurStroke === idx) hwCurStroke = -1; };
      })(i));
      svg.addEventListener('pointerleave', (function (idx) {
        return function () { if (hwCurStroke === idx) hwCurStroke = -1; };
      })(i));
    }
    if (hwStdData[ch]) { st.stdReady = true; }
    else {
      (function (c, si) {
        try {
          HanziWriter.loadCharacterData(c, function (data) {
            hwStdData[c] = (data && data.strokes && data.strokes.length) ? data : null;
            if (hwStats[si]) hwStats[si].stdReady = true;
          });
        } catch (e) { hwStdData[c] = null; if (hwStats[si]) hwStats[si].stdReady = true; }
      })(ch, i);
    }
  }

  /* ---------- 笔迹收集 ---------- */
  function svgPoint(e, svg) {
    var r = svg.getBoundingClientRect();
    var vbW = 1024, vbH = 1024;
    try {
      if (svg.viewBox && svg.viewBox.baseVal && svg.viewBox.baseVal.width) {
        vbW = svg.viewBox.baseVal.width; vbH = svg.viewBox.baseVal.height;
      }
    } catch (err) {}
    return {
      x: (e.clientX - r.left) / (r.width || 1) * vbW,
      y: (e.clientY - r.top) / (r.height || 1) * vbH
    };
  }
  function startUserStroke(idx, e, svg) {
    if (!hwStats[idx]) return;
    hwCurStroke = idx;
    if (!hwStats[idx].userStrokes) hwStats[idx].userStrokes = [];
    hwStats[idx].userStrokes.push([svgPoint(e, svg)]);
  }
  function extendUserStroke(idx, e, svg) {
    if (hwCurStroke !== idx || !hwStats[idx] || !hwStats[idx].userStrokes.length) return;
    var last = hwStats[idx].userStrokes[hwStats[idx].userStrokes.length - 1];
    var p = svgPoint(e, svg);
    var prev = last[last.length - 1];
    if (prev && Math.abs(p.x - prev.x) + Math.abs(p.y - prev.y) < 3) return;
    last.push(p);
  }

  /* ---------- 笔迹比对 ---------- */
  function strokeDist(a, b) { return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.y - b.y) * (a.y - b.y)); }
  function resampleStroke(points, n) {
    if (!points || points.length < 2) return points || [];
    var total = 0, i;
    for (i = 1; i < points.length; i++) total += strokeDist(points[i - 1], points[i]);
    if (total <= 0) return [points[0]];
    var step = total / (n - 1), out = [points[0]], acc = 0, si = 0;
    for (i = 1; i < n - 1; i++) {
      var target = step * i;
      while (si < points.length - 2 && acc + strokeDist(points[si], points[si + 1]) < target) {
        acc += strokeDist(points[si], points[si + 1]); si++;
      }
      var seg = strokeDist(points[si], points[si + 1]) || 1;
      var t = Math.min(1, Math.max(0, (target - acc) / seg));
      out.push({ x: points[si].x + (points[si + 1].x - points[si].x) * t,
                 y: points[si].y + (points[si + 1].y - points[si].y) * t });
    }
    out.push(points[points.length - 1]);
    return out;
  }
  function normalizeStroke(points) {
    if (!points || !points.length) return [];
    var cx = 0, cy = 0, i;
    for (i = 0; i < points.length; i++) { cx += points[i].x; cy += points[i].y; }
    cx /= points.length; cy /= points.length;
    var pts = points.map(function (p) { return { x: p.x - cx, y: p.y - cy }; });
    var maxD = 0;
    pts.forEach(function (p) { var d = Math.sqrt(p.x * p.x + p.y * p.y); if (d > maxD) maxD = d; });
    if (maxD > 0) pts.forEach(function (p) { p.x /= maxD; p.y /= maxD; });
    return pts;
  }
  function strokeSim(a, b) {
    var A = normalizeStroke(resampleStroke(a, 24));
    var B = normalizeStroke(resampleStroke(b, 24));
    if (A.length < 2 || B.length < 2) return 0;
    var s = 0;
    for (var i = 0; i < 24; i++) s += A[i].x * B[i].x + A[i].y * B[i].y;
    return Math.max(0, s / 24);
  }
  function matchStrokesOrder(userStrokes, stdStrokes, thr) {
    var assignments = [], matched = 0;
    var used = new Array(stdStrokes.length).fill(false);
    for (var k = 0; k < userStrokes.length; k++) {
      var best = -1, bi = -1;
      for (var j = 0; j < stdStrokes.length; j++) {
        if (used[j]) continue;
        var s = strokeSim(userStrokes[k], stdStrokes[j].points);
        if (s > best) { best = s; bi = j; }
      }
      if (bi >= 0 && best >= thr) { used[bi] = true; matched++; assignments.push(bi); }
      else assignments.push(-1);
    }
    var ratio = matched / stdStrokes.length;
    var inversions = 0;
    var seq = assignments.filter(function (x) { return x >= 0; });
    for (var a = 1; a < seq.length; a++) { if (seq[a] < seq[a - 1]) inversions++; }
    return { matched: matched, ratio: ratio, inversions: inversions };
  }

  /* ---------- 单字是否通过 ---------- */
  function charPass(i) {
    var st = hwStats[i];
    if (!st || !st.userStrokes || st.userStrokes.length === 0) return false;
    if (!st.stdReady) return true;
    var std = hwStdData[hwChars[i]];
    if (!std || !std.strokes || !std.strokes.length) return true;
    var res = matchStrokesOrder(st.userStrokes, std.strokes, HW_SIM_THRESHOLD);
    return res.ratio >= HW_PASS_RATIO;
  }

  /* ---------- 提交：统一判定 ---------- */
  function hwJudge() {
    if (!hwChestOpen) return;
    var allDone = true, firstFail = -1;
    for (var i = 0; i < hwChars.length; i++) {
      if (!charPass(i)) { allDone = false; if (firstFail < 0) firstFail = i; }
    }
    if (!allDone) {
      hwPenaltyMode = true;
      hwPenaltyCount = 0;
      var names = [];
      for (var j = 0; j < hwChars.length; j++) { if (!charPass(j)) { resetCell(j); names.push('第' + (j + 1) + '个字'); } }
      dom.status.textContent = '✗ ' + names.join('、') + ' 没写对，请连续正确书写 ' + HW_PENALTY_TIMES + ' 遍解锁（点「重写」清空）';
      return;
    }
    if (!hwPenaltyMode) { dom.status.textContent = '✓ 写对啦！宝箱解锁'; finish(true); }
    else {
      hwPenaltyCount++;
      if (hwPenaltyCount >= HW_PENALTY_TIMES) { dom.status.textContent = '✓ 连续写对 ' + HW_PENALTY_TIMES + ' 遍！宝箱解锁'; finish(true); }
      else { dom.status.textContent = '✓ 第 ' + hwPenaltyCount + '/' + HW_PENALTY_TIMES + ' 遍正确，继续！'; setTimeout(clearAll, 700); }
    }
  }

  /* ---------- 收尾 ---------- */
  function finish(success) {
    close();
    if (hooks.onReward) { try { hooks.onReward(50); } catch (e) {} }
    if (success && hooks.onPass) hooks.onPass();
    else if (!success && hooks.onSkip) hooks.onSkip();
  }

  /* ---------- 清空所有格重来 ---------- */
  function clearAll() {
    if (!hwChestOpen) return;
    for (var i = 0; i < hwChars.length; i++) resetCell(i);
  }

  global.WritingCore = {
    init: init,
    open: open,
    close: close,
    setParams: function (p) {
      if (typeof p.passRatio === 'number') HW_PASS_RATIO = p.passRatio;
      if (typeof p.simThreshold === 'number') HW_SIM_THRESHOLD = p.simThreshold;
      if (typeof p.penaltyTimes === 'number') HW_PENALTY_TIMES = p.penaltyTimes;
    }
  };
})(window);
