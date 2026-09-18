'use strict';
/* ui.js — screens, rendering and the host/guest glue.
   Roles: 'local' (practice, everything in-page), 'host' (runs the Game and
   pushes per-seat views to the guest), 'guest' (renders views, sends intents). */

const $ = s => document.querySelector(s);
const $$ = s => Array.from(document.querySelectorAll(s));

function syncDoudizhuViewportHeight() {
  document.documentElement.style.setProperty(
    '--ddz-viewport-height',
    `${Math.max(320, window.innerHeight)}px`
  );
}

syncDoudizhuViewportHeight();
window.addEventListener('resize', syncDoudizhuViewportHeight);

const App = {
  role: null,
  name: '',
  cfg: null,
  game: null,
  peer: null,
  conns: {},        // host, in-game: seat -> DataConnection
  guests: [],       // host, lobby: [{conn, name}] in seat order (seat = idx + 1)
  guestConn: null,  // guest: connection to host
  code: null,
  started: false,
  view: null,
  selected: new Set(),
  bubbles: {},
};

const BCI_TYPE_LABELS = {
  single: '单张', pair: '对子', trio: '三张', trio_single: '三带一', trio_pair: '三带二',
  straight: '顺子', pair_straight: '连对', plane: '飞机', plane_single: '飞机带单',
  plane_pair: '飞机带对', four_two: '四带二', four_two_pairs: '四带两对',
  bomb: '炸弹', rocket: '王炸',
};

const BciScan = {
  cursor: new DdzScanCursor(),
  intervalMs: 1500,
  timer: null,
  viewSignature: null,
  stack: [],
  settingsReturn: null,
  confirmReturn: null,
  pendingAction: null,
  confirmationEnabled: true,
};

try { BciScan.confirmationEnabled = localStorage.getItem('ddz_bci_confirm') !== '0'; } catch (e) { }

function bciViewSignature(v) {
  const combo = v.trick && v.trick.combo;
  return [
    v.roundNo, v.state, v.actor, v.mySeat, v.currentBid,
    v.trick && v.trick.seat,
    combo && combo.type, combo && combo.rank, combo && combo.len, combo && combo.n,
    v.myHand.map(card => card.id).join(','),
  ].join('|');
}

function bciSetPhase(phase, candidates, orientation, rememberParent) {
  if (rememberParent) BciScan.stack.push(BciScan.cursor.snapshot());
  BciScan.cursor.setPhase(phase, candidates, orientation || 'horizontal');
  bciRestartTimer();
  bciRender();
}

function bciRestore(snapshot) {
  BciScan.cursor.restore(snapshot);
  bciRestartTimer();
  bciRender();
}

function bciRestartTimer() {
  if (BciScan.timer) clearInterval(BciScan.timer);
  BciScan.timer = null;
  if (!BciScan.cursor.candidates.length) return;
  BciScan.timer = setInterval(() => {
    BciScan.cursor.advance();
    bciRender();
  }, BciScan.intervalMs);
}

function bciSetInterval(value) {
  const parsed = Number(value);
  if (!Number.isFinite(parsed)) return;
  BciScan.intervalMs = Math.max(1100, Math.min(3000, Math.round(parsed)));
  bciRestartTimer();
}

function bciRank(rank) { return rankLabel(rank); }

function bciRemoveBody(play, rank, count, length) {
  const remaining = play.slice();
  const first = rank - (length || 1) + 1;
  for (let r = first; r <= rank; r++) {
    for (let i = 0; i < count; i++) remaining.splice(remaining.indexOf(r), 1);
  }
  return remaining;
}

function bciRankList(ranks, pairUnits) {
  const grouped = counts(ranks);
  return Object.keys(grouped).map(Number).sort((a, b) => a - b).map(rank => {
    const n = grouped[rank];
    if (pairUnits && n === 2) return `对${bciRank(rank)}`;
    return Array(n).fill(bciRank(rank)).join('');
  }).join('、');
}

function bciMoveLabel(move, attachmentOnly) {
  const c = move.combo;
  const type = BCI_TYPE_LABELS[c.type] || c.type;
  if (c.type === 'single') return bciRank(c.rank);
  if (c.type === 'pair') return `对${bciRank(c.rank)}`;
  if (c.type === 'trio') return `三个${bciRank(c.rank)}`;
  if (c.type === 'bomb') return `${bciRank(c.rank)}炸弹`;
  if (c.type === 'rocket') return '王炸';
  if (c.type === 'straight' || c.type === 'pair_straight' || c.type === 'plane') {
    const first = c.rank - c.len + 1;
    return `${type} ${bciRank(first)}-${bciRank(c.rank)}`;
  }
  let bodyCount = 3;
  let bodyLength = 1;
  if (c.type.startsWith('plane_')) bodyLength = c.len;
  if (c.type.startsWith('four_')) bodyCount = 4;
  const wings = bciRemoveBody(move.play, c.rank, bodyCount, bodyLength);
  const wingText = bciRankList(wings, c.type === 'trio_pair' || c.type === 'plane_pair' || c.type === 'four_two_pairs');
  if (attachmentOnly) return `带 ${wingText}`;
  const first = c.rank - bodyLength + 1;
  const body = bodyLength > 1 ? `${bciRank(first)}-${bciRank(c.rank)}` : bciRank(c.rank);
  return `${type} ${body}＋${wingText}`;
}

function bciMainLabel(move) {
  const c = move.combo;
  if (c.type.startsWith('plane_')) {
    return `飞机主体 ${bciRank(c.rank - c.len + 1)}-${bciRank(c.rank)}`;
  }
  if (c.type.startsWith('four_')) return `四个${bciRank(c.rank)}`;
  return `三个${bciRank(c.rank)}`;
}

function bciMoveCandidate(move, attachmentOnly) {
  return {
    kind: 'move', move,
    label: bciMoveLabel(move, attachmentOnly),
    style: isBombMove(move) ? 'bomb' : '',
  };
}

function bciPhaseTitle(phase) {
  if (phase === 'confirm_inline') {
    const action = BciScan.pendingAction;
    return action && action.kind === 'play' ? `确认出牌：${bciMoveLabel(action.move, false)}` : '确认不出';
  }
  return ({
    bidding: '抢地主', follow: '选择跟牌', lead_type: '选择牌型',
    choose_length: '选择长度', choose_main: '选择主体', choose_move: '选择牌组',
    settings: '游戏设置', confirm: '再次确认', waiting: '等待对方', settle: '本局结束',
  })[phase] || '耳机选择';
}

function bciClearVisualFocus() {
  $$('.bci-focus').forEach(el => el.classList.remove('bci-focus'));
  $$('#my-hand .bci-preview').forEach(el => el.classList.remove('bci-preview'));
}

function bciPreview(move) {
  if (!move || !App.view) return;
  const cards = materialize(App.view.myHand, move.play, null) || [];
  for (const card of cards) {
    $(`#my-hand .card[data-id="${card.id}"]`)?.classList.add('bci-preview');
  }
}

function bciRender() {
  const phaseEl = $('#bci-phase');
  const directionEl = $('#bci-direction');
  const wrap = $('#bci-candidates');
  if (!phaseEl || !directionEl || !wrap) return;
  bciClearVisualFocus();
  const cursor = BciScan.cursor;
  const current = cursor.current();
  phaseEl.textContent = bciPhaseTitle(cursor.phase);
  directionEl.textContent = cursor.orientation === 'vertical'
    ? (cursor.direction > 0 ? '向下轮转' : '向上轮转')
    : (cursor.direction > 0 ? '向右轮转' : '向左轮转');

  if (cursor.phase === 'waiting') {
    wrap.className = 'bci-candidates';
    wrap.innerHTML = '<span class="status">轮到玩家后自动开始扫描</span>';
    return;
  }

  if (cursor.phase === 'settle') {
    wrap.innerHTML = '<span class="status">结算选项正在轮转</span>';
    const ids = { again: '#btn-again' };
    if (current) $(ids[current.kind])?.classList.add('bci-focus');
    return;
  }

  if (cursor.phase === 'settings') {
    wrap.innerHTML = '<span class="status">设置选项正在纵向轮转</span>';
    const ids = {
      return_selection: '#btn-return-selection', sound: '#btn-sound-toggle',
      confirmation: '#btn-confirm-toggle', restart: '#btn-restart-game', exit: '#btn-app-exit',
    };
    if (current) $(ids[current.kind])?.classList.add('bci-focus');
    return;
  }

  if (cursor.phase === 'confirm') {
    wrap.innerHTML = '<span class="status">请在确认窗口中选择</span>';
    if (current) $(current.kind === 'confirm' ? '#btn-bci-confirm' : '#btn-bci-cancel')?.classList.add('bci-focus');
    return;
  }

  wrap.className = 'bci-candidates horizontal';
  wrap.innerHTML = cursor.candidates.map((candidate, index) =>
    `<button type="button" class="bci-choice ${candidate.style || ''} ${index === cursor.index ? 'bci-focus' : ''}" data-bci-index="${index}">${esc(candidate.label)}</button>`
  ).join('');
  $$('[data-bci-index]').forEach(button => {
    button.onclick = () => bciActivate(cursor.candidates[+button.dataset.bciIndex]);
  });
  const focused = wrap.querySelector('.bci-focus');
  if (focused) focused.scrollIntoView({ block: 'nearest', inline: 'center' });
  if (current && current.move) bciPreview(current.move);
  else if (cursor.phase === 'confirm_inline' && BciScan.pendingAction?.kind === 'play') {
    bciPreview(BciScan.pendingAction.move);
  }
}

function bciWait(phase) {
  BciScan.stack = [];
  BciScan.cursor.setPhase(phase || 'waiting', [], 'horizontal');
  bciRestartTimer();
  bciRender();
}

function bciStartBidding(v) {
  const candidates = [{ kind: 'bid', value: 0, label: '不叫', style: 'pass' }];
  for (let score = v.currentBid + 1; score <= 3; score++) {
    candidates.push({ kind: 'bid', value: score, label: `${score}分` });
  }
  candidates.push({ kind: 'settings', label: '游戏设置', style: 'settings' });
  bciSetPhase('bidding', candidates, 'horizontal', false);
}

function bciSequenceLength(move) { return move.combo.len; }

function bciShowMoveOptions(moves, attachmentOnly, rememberParent) {
  const candidates = sortBciMoves(moves, App.view.myHand.map(card => card.r))
    .map(move => bciMoveCandidate(move, attachmentOnly));
  if (BciScan.stack.length || rememberParent) candidates.push({ kind: 'back', label: '返回上一步' });
  candidates.push({ kind: 'settings', label: '游戏设置', style: 'settings' });
  bciSetPhase('choose_move', candidates, 'horizontal', rememberParent);
}

function bciOpenComposite(moves, rememberParent) {
  const groups = new Map();
  for (const move of moves) {
    const key = bciMainKey(move);
    if (!groups.has(key)) groups.set(key, []);
    groups.get(key).push(move);
  }
  if (groups.size === 1) {
    bciShowMoveOptions([...groups.values()][0], true, rememberParent);
    return;
  }
  const candidates = [...groups.values()].map(group => ({
    kind: 'main', moves: group, label: bciMainLabel(group[0]),
  }));
  candidates.push({ kind: 'back', label: '返回上一步' });
  candidates.push({ kind: 'settings', label: '游戏设置', style: 'settings' });
  bciSetPhase('choose_main', candidates, 'horizontal', rememberParent);
}

function bciOpenType(type, moves) {
  const chosen = moves.filter(move => move.combo.type === type);
  const sequence = type === 'straight' || type === 'pair_straight' || type === 'plane';
  const lengths = [...new Set(chosen.map(bciSequenceLength))].sort((a, b) => a - b);
  if (sequence && chosen.length > 6 && lengths.length > 1) {
    const candidates = lengths.map(length => ({
      kind: 'length', type, length, moves: chosen.filter(move => bciSequenceLength(move) === length),
      label: type === 'straight' ? `${length}张顺子` : type === 'pair_straight' ? `${length}连对` : `${length}连飞机`,
    }));
    candidates.push({ kind: 'back', label: '返回上一步' });
    candidates.push({ kind: 'settings', label: '游戏设置', style: 'settings' });
    bciSetPhase('choose_length', candidates, 'horizontal', true);
    return;
  }
  if (BCI_COMPOSITE_TYPES.has(type)) bciOpenComposite(chosen, true);
  else bciShowMoveOptions(chosen, false, true);
}

function bciStartLead(v) {
  const ranks = v.myHand.map(card => card.r);
  const moves = sortBciMoves(allBciMoves(ranks, null), ranks);
  const available = new Set(moves.map(move => move.combo.type));
  const candidates = [];
  if (moves.length) candidates.push({ kind: 'move', move: moves[0], label: `推荐：${bciMoveLabel(moves[0], false)}` });
  for (const type of BCI_TYPE_ORDER) {
    if (available.has(type)) candidates.push({ kind: 'type', type, moves, label: BCI_TYPE_LABELS[type] });
  }
  candidates.push({ kind: 'settings', label: '游戏设置', style: 'settings' });
  bciSetPhase('lead_type', candidates, 'horizontal', false);
}

function bciStartFollow(v, prev) {
  const ranks = v.myHand.map(card => card.r);
  const moves = sortBciMoves(allBciMoves(ranks, prev), ranks);
  const normal = moves.filter(move => !isBombMove(move));
  const bombs = moves.filter(isBombMove);
  const candidates = [{
    kind: moves.length ? 'pass' : 'forced_pass',
    label: moves.length ? '不出' : '不出（无牌可跟）',
    style: 'pass',
  }];

  if (BCI_COMPOSITE_TYPES.has(prev.type) && normal.length) {
    const groups = new Map();
    for (const move of normal) {
      const key = bciMainKey(move);
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key).push(move);
    }
    if (groups.size === 1) {
      for (const move of [...groups.values()][0]) candidates.push(bciMoveCandidate(move, true));
    } else {
      for (const group of groups.values()) {
        if (group.length === 1) candidates.push(bciMoveCandidate(group[0], false));
        else candidates.push({ kind: 'main', moves: group, label: bciMainLabel(group[0]) });
      }
    }
  } else {
    normal.forEach(move => candidates.push(bciMoveCandidate(move, false)));
  }
  bombs.forEach(move => candidates.push(bciMoveCandidate(move, false)));
  candidates.push({ kind: 'settings', label: '游戏设置', style: 'settings' });
  bciSetPhase('follow', candidates, 'horizontal', false);
}

function bciStartSettle() {
  bciSetPhase('settle', [
    { kind: 'again', label: '再来一次' },
  ], 'horizontal', false);
}

function bciSyncForView(v) {
  if (!v) return;
  const signature = bciViewSignature(v);
  if (BciScan.cursor.phase === 'settings' || BciScan.cursor.phase === 'confirm' ||
      BciScan.cursor.phase === 'confirm_inline') {
    BciScan.viewSignature = signature;
    bciRender();
    return;
  }
  if (signature === BciScan.viewSignature && BciScan.cursor.phase !== 'waiting') {
    bciRender();
    return;
  }
  BciScan.viewSignature = signature;
  BciScan.stack = [];
  if (v.state === 'settle') { bciStartSettle(); return; }
  if (v.actor !== v.mySeat) { bciWait('waiting'); return; }
  if (v.state === 'bidding') { bciStartBidding(v); return; }
  if (v.state === 'playing') {
    const prev = prevFor(v);
    if (prev) bciStartFollow(v, prev); else bciStartLead(v);
    return;
  }
  bciWait('waiting');
}

function bciBack() {
  const snapshot = BciScan.stack.pop();
  if (snapshot) bciRestore(snapshot);
}

function bciSettingsCandidates() {
  return [
    { kind: 'return_selection', label: '返回选择' },
    { kind: 'sound', label: `声音：${Snd.enabled && Bgm.enabled ? '开' : '关'}` },
    { kind: 'confirmation', label: `二次确认：${BciScan.confirmationEnabled ? '开' : '关'}` },
    { kind: 'restart', label: '重新开始' },
    { kind: 'exit', label: '退出斗地主' },
  ];
}

function bciEnterSettings() {
  if (BciScan.cursor.phase !== 'settings') {
    BciScan.settingsReturn = {
      cursor: BciScan.cursor.snapshot(), stack: BciScan.stack.slice(),
      signature: bciViewSignature(App.view),
    };
  }
  $('#settings-modal').classList.remove('hidden');
  bciSetPhase('settings', bciSettingsCandidates(), 'vertical', false);
}

function bciReturnFromSettings() {
  $('#settings-modal').classList.add('hidden');
  const saved = BciScan.settingsReturn;
  BciScan.settingsReturn = null;
  if (saved && saved.signature === bciViewSignature(App.view)) {
    BciScan.stack = saved.stack.slice();
    bciRestore(saved.cursor);
  } else {
    BciScan.viewSignature = null;
    bciSyncForView(App.view);
  }
}

function bciSetConfirmationEnabled(enabled) {
  BciScan.confirmationEnabled = !!enabled;
  try { localStorage.setItem('ddz_bci_confirm', enabled ? '1' : '0'); } catch (e) { }
  $('#settings-confirm-label').textContent = `二次确认：${enabled ? '开' : '关'}`;
}

function bciShowConfirmation(action) {
  BciScan.pendingAction = action;
  BciScan.confirmReturn = { cursor: BciScan.cursor.snapshot(), stack: BciScan.stack.slice() };
  const title = action.kind === 'play' ? `确定要出“${bciMoveLabel(action.move, false)}”吗？`
    : action.kind === 'pass' ? '确定要不出吗？'
      : action.kind === 'restart' ? '确定要重新开始吗？' : '确定要退出斗地主吗？';
  if (action.kind === 'play' || action.kind === 'pass') {
    $('#bci-confirm-modal').classList.add('hidden');
    bciSetPhase('confirm_inline', [
      { kind: 'confirm', label: action.kind === 'play' ? '确定出牌' : '确定不出', style: 'confirm-inline' },
      { kind: 'cancel', label: '返回选择', style: 'cancel-inline' },
    ], 'horizontal', false);
    return;
  }

  $('#bci-confirm-title').textContent = title;
  {
    const actionText = action.kind === 'pass' ? '不出'
      : action.kind === 'restart' ? '重新发牌' : '返回 APP 首页';
    $('#bci-confirm-cards').innerHTML = `<strong>${actionText}</strong>`;
  }
  $('#bci-confirm-modal').classList.remove('hidden');
  bciSetPhase('confirm', [
    { kind: 'confirm', label: '确定' },
    { kind: 'cancel', label: '返回选择' },
  ], 'horizontal', false);
}

function bciCancelConfirmation() {
  $('#bci-confirm-modal').classList.add('hidden');
  BciScan.pendingAction = null;
  const saved = BciScan.confirmReturn;
  BciScan.confirmReturn = null;
  if (saved) {
    BciScan.stack = saved.stack.slice();
    bciRestore(saved.cursor);
  }
}

function restartFixedPractice() {
  $('#settings-modal').classList.add('hidden');
  $('#bci-confirm-modal').classList.add('hidden');
  if (App.game) App.game.stop();
  BciScan.viewSignature = null;
  BciScan.settingsReturn = null;
  BciScan.confirmReturn = null;
  BciScan.pendingAction = null;
  startPractice();
}

function bciExecute(action) {
  if (!action) return;
  if (action.kind === 'play') {
    const cards = materialize(App.view.myHand, action.move.play, null);
    if (cards) {
      App.selected.clear();
      act('play', { ids: cards.map(card => card.id) });
    }
  } else if (action.kind === 'pass') {
    App.selected.clear();
    act('pass');
  } else if (action.kind === 'restart') {
    restartFixedPractice();
  } else if (action.kind === 'again') {
    act('again');
  } else if (action.kind === 'exit') {
    requestAppExit();
  }
}

function bciRequest(action) {
  if (BciScan.confirmationEnabled) bciShowConfirmation(action);
  else bciExecute(action);
}

function bciConfirmPending() {
  const action = BciScan.pendingAction;
  BciScan.pendingAction = null;
  BciScan.confirmReturn = null;
  $('#bci-confirm-modal').classList.add('hidden');
  BciScan.cursor.setPhase('waiting', [], 'horizontal');
  BciScan.viewSignature = null;
  bciRestartTimer();
  bciExecute(action);
}

function bciActivate(candidate) {
  if (!candidate) return;
  if (candidate.kind === 'bid') { act('bid', { v: candidate.value }); return; }
  if (candidate.kind === 'pass') { bciRequest({ kind: 'pass' }); return; }
  if (candidate.kind === 'forced_pass') { bciExecute({ kind: 'pass' }); return; }
  if (candidate.kind === 'move') { bciRequest({ kind: 'play', move: candidate.move }); return; }
  if (candidate.kind === 'settings') { bciEnterSettings(); return; }
  if (candidate.kind === 'back') { bciBack(); return; }
  if (candidate.kind === 'type') { bciOpenType(candidate.type, candidate.moves); return; }
  if (candidate.kind === 'length') { bciShowMoveOptions(candidate.moves, false, true); return; }
  if (candidate.kind === 'main') { bciShowMoveOptions(candidate.moves, true, true); return; }
  if (candidate.kind === 'return_selection') { bciReturnFromSettings(); return; }
  if (candidate.kind === 'sound') {
    toggleCombinedSound();
    BciScan.cursor.candidates = bciSettingsCandidates();
    bciRender();
    return;
  }
  if (candidate.kind === 'confirmation') {
    bciSetConfirmationEnabled(!BciScan.confirmationEnabled);
    BciScan.cursor.candidates = bciSettingsCandidates();
    bciRender();
    return;
  }
  if (candidate.kind === 'again') { bciExecute({ kind: 'again' }); return; }
  if (candidate.kind === 'restart' || candidate.kind === 'exit') { bciRequest({ kind: candidate.kind }); return; }
  if (candidate.kind === 'confirm') { bciConfirmPending(); return; }
  if (candidate.kind === 'cancel') bciCancelConfirmation();
}

function handleDoudizhuBciAction(action) {
  if (action === 'look_left' || action === 'look_right') {
    BciScan.cursor.setDirectionFromAction(action);
    bciRestartTimer();
    bciRender();
    return;
  }
  if (action === 'bite') bciActivate(BciScan.cursor.current());
}

/* ---------------- generic helpers ---------------- */

function showScreen(id) {
  $$('.screen').forEach(el => el.classList.toggle('active', el.id === id));
}

function toast(msg) {
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = msg;
  $('#toast-wrap').appendChild(el);
  setTimeout(() => el.classList.add('show'), 10);
  setTimeout(() => { el.classList.remove('show'); setTimeout(() => el.remove(), 300); }, 2600);
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

function rankLabel(r) {
  if (r === 16) return t('jk_small');
  if (r === 17) return t('jk_big');
  return RANK_LABELS[r];
}

function cardHTML(c, cls, laizi) {
  cls = cls || '';
  if (!c) return `<div class="card back ${cls}"></div>`;
  const isJoker = c.r >= 16;
  const red = isJoker ? c.r === 17 : (c.s === 1 || c.s === 2);
  const lz = laizi && c.r === laizi ? ' lz' : '';
  if (isJoker) {
    return `<div class="card jk ${red ? 'red' : ''} ${cls}${lz}" data-id="${c.id}">` +
      `<span class="jk-txt">${c.r === 17 ? 'JOKER' : 'joker'}</span><span class="cs">🃏</span></div>`;
  }
  return `<div class="card ${red ? 'red' : ''} ${cls}${lz}" data-id="${c.id}">` +
    `<span class="cr">${RANK_LABELS[c.r]}</span><span class="cs">${SUITS[c.s]}</span></div>`;
}

function comboName(combo) {
  if (!combo) return '';
  if (combo.type === 'bomb') {
    if (combo.laiziBomb) return t('c_laizi_bomb');
    return t('c_bomb') + (combo.soft ? ' · ' + t('c_soft') : '');
  }
  let s = t('c_' + combo.type);
  if (combo.soft) s += ' · ' + t('g_laizi');
  return s;
}

function prevFor(v) {
  return v.trick.combo && v.trick.seat !== v.mySeat ? v.trick.combo : null;
}

/* ---------------- static texts / language ---------------- */

function applyStaticTexts() {
  const set = (id, key) => { const el = $(id); if (el) el.textContent = t(key); };
  set('#t-title', 'title'); set('#t-subtitle', 'subtitle');
  set('#t-name', 'h_name'); set('#t-mode', 'h_mode');
  set('#t-mclassic', 'm_classic'); set('#t-mclassic-d', 'm_classic_d');
  set('#t-mduel', 'm_duel'); set('#t-mduel-d', 'm_duel_d');
  set('#t-mteam', 'm_team'); set('#t-mteam-d', 'm_team_d');
  set('#t-options', 'h_options');
  set('#t-olaizi', 'o_laizi'); set('#t-olaizi-d', 'o_laizi_d');
  set('#t-onoshuffle', 'o_noshuffle'); set('#t-onoshuffle-d', 'o_noshuffle_d');
  set('#t-odoubling', 'o_doubling'); set('#t-odoubling-d', 'o_doubling_d');
  set('#t-obase', 'o_base');
  set('#t-bcreate', 'b_create'); set('#t-bcreate-d', 'b_create_d');
  set('#t-bpractice', 'b_practice'); set('#t-bpractice-d', 'b_practice_d');
  set('#t-join', 'h_join'); set('#t-rcode', 'r_code');
  $('#btn-diag').textContent = t('diag_btn');
  set('#t-help-title', 'help_title');
  $('#btn-help').textContent = t('b_help');
  $('#btn-join').textContent = t('b_join');
  $('#btn-copy').textContent = t('b_copy');
  $('#btn-start').textContent = t('b_start');
  $('#btn-leave-room').textContent = t('b_leave');
  $('#inp-code').placeholder = t('ph_code');
  $('#btn-lang').textContent = LANG === 'zh' ? 'EN' : '中文';
  for (const id of ['#btn-bgm', '#btn-bgm2']) { const el = $(id); if (el) el.title = t('tt_bgm'); }
  for (const id of ['#btn-snd', '#btn-snd2']) { const el = $(id); if (el) el.title = t('tt_snd'); }
  if ($('#btn-lang2')) $('#btn-lang2').textContent = LANG === 'zh' ? 'EN' : '中文';
  $('#help-body').innerHTML = t('help_body');
  document.documentElement.lang = LANG === 'zh' ? 'zh' : 'en';
}

/* ---------------- home / config ---------------- */

function cfgFromForm() {
  return {
    mode: ($$('input[name=mode]').find(r => r.checked) || {}).value || 'classic',
    laizi: $('#opt-laizi').checked,
    noShuffle: $('#opt-noshuffle').checked,
    doubling: $('#opt-doubling').checked,
    base: +$('#sel-base').value,
  };
}

function myName() {
  const v = $('#inp-name').value.trim();
  const name = v || ('Player' + Math.floor(Math.random() * 900 + 100));
  try { localStorage.setItem('ddz_name', name); } catch (e) { }
  return name;
}

/* ---------------- room (waiting) screen ---------------- */

function renderRoster(players) {
  const wrap = $('#room-players');
  wrap.innerHTML = players.map(p =>
    `<div class="roster-row"><span class="avatar">${p.ai ? '🤖' : '🧑'}</span>` +
    `<span>${esc(p.name)}</span>${p.tag ? `<span class="tag">${esc(p.tag)}</span>` : ''}</div>`).join('');
}

function seatsForMode(mode) { return mode === 'duel' ? 2 : mode === 'team' ? 4 : 3; }
function roomCapacity() { return App.cfg ? seatsForMode(App.cfg.mode) : 3; }

function hostRosterPlayers() {
  const seats = [{ name: App.name, tag: t('r_host') }];
  for (const g of App.guests) seats.push({ name: g.name });
  while (seats.length < roomCapacity()) seats.push({ name: t('r_ai'), ai: true });
  return seats;
}

function updateRoomScreen() {
  $('#room-code').textContent = App.code || '';
  $('#room-status').textContent = App.guests.length >= roomCapacity() - 1 ? '' : t('r_waiting');
  renderRoster(hostRosterPlayers());
  $('#btn-start').style.display = App.role === 'host' ? '' : 'none';
}

function sendRoomInfo() {
  const players = hostRosterPlayers().map(p => ({ name: p.name, ai: !!p.ai, tag: p.tag || '' }));
  for (const g of App.guests) {
    if (g.conn) try { g.conn.send({ t: 'room', code: App.code, players }); } catch (e) { }
  }
}

/* ---------------- liveness (heartbeat) ----------------
   WebRTC close events are unreliable when a tab is killed or a phone is
   locked, so both sides ping every 5s; 40s of silence means gone. */

const HEARTBEAT_MS = 5000;
const PEER_TIMEOUT_MS = 40000;

function startHeartbeat() {
  stopHeartbeat();
  App._hb = setInterval(() => {
    const now = Date.now();
    if (App.role === 'host') {
      for (const g of App.guests.slice()) {
        if (!g.conn) continue;
        try { g.conn.send({ t: 'ping' }); } catch (e) { }
        if (g.lastSeen && now - g.lastSeen > PEER_TIMEOUT_MS) {
          const conn = g.conn;
          try { conn.close(); } catch (e) { }
          hostOnGuestGone(conn);
        }
      }
    } else if (App.role === 'guest' && App.guestConn) {
      try { App.guestConn.send({ t: 'ping' }); } catch (e) { }
      if (App._hostSeen && now - App._hostSeen > PEER_TIMEOUT_MS) {
        toast(t('e_hostleft'));
        goHome();
      }
    }
  }, HEARTBEAT_MS);
}

function stopHeartbeat() {
  if (App._hb) { clearInterval(App._hb); App._hb = null; }
}

function sendBye() {
  try {
    if (App.role === 'guest' && App.guestConn) App.guestConn.send({ t: 'bye' });
    if (App.role === 'host') for (const g of App.guests) { if (g.conn) g.conn.send({ t: 'bye' }); }
  } catch (e) { }
}

/* ---------------- host / local game plumbing ---------------- */

function sendAll(msg) {
  for (const conn of Object.values(App.conns)) { try { conn.send(msg); } catch (e) { } }
}

function pushViews() {
  const g = App.game;
  if (!g) return;
  for (const [seat, conn] of Object.entries(App.conns)) {
    try { conn.send({ t: 'view', v: g.buildView(+seat) }); } catch (e) { }
  }
  App.view = g.buildView(0);
  renderGame();
}

function newGame(players) {
  App.game = new Game(Object.assign({}, App.cfg, { players }), {
    onUpdate: () => pushViews(),
    onEvent: (ev) => {
      if (ev === 'redeal') { toast(t('e_redeal')); sendAll({ t: 'toastc', code: 'e_redeal' }); }
    },
    onError: (e) => console.error(e),
  });
  App.started = true;
  App.selected = new Set();
  showScreen('screen-game');
  App.game.startRound();
}

function startPractice() {
  // 康复 APP 固定使用经典三人单机局；不启用任何附加房间选项。
  App.name = '玩家';
  App.cfg = {
    mode: 'classic',
    laizi: false,
    noShuffle: false,
    doubling: false,
    base: 1,
  };
  App.role = 'local';
  const players = [{ name: App.name }];
  const total = seatsForMode(App.cfg.mode);
  while (players.length < total) players.push({ name: 'AI ' + players.length, isAI: true });
  newGame(players);
}

function setCreateBusy(busy) {
  const btn = $('#btn-create');
  btn.disabled = busy;
  $('#t-bcreate').textContent = busy ? t('connecting') : t('b_create');
}

function createRoom(attempt) {
  if (!netAvailable()) { toast(t('e_net')); return; }
  App.name = myName();
  App.cfg = cfgFromForm();
  App.role = 'host';
  App.code = makeRoomCode();
  setCreateBusy(true);
  const sess = App._netSession || 0;
  const alive = () => (App._netSession || 0) === sess && App.role === 'host';
  App._joinTimer = setTimeout(() => {
    App._joinTimer = null;
    toast(t('e_timeout'));
    goHome();
  }, 12000);
  let myPeer = null;
  createHostPeer(App.code, {
    onReady: () => {
      if (!alive()) return;
      clearJoinTimer(); setCreateBusy(false);
      showScreen('screen-room'); updateRoomScreen(); startHeartbeat();
    },
    onCodeTaken: () => {
      if (!alive()) return;
      clearJoinTimer();
      try { if (myPeer) myPeer.destroy(); } catch (e) { }
      if ((attempt || 0) < 3) createRoom((attempt || 0) + 1);
      else { setCreateBusy(false); toast(t('e_conn')); }
    },
    onError: () => { if (!alive()) return; clearJoinTimer(); setCreateBusy(false); toast(t('e_net')); goHome(); },
    onConnection: (conn) => { if (alive()) hostAcceptConn(conn); },
  }).then(p => {
    myPeer = p;
    if (!alive()) { try { p.destroy(); } catch (e) { } return; }
    App.peer = p;
  });
}

function seatOfConn(conn) {
  const idx = App.guests.findIndex(g => g.conn === conn);
  return idx < 0 ? -1 : idx + 1;
}

function hostAcceptConn(conn) {
  if (App.started || App.guests.length >= roomCapacity() - 1) {
    try { conn.send({ t: 'full' }); setTimeout(() => conn.close(), 200); } catch (e) { }
    return;
  }
  conn.on('data', d => hostOnData(conn, d));
  conn.on('close', () => hostOnGuestGone(conn));
}

function hostOnData(conn, d) {
  if (!d || typeof d !== 'object') return;
  const known = App.guests.find(g => g.conn === conn);
  if (known) known.lastSeen = Date.now();
  if (d.t === 'ping') return;
  if (d.t === 'bye') { if (known) { try { conn.close(); } catch (e) { } hostOnGuestGone(conn); } return; }
  if (d.t === 'hello') {
    if (seatOfConn(conn) >= 0) return;
    if (App.started || App.guests.length >= roomCapacity() - 1) {
      try { conn.send({ t: 'full' }); } catch (e) { }
      return;
    }
    App.guests.push({ conn, name: String(d.name || 'Guest').slice(0, 12), lastSeen: Date.now() });
    updateRoomScreen();
    sendRoomInfo();
    return;
  }
  if (d.t === 'act' && App.started) {
    const seat = seatOfConn(conn);
    if (seat < 0) return;
    dispatchAct(seat, d.kind, d.data, () => { try { conn.send({ t: 'err', code: 'e_invalid' }); } catch (e) { } });
  }
}

function hostOnGuestGone(conn) {
  const idx = App.guests.findIndex(g => g.conn === conn);
  if (idx < 0) return;
  if (App.started && App.game && App.game.state !== 'idle') {
    // Keep the seat (order defines seats); let an AI take over.
    const seat = idx + 1;
    App.guests[idx].conn = null;
    delete App.conns[seat];
    const nm = App.game.players[seat].name;
    App.game.players[seat].isAI = true;
    toast(t('e_disconnected', nm));
    sendAll({ t: 'toastc', code: 'e_disconnected', arg: nm });
    App.game.pump();
    pushViews();
  } else {
    const nm = App.guests[idx].name;
    App.guests.splice(idx, 1);
    toast(t('e_left', nm));
    updateRoomScreen();
    sendRoomInfo();
  }
}

function hostStartGame() {
  const players = [{ name: App.name }];
  for (const g of App.guests) players.push({ name: g.name });
  while (players.length < roomCapacity()) players.push({ name: 'AI ' + players.length, isAI: true });
  App.conns = {};
  App.guests.forEach((g, i) => { if (g.conn) App.conns[i + 1] = g.conn; });
  newGame(players);
}

function dispatchAct(seat, kind, data, onErr) {
  const g = App.game;
  if (!g) return;
  let ok = true;
  data = data || {};
  if (kind === 'bid') ok = g.actBid(seat, data.v | 0);
  else if (kind === 'double') ok = g.actDouble(seat, !!data.yes);
  else if (kind === 'play') ok = g.actPlay(seat, Array.isArray(data.ids) ? data.ids : []);
  else if (kind === 'pass') ok = g.actPass(seat);
  else if (kind === 'again') g.nextRound();
  else if (kind === 'chat') hostChat(seat, data.id | 0);
  if (!ok && onErr) onErr();
}

function hostChat(seat, id) {
  sendAll({ t: 'chat', seat, id });
  showBubble(seat, t('chat')[id] || '');
}

/* ---------------- guest plumbing ---------------- */

function setJoinBusy(busy) {
  const btn = $('#btn-join');
  btn.disabled = busy;
  btn.textContent = busy ? t('connecting') : t('b_join');
}

function clearJoinTimer() {
  if (App._joinTimer) { clearTimeout(App._joinTimer); App._joinTimer = null; }
  setJoinBusy(false);
}

function joinRoom() {
  if (!netAvailable()) { toast(t('e_net')); return; }
  const code = $('#inp-code').value.trim().toUpperCase();
  if (code.length !== 4) { toast(t('e_room404')); return; }
  App.name = myName();
  App.role = 'guest';
  App.code = code;
  setJoinBusy(true);
  const sess = App._netSession || 0;
  const alive = () => (App._netSession || 0) === sess && App.role === 'guest';
  App._joinTimer = setTimeout(() => {
    App._joinTimer = null;
    toast(t('e_timeout'));
    goHome();
  }, 12000);
  createGuestPeer(code, {
    onOpen: (conn) => {
      if (!alive()) return;
      clearJoinTimer();
      App.guestConn = conn;
      App._hostSeen = Date.now();
      startHeartbeat();
      conn.send({ t: 'hello', name: App.name });
      showScreen('screen-room');
      $('#room-code').textContent = code;
      $('#room-status').textContent = t('r_waiting');
      $('#btn-start').style.display = 'none';
      renderRoster([]);
    },
    onData: (d) => { if (alive()) guestOnData(d); },
    onClose: () => { if (alive()) { toast(t('e_hostleft')); goHome(); } },
    onNotFound: () => { if (!alive()) return; clearJoinTimer(); toast(t('e_room404')); goHome(); },
    onError: () => { if (!alive()) return; clearJoinTimer(); toast(t('e_conn')); goHome(); },
  }).then(p => {
    if (!alive()) { try { p.destroy(); } catch (e) { } return; }
    App.peer = p;
  });
}

/* ---------------- network diagnostics ---------------- */

async function runDiag() {
  const out = $('#diag-out');
  out.innerHTML = `<div class="diag-line">${t('diag_running')}</div>`;
  const r = await netDiagnose();
  const line = (ok, label) =>
    `<div class="diag-line">${ok ? '✅' : '❌'} ${label}</div>`;
  let verdict;
  if (!r.broker) verdict = t('diag_v_broker');
  else if (r.turn) verdict = t('diag_v_good');
  else if (r.stun) verdict = t('diag_v_noturn');
  else verdict = t('diag_v_blocked');
  out.innerHTML =
    line(r.broker, t('diag_broker')) +
    line(r.stun, t('diag_stun')) +
    line(r.turn, t('diag_turn')) +
    `<div class="diag-verdict">${verdict}</div>`;
}

function guestOnData(d) {
  if (!d || typeof d !== 'object') return;
  App._hostSeen = Date.now();
  if (d.t === 'ping') return;
  if (d.t === 'bye') { toast(t('e_hostleft')); goHome(); return; }
  if (d.t === 'room') {
    $('#room-status').textContent = '';
    renderRoster(d.players || []);
  } else if (d.t === 'view') {
    App.started = true;
    App.view = d.v;
    if (!$('#screen-game').classList.contains('active')) showScreen('screen-game');
    renderGame();
  } else if (d.t === 'chat') {
    if (d.seat !== App.view?.mySeat) showBubble(d.seat, t('chat')[d.id] || '');
  } else if (d.t === 'err' || d.t === 'toastc') {
    toast(t(d.code || 'e_invalid', d.arg !== undefined ? d.arg : ''));
  } else if (d.t === 'full') {
    toast(t('e_full'));
    goHome();
  }
}

/* ---------------- shared action entry ---------------- */

function act(kind, data) {
  if (App.role === 'guest') {
    if (App.guestConn) App.guestConn.send({ t: 'act', kind, data });
    if (kind === 'chat') showBubble(App.view ? App.view.mySeat : 1, t('chat')[data.id] || '');
    return;
  }
  if (kind === 'chat' && App.role === 'local') { showBubble(0, t('chat')[data.id] || ''); return; }
  dispatchAct(0, kind, data, () => toast(t('e_invalid')));
}

/* ---------------- game rendering ---------------- */

function seatSides(v) {
  // My seat at the bottom; play order flows to the right.
  if (v.n === 2) return { left: null, right: null, top: (v.mySeat + 1) % 2 };
  if (v.n === 4) return { left: (v.mySeat + 3) % 4, right: (v.mySeat + 1) % 4, top: (v.mySeat + 2) % 4 };
  return { left: (v.mySeat + 2) % 3, right: (v.mySeat + 1) % 3, top: null };
}

function playAreaHTML(v, seat) {
  const p = v.players[seat];
  if (v.state === 'bidding') {
    if (p.bid === null || p.bid === undefined) return '';
    return `<span class="say">${p.bid === 0 ? t('bid0') : t('bidN', p.bid)}</span>`;
  }
  if (v.state === 'doubling') {
    if (p.dbl === null || p.dbl === undefined) return '';
    return `<span class="say">${p.dbl ? t('dblY') : t('dblN')}</span>`;
  }
  const lp = p.lastPlay;
  if (!lp) return '';
  if (lp.pass) return `<span class="say pass">${t('pass_txt')}</span>`;
  const cards = lp.cards.slice().sort((a, b) => b.r - a.r);
  return `<div class="mini-cards">${cards.map(c => cardHTML(c, 'mini', v.laizi)).join('')}</div>` +
    `<span class="combo-tag">${comboName(lp.combo)}</span>`;
}

function seatBadge(v, p) {
  if (p.landlord) return `<span class="badge lord">👑 ${t('g_landlord')}</span>`;
  if (p.ally) return `<span class="badge ally">🤝 ${t('g_ally')}</span>`;
  return v.landlord != null ? `<span class="badge">${t('g_farmer')}</span>` : '';
}

function oppPanelHTML(v, seat) {
  const p = v.players[seat];
  const isTurn = v.actor === seat && (v.state === 'bidding' || v.state === 'doubling' || v.state === 'playing');
  const badge = seatBadge(v, p);
  const settleHand = p.hand && v.state === 'settle'
    ? `<div class="mini-cards reveal">${p.hand.map(c => cardHTML(c, 'mini', v.laizi)).join('')}</div>` : '';
  return `<div class="opp ${isTurn ? 'turn' : ''}" data-seat="${seat}">
    <div class="opp-head">
      <span class="avatar">${p.isAI ? '🤖' : '🧑'}</span>
      <div class="opp-names">
        <div class="pname">${esc(p.name)}${p.isAI ? ` <span class="tag">${t('ai_tag')}</span>` : ''} ${badge}</div>
        <div class="pmeta">${t('score_pts', p.score)} · ${t('cards_left', p.cardCount)}</div>
      </div>
    </div>
    <div class="opp-play">${playAreaHTML(v, seat)}</div>${settleHand}
    <div class="bubble-slot"></div>
  </div>`;
}

function centerHTML(v) {
  const chips = [];
  chips.push(`<span class="chip">${t('g_round', v.roundNo)}</span>`);
  if (v.laizi) chips.push(`<span class="chip lzchip">${t('g_laizi')}: ${rankLabel(v.laizi)}</span>`);
  chips.push(`<span class="chip">${t('g_mult')} ×${v.liveMult}</span>`);
  if (v.flags.noShuffle) chips.push(`<span class="chip">${t('o_noshuffle')}</span>`);
  if (v.mode === 'duel') chips.push(`<span class="chip dim" title="${t('g_dead')}">🂠×17</span>`);
  return chips.join('');
}

function midHTML(v) {
  let bottom;
  if (v.bottom) bottom = v.bottom.map(c => cardHTML(c, 'mini', v.laizi)).join('');
  else bottom = [0, 1, 2].map(() => cardHTML(null, 'mini')).join('');
  let html = `<div class="bottom-cards"><span class="bc-label">${t('g_bottom')}</span>${bottom}</div>`;
  const sides = seatSides(v);
  if (sides.top !== null) html += oppPanelHTML(v, sides.top);
  return html;
}

function myInfoHTML(v) {
  const p = v.players[v.mySeat];
  const badge = seatBadge(v, p);
  return `<span class="avatar">🧑</span><span class="pname">${esc(p.name)}</span> ${badge}
    <span class="pmeta">${t('score_pts', p.score)}</span><div class="bubble-slot"></div>`;
}

function actionsHTML(v) {
  const mine = v.actor === v.mySeat;
  if (v.state === 'bidding') {
    if (!mine) return statusHTML(v);
    let btns = `<button class="act-btn" data-bid="0">${t('bid0')}</button>`;
    for (let b = 1; b <= 3; b++)
      btns += `<button class="act-btn primary" data-bid="${b}" ${b <= v.currentBid ? 'disabled' : ''}>${t('bidN', b)}</button>`;
    return btns;
  }
  if (v.state === 'doubling') {
    if (!mine) return statusHTML(v);
    return `<button class="act-btn" data-dbl="0">${t('dblN')}</button>` +
      `<button class="act-btn primary" data-dbl="1">${t('dblY')}</button>`;
  }
  if (v.state === 'playing') {
    if (!mine) return statusHTML(v);
    const prev = prevFor(v);
    if (prev && !canBeat(v)) {
      return `<button class="act-btn noplay" data-act="pass">${t('cant_beat')}</button>`;
    }
    const sel = v.myHand.filter(c => App.selected.has(c.id));
    let combo = null;
    if (sel.length) {
      combo = analyze(sel.map(c => c.r), prev, v.laizi);
      if (combo && prev && !beats(combo, prev)) combo = null;
    }
    const preview = combo ? `<span class="combo-preview">${comboName(combo)}</span>` : '';
    return `${preview}<button class="act-btn" data-act="hint">${t('b_hint')}</button>` +
      `<button class="act-btn" data-act="pass" ${prev ? '' : 'disabled'}>${t('b_pass')}</button>` +
      `<button class="act-btn primary" data-act="play" ${combo ? '' : 'disabled'}>${t('b_play')}</button>`;
  }
  return '';
}

/* Is there any legal answer to the current trick? Memoized per situation
   so re-renders (card clicks, bubbles) don't recompute move generation. */
function canBeat(v) {
  const c = v.trick.combo;
  const sig = [v.roundNo, v.trick.seat, c && c.type, c && c.rank, c && c.n, v.myHand.length].join('|');
  if (App._beatSig === sig) return App._beatHas;
  App._beatSig = sig;
  App._beatHas = legalMoves(v.myHand.map(x => x.r), v.laizi, prevFor(v)).length > 0;
  return App._beatHas;
}

function statusHTML(v) {
  if (v.actor == null) return '';
  const nm = v.players[v.actor].name;
  return `<span class="status">${t('thinking', esc(nm))}</span>`;
}

/* Sound effects are driven by diffing consecutive views, so they fire
   identically for local play, host and guest. */
function sndSig(v) {
  return {
    round: v.roundNo,
    state: v.state,
    actor: v.actor,
    plays: v.players.map(p => {
      const lp = p.lastPlay;
      if (!lp) return '';
      return lp.pass ? 'P' : lp.cards.map(c => c.id).join('-');
    }),
    bids: v.players.map(p => (p.bid === null || p.bid === undefined) ? '' : String(p.bid)).join(','),
    dbls: v.players.map(p => (p.dbl === null || p.dbl === undefined) ? '' : (p.dbl ? '1' : '0')).join(','),
  };
}

function playSounds(v) {
  const prev = App._snd;
  const cur = sndSig(v);
  App._snd = cur;
  if (!prev || prev.round !== cur.round) {
    if (v.state === 'bidding') Snd.deal();
    return;
  }
  for (let i = 0; i < v.players.length; i++) {
    if (cur.plays[i] === prev.plays[i] || cur.plays[i] === '') continue;
    if (cur.plays[i] === 'P') { Snd.pass(); continue; }
    const c = v.players[i].lastPlay.combo;
    if (c && c.type === 'rocket') Snd.rocket();
    else if (c && c.type === 'bomb') Snd.bomb();
    else Snd.play();
  }
  if (cur.bids !== prev.bids) Snd.bid();
  if (cur.dbls !== prev.dbls) Snd.dbl();
  if (v.state === 'settle' && prev.state !== 'settle' && v.result) {
    const r = v.result;
    const iWon = r.landlordWon ? v.mySeat === v.landlord : v.mySeat !== v.landlord;
    if (iWon) Snd.win(); else Snd.lose();
    return;
  }
  if (v.state === 'playing' && cur.actor === v.mySeat && prev.actor !== v.mySeat) Snd.turn();
}

function renderGame() {
  const v = App.view;
  if (!v) return;
  playSounds(v);
  // prune stale selection
  const handIds = new Set(v.myHand.map(c => c.id));
  for (const id of [...App.selected]) if (!handIds.has(id)) App.selected.delete(id);

  $('#center-info').innerHTML = centerHTML(v);
  const sides = seatSides(v);
  $('#opp-left').innerHTML = sides.left !== null ? oppPanelHTML(v, sides.left) : '';
  $('#opp-right').innerHTML = sides.right !== null ? oppPanelHTML(v, sides.right) : '';
  $('#table-mid').innerHTML = midHTML(v);

  // my last play
  const mp = v.players[v.mySeat].lastPlay;
  let mpHtml = '';
  if (mp) {
    mpHtml = mp.pass ? `<span class="say pass">${t('pass_txt')}</span>`
      : `<div class="mini-cards">${mp.cards.slice().sort((a, b) => b.r - a.r).map(c => cardHTML(c, 'mini', v.laizi)).join('')}</div>`;
  } else if (v.state === 'playing' && v.actor === v.mySeat && !prevFor(v)) {
    mpHtml = `<span class="say lead">${t('lead_any')}</span>`;
  }
  $('#my-lastplay').innerHTML = mpHtml;

  $('#actions').innerHTML = actionsHTML(v);
  $('#my-hand').innerHTML = v.myHand.map(c =>
    cardHTML(c, App.selected.has(c.id) ? 'sel' : '', v.laizi)).join('');
  layoutHand();
  $('#my-info').innerHTML = myInfoHTML(v);
  $('#btn-chat').style.display = App.role === 'local' ? 'none' : '';

  bindActionButtons();
  renderBubbles();

  if (v.landlord != null && App._lordSeen !== v.roundNo) {
    App._lordSeen = v.roundNo;
    animateLandlord(v);
  }

  if (v.state === 'settle' && v.result) renderSettle(v);
  else $('#settle-overlay').classList.add('hidden');
  bciSyncForView(v);
}

/* Landlord reveal: banner + the kitty cards flying into the landlord's
   hand (or seat panel). Purely cosmetic overlay clones. */
function animateLandlord(v) {
  Snd.landlord();
  const banner = document.createElement('div');
  banner.className = 'lord-banner';
  banner.textContent = '👑 ' + t('lord_banner', v.players[v.landlord].name);
  document.body.appendChild(banner);
  setTimeout(() => banner.classList.add('show'), 20);
  setTimeout(() => { banner.classList.remove('show'); setTimeout(() => banner.remove(), 400); }, 1800);

  const srcCards = $$('#table-mid .bottom-cards .card');
  const target = v.landlord === v.mySeat ? $('#my-hand')
    : ($(`.opp[data-seat="${v.landlord}"]`) || $('#table-mid'));
  if (!srcCards.length || !target) return;
  const tr = target.getBoundingClientRect();
  const tx = tr.left + tr.width / 2, ty = tr.top + tr.height / 2;
  srcCards.forEach((el, i) => {
    const r = el.getBoundingClientRect();
    const clone = el.cloneNode(true);
    clone.classList.add('fly-card');
    clone.style.left = r.left + 'px';
    clone.style.top = r.top + 'px';
    clone.style.width = r.width + 'px';
    clone.style.height = r.height + 'px';
    document.body.appendChild(clone);
    requestAnimationFrame(() => {
      clone.style.transform = 'scale(1.9)';
      setTimeout(() => {
        clone.style.transform =
          `translate(${tx - (r.left + r.width / 2)}px, ${ty - (r.top + r.height / 2)}px) scale(.35)`;
        clone.style.opacity = '0';
      }, 650 + i * 130);
    });
    setTimeout(() => clone.remove(), 1700 + i * 130);
  });
}

function bindActionButtons() {
  $$('#actions [data-bid]').forEach(b => b.onclick = () => act('bid', { v: +b.dataset.bid }));
  $$('#actions [data-dbl]').forEach(b => b.onclick = () => act('double', { yes: +b.dataset.dbl === 1 }));
  const hint = $('#actions [data-act=hint]');
  if (hint) hint.onclick = doHint;
  const pass = $('#actions [data-act=pass]');
  if (pass) pass.onclick = () => { App.selected.clear(); act('pass'); };
  const play = $('#actions [data-act=play]');
  if (play) play.onclick = () => act('play', { ids: [...App.selected] });
}

/* Drag across the hand to (de)select a run of cards; a plain tap still
   toggles one card. Bound once in boot() — the hand element persists. */
function bindHandDrag() {
  const handEl = $('#my-hand');
  let drag = null;
  const cardIdAt = (x, y) => {
    const el = document.elementFromPoint(x, y);
    const c = el && el.closest('#my-hand .card');
    return c ? +c.dataset.id : null;
  };
  const applySel = (id, on) => {
    if (on) App.selected.add(id); else App.selected.delete(id);
    Snd.tick();
    renderGame();
  };
  handEl.addEventListener('pointerdown', e => {
    const id = cardIdAt(e.clientX, e.clientY);
    if (id === null) return;
    e.preventDefault();
    try { handEl.setPointerCapture(e.pointerId); } catch (err) { }
    drag = { on: !App.selected.has(id), touched: new Set([id]) };
    applySel(id, drag.on);
  });
  handEl.addEventListener('pointermove', e => {
    if (!drag) return;
    const id = cardIdAt(e.clientX, e.clientY);
    if (id === null || drag.touched.has(id)) return;
    drag.touched.add(id);
    applySel(id, drag.on);
  });
  const end = () => { drag = null; };
  handEl.addEventListener('pointerup', end);
  handEl.addEventListener('pointercancel', end);
}

/* Compress card overlap so the whole hand always fits the screen. */
function layoutHand() {
  const handEl = $('#my-hand');
  const cards = handEl.children;
  const n = cards.length;
  if (!n) return;
  const cw = cards[0].offsetWidth;
  const avail = handEl.clientWidth - 20;
  let step = cw - (window.innerWidth <= 720 ? 22 : 30);
  if (cw + step * (n - 1) > avail) step = Math.max(11, (avail - cw) / (n - 1));
  for (let i = 1; i < n; i++) cards[i].style.marginLeft = (step - cw) + 'px';
}

function doHint() {
  const v = App.view;
  const prev = prevFor(v);
  const moves = hintMoves(v.myHand.map(c => c.r), v.laizi, prev);
  if (!moves.length) { if (prev) { App.selected.clear(); act('pass'); } return; }
  // Repeated presses in the same situation cycle through the options.
  const sig = JSON.stringify([v.roundNo, v.trick.seat, prev && prev.type, prev && prev.rank, v.myHand.length]);
  if (App._hintSig !== sig) { App._hintSig = sig; App._hintIdx = 0; }
  const mv = moves[App._hintIdx % moves.length];
  App._hintIdx++;
  const cards = materialize(v.myHand, mv.play, v.laizi);
  if (!cards) return;
  App.selected = new Set(cards.map(c => c.id));
  renderGame();
}

/* ---------------- bubbles / chat ---------------- */

function showBubble(seat, text) {
  if (!text) return;
  Snd.chat();
  if (App.bubbles[seat]) clearTimeout(App.bubbles[seat].timer);
  App.bubbles[seat] = { text, timer: setTimeout(() => { delete App.bubbles[seat]; renderBubbles(); }, 3200) };
  renderBubbles();
}

function renderBubbles() {
  const v = App.view;
  if (!v) return;
  $$('.bubble-slot').forEach(el => el.innerHTML = '');
  for (const [seatStr, b] of Object.entries(App.bubbles)) {
    const seat = +seatStr;
    let slot = null;
    if (seat === v.mySeat) slot = $('#my-info .bubble-slot');
    else {
      const panel = $(`.opp[data-seat="${seat}"] .bubble-slot`);
      if (panel) slot = panel;
    }
    if (slot) slot.innerHTML = `<span class="bubble">${esc(b.text)}</span>`;
  }
}

function toggleChatPop() {
  const pop = $('#chat-pop');
  if (!pop.classList.contains('hidden')) { pop.classList.add('hidden'); return; }
  pop.innerHTML = t('chat').map((p, i) => `<button data-chat="${i}">${esc(p)}</button>`).join('');
  pop.classList.remove('hidden');
  $$('#chat-pop [data-chat]').forEach(b => b.onclick = () => {
    act('chat', { id: +b.dataset.chat });
    pop.classList.add('hidden');
  });
}

/* ---------------- settle overlay ---------------- */

function renderSettle(v) {
  const r = v.result;
  const iWon = r.landlordWon ? v.mySeat === v.landlord : v.mySeat !== v.landlord;
  const chips = [t('s_base', r.base), t('s_bid', r.bid)];
  if (r.bombs) chips.push(t('s_bombs', r.bombs));
  if (r.spring) chips.push(t('s_spring'));
  if (r.anti) chips.push(t('s_anti'));
  chips.push(t('s_total', r.mult * r.bid * r.base));
  const rows = v.players.map((p, i) => {
    const d = r.deltas[i];
    return `<div class="settle-row ${i === v.mySeat ? 'me' : ''}">
      <span>${p.landlord ? '👑' : ''} ${esc(p.name)}</span>
      <span class="${d >= 0 ? 'plus' : 'minus'}">${d >= 0 ? '+' : ''}${d}</span>
      <span class="total">${t('score_pts', p.score)}</span></div>`;
  }).join('');
  $('#settle-overlay').innerHTML = `<div class="settle-box ${iWon ? 'won' : 'lost'}">
    <h2>${r.landlordWon ? t('s_lwin') : t('s_fwin')}</h2>
    <p class="personal">${iWon ? t('s_youwin') : t('s_youlose')}</p>
    <div class="settle-chips">${chips.map(c => `<span class="chip">${c}</span>`).join('')}</div>
    <div class="settle-rows">${rows}</div>
    <div class="settle-actions-single">
      <button id="btn-again" class="bci-choice settle-again">再来一次</button>
    </div></div>`;
  $('#settle-overlay').classList.remove('hidden');
  $('#btn-again').onclick = () => act('again');
}

function requestAppExit() {
  $('#settings-modal').classList.add('hidden');
  if (window.InputDsDoudizhuHost && window.InputDsDoudizhuHost.requestExit) {
    window.InputDsDoudizhuHost.requestExit();
    return;
  }
  toast('APP 中点击此项会返回首页；浏览器测试请直接关闭页面');
}

function setCombinedSound(enabled) {
  if (Snd.enabled !== enabled) Snd.toggle();
  if (Bgm.enabled !== enabled) Bgm.toggle();
  $('#settings-sound-label').textContent = `声音：${enabled ? '开' : '关'}`;
  $('#btn-sound-toggle').classList.toggle('off', !enabled);
}

function toggleCombinedSound() {
  setCombinedSound(!(Snd.enabled && Bgm.enabled));
}

/* ---------------- navigation / cleanup ---------------- */

function goHome() {
  sendBye();
  stopHeartbeat();
  App._netSession = (App._netSession || 0) + 1;
  App._hostSeen = null;
  if (App.game) { App.game.stop(); App.game = null; }
  if (App.peer) {
    // Let the bye message flush before tearing the connection down.
    const peer = App.peer;
    App.peer = null;
    setTimeout(() => { try { peer.destroy(); } catch (e) { } }, 300);
  }
  App.role = null;
  App.conns = {};
  App.guests = [];
  App.guestConn = null;
  App.started = false;
  App.view = null;
  App.selected = new Set();
  App.bubbles = {};
  App._snd = null;
  App._lordSeen = null;
  App._hintSig = null;
  App._beatSig = null;
  if (App._joinTimer) { clearTimeout(App._joinTimer); App._joinTimer = null; }
  setJoinBusy(false);
  setCreateBusy(false);
  $('#settle-overlay').classList.add('hidden');
  $('#chat-pop').classList.add('hidden');
  showScreen('screen-home');
}

/* ---------------- boot ---------------- */

function boot() {
  // 固定中文单机模式，不再读取联网大厅中的语言、昵称或玩法选项。
  setLang('zh');
  applyStaticTexts();

  setCombinedSound(Snd.enabled && Bgm.enabled);
  bciSetConfirmationEnabled(BciScan.confirmationEnabled);
  $('#btn-sound-toggle').onclick = toggleCombinedSound;
  $('#btn-settings').onclick = bciEnterSettings;
  $('#btn-return-selection').onclick = bciReturnFromSettings;
  $('#btn-confirm-toggle').onclick = () => bciSetConfirmationEnabled(!BciScan.confirmationEnabled);
  $('#btn-restart-game').onclick = () => bciRequest({ kind: 'restart' });
  $('#btn-app-exit').onclick = () => bciRequest({ kind: 'exit' });
  $('#btn-bci-confirm').onclick = bciConfirmPending;
  $('#btn-bci-cancel').onclick = bciCancelConfirmation;
  // Browsers only allow audio after a user gesture; arm on any click.
  document.addEventListener('pointerdown', () => { Snd.unlock(); Bgm.poke(); });

  bindHandDrag();
  window.addEventListener('resize', () => { if (App.view) layoutHand(); });
  document.addEventListener('visibilitychange', () => {
    if (!document.hidden) { Snd.unlock(); Bgm.poke(); }
  });
  $('#btn-chat').onclick = toggleChatPop;

  const farewell = () => {
    sendBye();
    if (App.peer) try { App.peer.destroy(); } catch (e) { }
  };
  window.addEventListener('beforeunload', farewell);
  window.addEventListener('pagehide', farewell);

  window.inputDsDoudizhu = {
    handleAction: handleDoudizhuBciAction,
    setScanInterval: bciSetInterval,
  };

  startPractice();
}

document.addEventListener('DOMContentLoaded', boot);
