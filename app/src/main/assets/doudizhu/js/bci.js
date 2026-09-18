'use strict';
/* Pure helpers for EEG scanning. The rehabilitation build disables laizi, so
   exhaustive attachment generation below intentionally targets plain rules. */

if (typeof require !== 'undefined' && typeof window === 'undefined') {
  Object.assign(globalThis, require('./cards.js'), require('./moves.js'));
}

const BCI_COMPOSITE_TYPES = new Set([
  'trio_single', 'trio_pair', 'plane_single', 'plane_pair',
  'four_two', 'four_two_pairs',
]);

const BCI_TYPE_ORDER = [
  'single', 'pair', 'trio', 'trio_single', 'trio_pair',
  'straight', 'pair_straight', 'plane', 'plane_single', 'plane_pair',
  'four_two', 'four_two_pairs', 'bomb', 'rocket',
];

function chooseUnits(countMap, totalUnits, unitSize) {
  const ranks = Object.keys(countMap).map(Number).sort((a, b) => a - b);
  const out = [];
  const walk = (at, left, picked) => {
    if (left === 0) { out.push(picked.slice()); return; }
    if (at >= ranks.length) return;
    const rank = ranks[at];
    const available = Math.floor((countMap[rank] || 0) / unitSize);
    const max = Math.min(available, left);
    for (let n = 0; n <= max; n++) {
      for (let i = 0; i < n * unitSize; i++) picked.push(rank);
      walk(at + 1, left - n, picked);
      picked.length -= n * unitSize;
    }
  };
  walk(0, totalUnits, []);
  return out;
}

function subtractRanks(source, ranks) {
  const next = Object.assign({}, source);
  for (const rank of ranks) {
    next[rank] = (next[rank] || 0) - 1;
    if (next[rank] <= 0) delete next[rank];
  }
  return next;
}

function repeatedRange(start, length, count) {
  const out = [];
  for (let rank = start; rank < start + length; rank++) {
    for (let i = 0; i < count; i++) out.push(rank);
  }
  return out;
}

/** Return every distinct legal rank multiset, including alternate wings. */
function allBciMoves(handRanks, prev) {
  const handCounts = counts(handRanks);
  const byRanks = new Map();
  const add = (play, expectedType) => {
    const sorted = play.slice().sort((a, b) => a - b);
    const combo = analyze(sorted, prev || null, null);
    if (!combo || (prev && !beats(combo, prev))) return;
    if (expectedType && combo.type !== expectedType) return;
    const key = sorted.join(',');
    if (!byRanks.has(key)) byRanks.set(key, { play: sorted, wild: 0, combo });
  };

  legalMoves(handRanks, null, prev || null).forEach(move => add(move.play));

  const wanted = type => !prev || prev.type === type;
  for (let rank = 3; rank <= 15; rank++) {
    if ((handCounts[rank] || 0) < 3) continue;
    const body = [rank, rank, rank];
    const rest = subtractRanks(handCounts, body);
    if (wanted('trio_single')) {
      chooseUnits(rest, 1, 1).forEach(wing => add([...body, ...wing], 'trio_single'));
    }
    if (wanted('trio_pair')) {
      chooseUnits(rest, 1, 2).forEach(wing => add([...body, ...wing], 'trio_pair'));
    }
  }

  for (let length = 2; length <= 6; length++) {
    for (let start = 3; start + length - 1 <= 14; start++) {
      const body = repeatedRange(start, length, 3);
      if (body.some(rank => (handCounts[rank] || 0) < 3)) continue;
      const rest = subtractRanks(handCounts, body);
      if (wanted('plane_single')) {
        chooseUnits(rest, length, 1)
          .forEach(wing => add([...body, ...wing], 'plane_single'));
      }
      if (wanted('plane_pair')) {
        chooseUnits(rest, length, 2)
          .forEach(wing => add([...body, ...wing], 'plane_pair'));
      }
    }
  }

  for (let rank = 3; rank <= 15; rank++) {
    if ((handCounts[rank] || 0) < 4) continue;
    const body = [rank, rank, rank, rank];
    const rest = subtractRanks(handCounts, body);
    if (wanted('four_two')) {
      chooseUnits(rest, 2, 1).forEach(wing => add([...body, ...wing], 'four_two'));
    }
    if (wanted('four_two_pairs')) {
      chooseUnits(rest, 2, 2).forEach(wing => add([...body, ...wing], 'four_two_pairs'));
    }
  }

  return [...byRanks.values()];
}

function isBombMove(move) {
  return move.combo.type === 'bomb' || move.combo.type === 'rocket';
}

function sortBciMoves(moves, handRanks) {
  const handCounts = counts(handRanks);
  return moves.slice().sort((a, b) => {
    const aFinish = a.play.length === handRanks.length ? 0 : 1;
    const bFinish = b.play.length === handRanks.length ? 0 : 1;
    return (aFinish - bFinish) ||
      (Number(isBombMove(a)) - Number(isBombMove(b))) ||
      (breakPenalty(a.play, handCounts, null) - breakPenalty(b.play, handCounts, null)) ||
      (a.combo.rank - b.combo.rank) ||
      (b.play.length - a.play.length);
  });
}

function bciMainKey(move) {
  const c = move.combo;
  if (c.type === 'plane_single' || c.type === 'plane_pair') return `${c.len}:${c.rank}`;
  if (BCI_COMPOSITE_TYPES.has(c.type)) return String(c.rank);
  return `${c.type}:${c.len}:${c.rank}`;
}

class DdzScanCursor {
  constructor() {
    this.phase = 'waiting';
    this.orientation = 'horizontal';
    this.direction = 1;
    this.candidates = [];
    this.index = -1;
  }

  setPhase(phase, candidates, orientation) {
    this.phase = phase;
    this.orientation = orientation || 'horizontal';
    this.direction = 1;
    this.candidates = candidates.slice();
    this.index = this.candidates.length ? 0 : -1;
    return this.current();
  }

  setDirectionFromAction(action) {
    if (this.orientation === 'vertical') {
      if (action === 'look_left') this.direction = -1;
      if (action === 'look_right') this.direction = 1;
    } else {
      if (action === 'look_left') this.direction = -1;
      if (action === 'look_right') this.direction = 1;
    }
    return this.direction;
  }

  advance() {
    if (!this.candidates.length) return null;
    this.index = (this.index + this.direction + this.candidates.length) % this.candidates.length;
    return this.current();
  }

  current() { return this.index >= 0 ? this.candidates[this.index] : null; }

  snapshot() {
    return {
      phase: this.phase,
      orientation: this.orientation,
      direction: this.direction,
      candidates: this.candidates.slice(),
      index: this.index,
    };
  }

  restore(snapshot) {
    if (!snapshot) return null;
    this.phase = snapshot.phase;
    this.orientation = snapshot.orientation;
    this.direction = snapshot.direction;
    this.candidates = snapshot.candidates.slice();
    this.index = Math.max(-1, Math.min(snapshot.index, this.candidates.length - 1));
    return this.current();
  }
}

if (typeof module !== 'undefined' && module.exports) {
  module.exports = {
    BCI_COMPOSITE_TYPES,
    BCI_TYPE_ORDER,
    DdzScanCursor,
    allBciMoves,
    bciMainKey,
    chooseUnits,
    sortBciMoves,
  };
}
