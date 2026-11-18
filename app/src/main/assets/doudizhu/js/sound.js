'use strict';
/* sound.js — local speech pack for all three seats and the narrator. */

const Snd = (() => {
  const SEAT_VOICES = [
    'voice_01_youth_male',
    'voice_02_dubbed_male',
    'voice_03_entertainment_female',
  ];
  const NARRATOR_VOICE = 'narrator_female';
  const RANK_FILES = {
    3: '3', 4: '4', 5: '5', 6: '6', 7: '7', 8: '8', 9: '9', 10: '10',
    11: 'j', 12: 'q', 13: 'k', 14: 'a', 15: '2',
  };
  const COMBO_FILES = {
    trio_single: 'combo_trio_single.wav',
    trio_pair: 'combo_trio_pair.wav',
    straight: 'combo_straight.wav',
    pair_straight: 'combo_pair_straight.wav',
    plane: 'combo_plane.wav',
    plane_single: 'combo_plane_single.wav',
    plane_pair: 'combo_plane_pair.wav',
    four_two: 'combo_four_two.wav',
    four_two_pairs: 'combo_four_two_pairs.wav',
    bomb: 'combo_bomb.wav',
    rocket: 'combo_rocket.wav',
  };
  const SYSTEM_FILES = {
    game_start: 'system_game_start.wav',
    start_bidding: 'system_start_bidding.wav',
    role_landlord: 'system_role_landlord.wav',
    role_farmer: 'system_role_farmer.wav',
    your_turn: 'system_your_turn.wav',
    win: 'result_win.wav',
    lose: 'result_lose.wav',
  };

  let enabled = true;
  let current = null;
  let queue = [];
  let generation = 0;
  try { enabled = localStorage.getItem('ddz_snd') !== '0'; } catch (e) { }

  function playerPath(seat, filename) {
    const voice = SEAT_VOICES[seat] || SEAT_VOICES[0];
    return `audio/${voice}/${filename}`;
  }

  function narratorPath(filename) {
    return `audio/${NARRATOR_VOICE}/${filename}`;
  }

  function stopAll() {
    generation += 1;
    queue = [];
    if (!current) return;
    const audio = current;
    current = null;
    audio.pause();
    try { audio.currentTime = 0; } catch (e) { }
    audio.removeAttribute('src');
  }

  function pump() {
    if (!enabled || current || !queue.length) return;
    const myGeneration = generation;
    const audio = new Audio(queue.shift());
    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      if (current === audio) current = null;
      if (myGeneration === generation) pump();
    };
    current = audio;
    audio.preload = 'auto';
    audio.volume = 1;
    audio.addEventListener('ended', finish, { once: true });
    audio.addEventListener('error', finish, { once: true });
    const result = audio.play();
    if (result && typeof result.catch === 'function') result.catch(finish);
  }

  function enqueue(path) {
    if (!enabled || !path) return;
    queue.push(path);
    pump();
  }

  function enqueuePlayer(seat, filename) {
    if (filename) enqueue(playerPath(seat, filename));
  }

  function rankFilename(prefix, rank) {
    if (rank === 16 && prefix === 'rank') return 'joker_small.wav';
    if (rank === 17 && prefix === 'rank') return 'joker_big.wav';
    const key = RANK_FILES[rank];
    return key ? `${prefix}_${key}.wav` : null;
  }

  function playMove(seat, combo) {
    if (!combo) return;
    let filename = null;
    if (combo.type === 'single') filename = rankFilename('rank', combo.rank);
    else if (combo.type === 'pair') filename = rankFilename('pair', combo.rank);
    else if (combo.type === 'trio') filename = rankFilename('trio', combo.rank);
    else filename = COMBO_FILES[combo.type] || null;
    enqueuePlayer(seat, filename);
  }

  return {
    get enabled() { return enabled; },
    toggle() {
      enabled = !enabled;
      try { localStorage.setItem('ddz_snd', enabled ? '1' : '0'); } catch (e) { }
      if (!enabled) stopAll();
      return enabled;
    },
    stopAll,
    unlock() { pump(); },
    playMove,
    pass(seat) { enqueuePlayer(seat, 'pass.wav'); },
    bid(seat, value) { enqueuePlayer(seat, `bid_${value}.wav`); },
    warning(seat, count) {
      if (count === 2) enqueuePlayer(seat, 'warning_two_cards.wav');
      else if (count === 1) enqueuePlayer(seat, 'warning_one_card.wav');
    },
    system(kind) {
      const filename = SYSTEM_FILES[kind];
      if (filename) enqueue(narratorPath(filename));
    },
    result(won) { enqueue(narratorPath(won ? SYSTEM_FILES.win : SYSTEM_FILES.lose)); },
    landlord(playerIsLandlord) {
      enqueue(narratorPath(playerIsLandlord ? SYSTEM_FILES.role_landlord : SYSTEM_FILES.role_farmer));
    },
  };
})();
