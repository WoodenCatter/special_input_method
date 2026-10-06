'use strict';
/* music.js — supplied local BGM. Pausing the HTMLAudio element is immediate. */

const Bgm = (() => {
  const audio = new Audio('audio/bgm/doudizhu_bgm.mp3');
  audio.loop = true;
  audio.preload = 'auto';
  audio.volume = 0.36;

  let enabled = true;
  try { enabled = localStorage.getItem('ddz_bgm') !== '0'; } catch (e) { }

  function start() {
    if (!enabled || !audio.paused) return;
    const result = audio.play();
    if (result && typeof result.catch === 'function') result.catch(() => { });
  }

  function stop() {
    audio.pause();
    try { audio.currentTime = 0; } catch (e) { }
  }

  return {
    get enabled() { return enabled; },
    get playing() { return !audio.paused; },
    poke() { start(); },
    stop,
    toggle() {
      enabled = !enabled;
      try { localStorage.setItem('ddz_bgm', enabled ? '1' : '0'); } catch (e) { }
      if (enabled) start(); else stop();
      return enabled;
    },
  };
})();
