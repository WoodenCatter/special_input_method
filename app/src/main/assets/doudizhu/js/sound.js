'use strict';

const Snd = (() => {
  let audioContext;
  let enabled = true;
  try { enabled = localStorage.getItem('ddz_snd') !== '0'; } catch (error) { /* Optional storage. */ }

  function unlock() {
    if (!enabled || !window.AudioContext) return;
    audioContext ??= new AudioContext();
    audioContext.resume().catch(() => {});
  }

  function stopAll() {
    audioContext?.suspend().catch(() => {});
  }

  function cue(frequency) {
    unlock();
    if (audioContext?.state !== 'running') return;
    const oscillator = audioContext.createOscillator();
    const gain = audioContext.createGain();
    const now = audioContext.currentTime;
    oscillator.frequency.value = frequency;
    oscillator.connect(gain).connect(audioContext.destination);
    gain.gain.setValueAtTime(0.05, now);
    gain.gain.exponentialRampToValueAtTime(0.001, now + 0.12);
    oscillator.start(now);
    oscillator.stop(now + 0.12);
  }

  return {
    get enabled() { return enabled; },
    toggle() {
      enabled = !enabled;
      try { localStorage.setItem('ddz_snd', enabled ? '1' : '0'); } catch (error) { /* Optional storage. */ }
      if (enabled) unlock(); else stopAll();
      return enabled;
    },
    unlock,
    stopAll,
    playMove(_seat, combo) { if (combo) cue(combo.type === 'bomb' || combo.type === 'rocket' ? 290 : 500); },
    pass() { cue(240); },
    bid(_seat, value) { cue(320 + value * 55); },
    warning(_seat, count) { if (count <= 2) cue(750); },
    system() { cue(560); },
    result(won) { cue(won ? 780 : 220); },
    landlord(isLandlord) { cue(isLandlord ? 650 : 420); },
  };
})();
