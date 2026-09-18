(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.MahjongCore = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  const FLOWER_START = 34;
  const TILE_NAMES = [
    "一万", "二万", "三万", "四万", "五万", "六万", "七万", "八万", "九万",
    "一筒", "二筒", "三筒", "四筒", "五筒", "六筒", "七筒", "八筒", "九筒",
    "一条", "二条", "三条", "四条", "五条", "六条", "七条", "八条", "九条",
    "东风", "南风", "西风", "北风", "红中", "发财", "白板",
    "春", "夏", "秋", "冬", "梅", "兰", "竹", "菊"
  ];

  const TILE_ASSETS = [
    "w1.svg", "w2.svg", "w3.svg", "w4.svg", "w5.svg", "w6.svg", "w7.svg", "w8.svg", "w9.svg",
    "d1.svg", "d2.svg", "d3.svg", "d4.svg", "d5.svg", "d6.svg", "d7.svg", "d8.svg", "d9.svg",
    "s1.svg", "s2.svg", "s3.svg", "s4.svg", "s5.svg", "s6.svg", "s7.svg", "s8.svg", "s9.svg",
    "east.svg", "south.svg", "west.svg", "north.svg", "red.svg", "green.svg", "white.svg",
    "spring.svg", "summer.svg", "autumn.svg", "winter.svg", "plum.svg", "orchid.svg", "bamboo.svg", "chrysanthemum.svg"
  ];

  function isFlower(type) {
    return type >= FLOWER_START;
  }

  function isSuited(type) {
    return type >= 0 && type < 27;
  }

  function tileName(type) {
    return TILE_NAMES[type] || "未知牌";
  }

  function tileAsset(type) {
    return "tiles/" + TILE_ASSETS[type];
  }

  function createDeck() {
    const deck = [];
    let id = 1;
    for (let type = 0; type < FLOWER_START; type += 1) {
      for (let copy = 0; copy < 4; copy += 1) deck.push({ type: type, id: id++ });
    }
    for (let type = FLOWER_START; type < TILE_NAMES.length; type += 1) {
      deck.push({ type: type, id: id++ });
    }
    return deck;
  }

  function shuffle(items, rng) {
    const random = rng || Math.random;
    const copy = items.slice();
    for (let i = copy.length - 1; i > 0; i -= 1) {
      const j = Math.floor(random() * (i + 1));
      const value = copy[i];
      copy[i] = copy[j];
      copy[j] = value;
    }
    return copy;
  }

  function sortTiles(tiles) {
    tiles.sort(function (a, b) {
      return a.type - b.type || a.id - b.id;
    });
    return tiles;
  }

  function countsOf(types) {
    const counts = new Array(FLOWER_START).fill(0);
    types.forEach(function (type) {
      if (!isFlower(type)) counts[type] += 1;
    });
    return counts;
  }

  function canFormMelds(counts, groupsLeft, memo) {
    if (groupsLeft === 0) return counts.every(function (value) { return value === 0; });
    const key = groupsLeft + ":" + counts.join("");
    if (memo[key] !== undefined) return memo[key];
    let first = -1;
    for (let i = 0; i < counts.length; i += 1) {
      if (counts[i] > 0) {
        first = i;
        break;
      }
    }
    if (first < 0) return false;

    if (counts[first] >= 3) {
      counts[first] -= 3;
      if (canFormMelds(counts, groupsLeft - 1, memo)) {
        counts[first] += 3;
        memo[key] = true;
        return true;
      }
      counts[first] += 3;
    }

    if (isSuited(first) && first % 9 <= 6 && counts[first + 1] > 0 && counts[first + 2] > 0) {
      counts[first] -= 1;
      counts[first + 1] -= 1;
      counts[first + 2] -= 1;
      if (canFormMelds(counts, groupsLeft - 1, memo)) {
        counts[first] += 1;
        counts[first + 1] += 1;
        counts[first + 2] += 1;
        memo[key] = true;
        return true;
      }
      counts[first] += 1;
      counts[first + 1] += 1;
      counts[first + 2] += 1;
    }

    memo[key] = false;
    return false;
  }

  function isWinningHand(types, exposedMeldCount) {
    const meldCount = exposedMeldCount || 0;
    const clean = types.filter(function (type) { return !isFlower(type); });
    const groupsNeeded = 4 - meldCount;
    if (groupsNeeded < 0 || clean.length !== groupsNeeded * 3 + 2) return false;
    const counts = countsOf(clean);

    if (meldCount === 0 && clean.length === 14) {
      const pairCount = counts.reduce(function (sum, value) { return sum + Math.floor(value / 2); }, 0);
      if (pairCount === 7 && counts.every(function (value) { return value % 2 === 0; })) return true;
    }

    for (let pair = 0; pair < counts.length; pair += 1) {
      if (counts[pair] < 2) continue;
      counts[pair] -= 2;
      if (canFormMelds(counts, groupsNeeded, Object.create(null))) {
        counts[pair] += 2;
        return true;
      }
      counts[pair] += 2;
    }
    return false;
  }

  function chowOptions(handTypes, incomingType) {
    if (!isSuited(incomingType)) return [];
    const suitStart = Math.floor(incomingType / 9) * 9;
    const rank = incomingType - suitStart;
    const counts = countsOf(handTypes);
    const options = [];
    for (let startRank = rank - 2; startRank <= rank; startRank += 1) {
      if (startRank < 0 || startRank > 6) continue;
      const sequence = [suitStart + startRank, suitStart + startRank + 1, suitStart + startRank + 2];
      const required = sequence.slice();
      required.splice(required.indexOf(incomingType), 1);
      if (counts[required[0]] > 0 && counts[required[1]] > 0) options.push(sequence);
    }
    return options;
  }

  function kongOptions(handTypes, melds) {
    const counts = countsOf(handTypes);
    const result = [];
    for (let type = 0; type < FLOWER_START; type += 1) {
      if (counts[type] === 4) result.push({ kind: "concealed", type: type });
    }
    (melds || []).forEach(function (meld, index) {
      if (meld.kind === "pong" && counts[meld.tiles[0]] > 0) {
        result.push({ kind: "added", type: meld.tiles[0], meldIndex: index });
      }
    });
    return result;
  }

  function structureScore(types, exposedMeldCount) {
    const counts = countsOf(types);
    const memo = Object.create(null);
    function solve() {
      const key = counts.join("");
      if (memo[key] !== undefined) return memo[key];
      let first = -1;
      for (let i = 0; i < counts.length; i += 1) {
        if (counts[i] > 0) {
          first = i;
          break;
        }
      }
      if (first < 0) return 0;

      counts[first] -= 1;
      let best = solve() - (first >= 27 ? 2 : 0);
      counts[first] += 1;

      if (counts[first] >= 2) {
        counts[first] -= 2;
        best = Math.max(best, 7 + solve());
        counts[first] += 2;
      }
      if (counts[first] >= 3) {
        counts[first] -= 3;
        best = Math.max(best, 24 + solve());
        counts[first] += 3;
      }
      if (isSuited(first)) {
        const rank = first % 9;
        if (rank <= 6 && counts[first + 1] > 0 && counts[first + 2] > 0) {
          counts[first] -= 1;
          counts[first + 1] -= 1;
          counts[first + 2] -= 1;
          best = Math.max(best, 24 + solve());
          counts[first] += 1;
          counts[first + 1] += 1;
          counts[first + 2] += 1;
        }
        if (rank <= 7 && counts[first + 1] > 0) {
          counts[first] -= 1;
          counts[first + 1] -= 1;
          best = Math.max(best, 4 + solve());
          counts[first] += 1;
          counts[first + 1] += 1;
        }
        if (rank <= 6 && counts[first + 2] > 0) {
          counts[first] -= 1;
          counts[first + 2] -= 1;
          best = Math.max(best, 3 + solve());
          counts[first] += 1;
          counts[first + 2] += 1;
        }
      }
      memo[key] = best;
      return best;
    }
    return (exposedMeldCount || 0) * 24 + solve();
  }

  function chooseDiscardIndex(hand, meldCount, visibleCounts, rng) {
    const random = rng || Math.random;
    let bestScore = -Infinity;
    let bestIndices = [];
    const tried = Object.create(null);
    for (let index = 0; index < hand.length; index += 1) {
      const type = hand[index].type;
      if (tried[type]) continue;
      tried[type] = true;
      const remaining = hand.filter(function (_, i) { return i !== index; }).map(function (tile) { return tile.type; });
      let winningOuts = 0;
      for (let draw = 0; draw < FLOWER_START; draw += 1) {
        if (isWinningHand(remaining.concat(draw), meldCount)) {
          const alreadyVisible = visibleCounts && visibleCounts[draw] ? visibleCounts[draw] : 0;
          const inHand = remaining.filter(function (value) { return value === draw; }).length;
          winningOuts += Math.max(0, 4 - alreadyVisible - inHand);
        }
      }
      let score = winningOuts * 120 + structureScore(remaining, meldCount);
      if (type >= 27) score += 0.15;
      if (score > bestScore + 0.001) {
        bestScore = score;
        bestIndices = [index];
      } else if (Math.abs(score - bestScore) < 0.001) {
        bestIndices.push(index);
      }
    }
    return bestIndices[Math.floor(random() * bestIndices.length)] || 0;
  }

  return {
    FLOWER_START: FLOWER_START,
    TILE_NAMES: TILE_NAMES,
    TILE_ASSETS: TILE_ASSETS,
    isFlower: isFlower,
    isSuited: isSuited,
    tileName: tileName,
    tileAsset: tileAsset,
    createDeck: createDeck,
    shuffle: shuffle,
    sortTiles: sortTiles,
    countsOf: countsOf,
    isWinningHand: isWinningHand,
    chowOptions: chowOptions,
    kongOptions: kongOptions,
    structureScore: structureScore,
    chooseDiscardIndex: chooseDiscardIndex
  };
});
