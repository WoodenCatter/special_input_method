/*
 * BCI 3-Key (LEFT_LOOK / RIGHT_LOOK / BITE) & Touch Adapter for A Dark Room
 * Matches the app's unified ScanCursor behavior (AUTO & MANUAL modes, graceMs delay
 * compensation, dwell progress bar, Coral day/night sync, BCI-fair combat & space tuning).
 */
(function () {
	'use strict';

	var cfg = {
		intervalMs: 1500,
		manual: false,
		paused: false,
		graceMs: 400,
		isDark: false
	};

	var state = {
		items: [],
		index: 0,
		direction: 1,
		previousIndex: -1,
		advancedAtMs: 0,
		timerId: null,
		refreshTimerId: null,
		lastContextKey: '',
		combatPausedByHost: false,
		spaceStopTimerId: null,
		lastReported: ''
	};

	function host() {
		return window.InputDsAdrHost || null;
	}

	function notifyHostFeedback(msg) {
		try {
			var h = host();
			if (h && h.notifyFeedback) h.notifyFeedback(String(msg));
		} catch (e) {}
	}

	function notifyContextBoundary() {
		try {
			var h = host();
			if (h && h.onContextBoundary) h.onContextBoundary();
		} catch (e) {}
	}

	function reportActionHints(left, bite, right, dir) {
		var key = [left, bite, right, dir].join('|');
		if (key === state.lastReported) return;
		state.lastReported = key;
		try {
			var h = host();
			if (h && h.scanState) {
				h.scanState(String(left || ''), String(bite || ''), String(right || ''), Number(dir || 0));
			}
		} catch (e) {}
	}

	function cleanButtonText(el) {
		if (!el) return '';
		var clone = el.cloneNode(true);
		var removeSel = clone.querySelectorAll('.tooltip, .cooldown, .bci-dwell-track, .bci-dir-badge');
		for (var i = 0; i < removeSel.length; i++) {
			removeSel[i].parentNode && removeSel[i].parentNode.removeChild(removeSel[i]);
		}
		return (clone.textContent || '').replace(/\s+/g, ' ').trim();
	}

	function isVisibleEl(el) {
		if (!el || !el.isConnected) return false;
		var style = window.getComputedStyle(el);
		if (style.display === 'none' || style.visibility === 'hidden' || parseFloat(style.opacity || '1') < 0.08) {
			return false;
		}
		var rect = el.getBoundingClientRect();
		return rect.width > 2 && rect.height > 2;
	}

	function isSpaceActive() {
		return typeof Engine !== 'undefined' &&
			typeof Space !== 'undefined' &&
			Engine.activeModule === Space &&
			!Space.done;
	}

	function isWorldActive() {
		return typeof Engine !== 'undefined' &&
			typeof World !== 'undefined' &&
			Engine.activeModule === World;
	}

	function getActiveEventEl() {
		var ev = document.getElementById('event');
		return (ev && isVisibleEl(ev)) ? ev : null;
	}

	function ensureWorldPad() {
		var pad = document.getElementById('bciWorldPad');
		if (!pad) {
			pad = document.createElement('div');
			pad.id = 'bciWorldPad';
			document.body.appendChild(pad);
		}
		var show = isWorldActive() && !getActiveEventEl();
		pad.classList.toggle('visible', show);
		if (!show) return pad;

		var canMeat = typeof World !== 'undefined' && typeof Path !== 'undefined' &&
			World.health < World.getMaxHealth() && (Path.outfit && (Path.outfit['cured meat'] || 0) > 0);
		var canMeds = typeof World !== 'undefined' && typeof Path !== 'undefined' &&
			World.health < World.getMaxHealth() && (Path.outfit && (Path.outfit['medicine'] || 0) > 0);

		var sig = 'w:' + (canMeat ? 1 : 0) + ':' + (canMeds ? 1 : 0);
		if (pad.dataset.sig === sig) return pad;
		pad.dataset.sig = sig;
		pad.innerHTML = '';

		var specs = [
			{ id: 'bciWorldN', label: '↑ 向北', fn: function () { World.move(World.NORTH); } },
			{ id: 'bciWorldE', label: '→ 向东', fn: function () { World.move(World.EAST); } },
			{ id: 'bciWorldS', label: '↓ 向南', fn: function () { World.move(World.SOUTH); } },
			{ id: 'bciWorldW', label: '← 向西', fn: function () { World.move(World.WEST); } }
		];
		if (canMeat) {
			specs.push({ id: 'bciWorldMeat', label: '吃熏肉', fn: function () { World.useSupplies && World.useSupplies('cured meat'); } });
		}
		if (canMeds) {
			specs.push({ id: 'bciWorldMeds', label: '用医药', fn: function () { World.useSupplies && World.useSupplies('medicine'); } });
		}
		specs.forEach(function (sp) {
			var b = document.createElement('button');
			b.type = 'button';
			b.id = sp.id;
			b.className = 'bci-world-btn';
			b.textContent = sp.label;
			b.addEventListener('click', function () {
				if (cfg.paused) return;
				sp.fn();
				scheduleRefresh();
			});
			pad.appendChild(b);
		});
		return pad;
	}

	function ensureSpaceHud() {
		var hud = document.getElementById('bciSpaceHud');
		if (!hud) {
			hud = document.createElement('div');
			hud.id = 'bciSpaceHud';
			hud.textContent = '左看：向左躲避  ·  咬牙：悬停稳定  ·  右看：向右躲避';
			document.body.appendChild(hud);
		}
		hud.classList.toggle('visible', isSpaceActive());
	}

	function updateSoundButtonLabel() {
		var btn = document.getElementById('bciSoundBtn');
		if (!btn || typeof $SM === 'undefined') return;
		var on = Boolean($SM.get('config.soundOn'));
		btn.textContent = on ? '音效：开' : '音效：关';
	}

	function collectScanItems() {
		ensureWorldPad();
		ensureSpaceHud();
		updateSoundButtonLabel();

		if (isSpaceActive()) {
			return [];
		}

		var out = [];
		var ev = getActiveEventEl();

		if (ev) {
			// Priority 1: Event modal (story choices, combat actions, loot, restart confirmation)
			var evBtns = ev.querySelectorAll('#lootButtons .button:not(.disabled), #buttons .button:not(.disabled)');
			var list = [];
			for (var i = 0; i < evBtns.length; i++) {
				var el = evBtns[i];
				if (!isVisibleEl(el)) continue;
				var lbl = cleanButtonText(el);
				if (!lbl) continue;
				list.push({ el: el, label: lbl, id: el.id || ('ev_' + i) });
			}
			// Safety: if a restart/confirm dialog has both 'no' and 'yes', put 'no' first (cancel-first rule)
			var noIdx = -1;
			var yesIdx = -1;
			for (var j = 0; j < list.length; j++) {
				if (list[j].id === 'no') noIdx = j;
				if (list[j].id === 'yes') yesIdx = j;
			}
			if (noIdx > 0 && yesIdx >= 0 && noIdx > yesIdx) {
				var noItem = list.splice(noIdx, 1)[0];
				list.unshift(noItem);
			}
			return list;
		}

		if (isWorldActive()) {
			// Priority 2: Wasteland 4-direction movement + heals + exit
			var padBtns = document.querySelectorAll('#bciWorldPad .bci-world-btn');
			for (var w = 0; w < padBtns.length; w++) {
				if (isVisibleEl(padBtns[w])) {
					out.push({ el: padBtns[w], label: padBtns[w].textContent.trim(), id: padBtns[w].id });
				}
			}
			var exitW = document.getElementById('bciExitBtn');
			if (exitW && isVisibleEl(exitW)) {
				out.push({ el: exitW, label: '离开小黑屋', id: 'bciExitBtn' });
			}
			return out;
		}

		// Priority 3: Standard game module panel (Room / Outside / Path / Ship / Fabricator)
		var panel = (typeof Engine !== 'undefined' && Engine.activeModule && Engine.activeModule.panel)
			? Engine.activeModule.panel[0]
			: document.querySelector('div.location');

		if (panel) {
			// 3a. Primary enabled buttons inside active panel
			var panelBtns = panel.querySelectorAll('.button:not(.disabled)');
			for (var p = 0; p < panelBtns.length; p++) {
				var btn = panelBtns[p];
				if (!isVisibleEl(btn)) continue;
				var text = cleanButtonText(btn);
				if (!text) continue;
				out.push({ el: btn, label: text, id: btn.id || ('btn_' + text) });
			}

			// 3b. Worker assignment +/- buttons in Outside
			var workerRows = panel.querySelectorAll('.workerRow');
			for (var wr = 0; wr < workerRows.length; wr++) {
				var row = workerRows[wr];
				if (!isVisibleEl(row)) continue;
				var keyName = (row.querySelector('.row_key') || {}).textContent || '';
				keyName = keyName.trim();
				var upW = row.querySelector('.upBtn:not(.disabled)');
				var dnW = row.querySelector('.dnBtn:not(.disabled)');
				if (upW && isVisibleEl(upW)) {
					out.push({ el: upW, label: '增加' + keyName, id: (row.id || wr) + '_up' });
				}
				if (dnW && isVisibleEl(dnW)) {
					out.push({ el: dnW, label: '减少' + keyName, id: (row.id || wr) + '_dn' });
				}
			}

			// 3c. Outfitting supply +/- buttons in Path
			var outfitRows = panel.querySelectorAll('.outfitRow');
			for (var or = 0; or < outfitRows.length; or++) {
				var orow = outfitRows[or];
				if (!isVisibleEl(orow)) continue;
				var itemName = (orow.querySelector('.row_key') || {}).textContent || '';
				itemName = itemName.trim();
				var upO = orow.querySelector('.upBtn:not(.disabled)');
				var dnO = orow.querySelector('.dnBtn:not(.disabled)');
				if (upO && isVisibleEl(upO)) {
					out.push({ el: upO, label: '携带' + itemName + ' +1', id: (orow.id || or) + '_up' });
				}
				if (dnO && isVisibleEl(dnO)) {
					out.push({ el: dnO, label: '减少' + itemName + ' -1', id: (orow.id || or) + '_dn' });
				}
			}
		}

		// 3d. Header location tabs (non-selected)
		var tabs = document.querySelectorAll('#header .headerButton:not(.selected)');
		for (var t = 0; t < tabs.length; t++) {
			if (!isVisibleEl(tabs[t])) continue;
			var tName = cleanButtonText(tabs[t]);
			if (tName) {
				out.push({ el: tabs[t], label: '去「' + tName + '」', id: tabs[t].id || ('tab_' + tName) });
			}
		}

		// 3e. Utility buttons (Sound + Exit; Restart is touch-only to prevent accidental save wipes)
		var soundBtn = document.getElementById('bciSoundBtn');
		if (soundBtn && isVisibleEl(soundBtn)) {
			out.push({ el: soundBtn, label: soundBtn.textContent.trim(), id: 'bciSoundBtn' });
		}
		var exitBtn = document.getElementById('bciExitBtn');
		if (exitBtn && isVisibleEl(exitBtn)) {
			out.push({ el: exitBtn, label: '离开小黑屋', id: 'bciExitBtn' });
		}

		return out;
	}

	function clearVisualFocus() {
		var prev = document.querySelectorAll('.bci-focus');
		for (var i = 0; i < prev.length; i++) {
			prev[i].classList.remove('bci-focus');
		}
		var tracks = document.querySelectorAll('.bci-dwell-track, .bci-dir-badge');
		for (var j = 0; j < tracks.length; j++) {
			tracks[j].parentNode && tracks[j].parentNode.removeChild(tracks[j]);
		}
	}

	function renderFocus() {
		clearVisualFocus();

		if (isSpaceActive()) {
			reportActionHints('向左躲避', '悬停稳定', '向右躲避', 0);
			return;
		}

		var items = state.items;
		if (!items.length) {
			var inEvent = !!getActiveEventEl();
			reportActionHints('', inEvent ? '等待冷却…' : '等待中…', '', 0);
			return;
		}

		if (state.index < 0 || state.index >= items.length) {
			state.index = 0;
		}
		var current = items[state.index];
		var el = current.el;
		el.classList.add('bci-focus');

		try {
			el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
		} catch (e) {}

		var dir = cfg.manual ? 0 : state.direction;
		if (!cfg.manual && items.length > 1) {
			var badge = document.createElement('span');
			badge.className = 'bci-dir-badge';
			badge.textContent = state.direction < 0 ? '←' : '→';
			el.appendChild(badge);
		}

		if (!cfg.manual && !cfg.paused && items.length > 1) {
			var track = document.createElement('div');
			track.className = 'bci-dwell-track' + (state.direction < 0 ? ' rtl' : '');
			var fill = document.createElement('div');
			fill.className = 'bci-dwell-fill';
			track.appendChild(fill);
			el.appendChild(track);
			// Kick off linear dwell animation synced to cfg.intervalMs
			void fill.offsetWidth;
			fill.style.transition = 'width ' + cfg.intervalMs + 'ms linear';
			fill.style.width = '100%';
		}

		if (cfg.manual) {
			reportActionHints('上一项', '选「' + current.label + '」', '下一项', 0);
		} else {
			reportActionHints('向左轮转', '选「' + current.label + '」', '向右轮转', dir);
		}
	}

	function stopScanTimer() {
		if (state.timerId !== null) {
			clearTimeout(state.timerId);
			state.timerId = null;
		}
	}

	function scheduleScanTick() {
		stopScanTimer();
		if (cfg.paused || cfg.manual || isSpaceActive() || state.items.length <= 1) {
			return;
		}
		state.timerId = setTimeout(function () {
			try {
				advanceAutoTick();
			} finally {
				scheduleScanTick();
			}
		}, cfg.intervalMs);
	}

	function advanceAutoTick() {
		if (cfg.paused || cfg.manual || isSpaceActive()) return;
		var n = state.items.length;
		if (n <= 1) return;
		state.previousIndex = state.index;
		state.advancedAtMs = Date.now();
		state.index = (state.index + state.direction + n) % n;
		renderFocus();
	}

	function currentContextKey() {
		var ev = getActiveEventEl();
		if (ev) {
			var title = (ev.querySelector('.eventTitle') || {}).textContent || '';
			var scene = (typeof Events !== 'undefined' && Events.activeScene) ? Events.activeScene : '';
			return 'event:' + title + ':' + scene;
		}
		if (isSpaceActive()) return 'space';
		if (isWorldActive()) return 'world';
		var mod = (typeof Engine !== 'undefined' && Engine.activeModule && Engine.activeModule.name) ? Engine.activeModule.name : 'init';
		return 'mod:' + mod;
	}

	function refreshItems(forceReset) {
		var prevFocusedEl = (state.items[state.index] && state.items[state.index].el) || null;
		var prevFocusedId = (state.items[state.index] && state.items[state.index].id) || null;
		var ctxKey = currentContextKey();
		var contextChanged = ctxKey !== state.lastContextKey;
		if (contextChanged) {
			state.lastContextKey = ctxKey;
			notifyContextBoundary();
		}

		state.items = collectScanItems();

		if (forceReset || contextChanged) {
			state.index = 0;
			state.direction = 1;
			state.previousIndex = -1;
			state.advancedAtMs = 0;
		} else if (prevFocusedEl) {
			var keptIdx = -1;
			for (var i = 0; i < state.items.length; i++) {
				if (state.items[i].el === prevFocusedEl || (prevFocusedId && state.items[i].id === prevFocusedId)) {
					keptIdx = i;
					break;
				}
			}
			if (keptIdx >= 0) {
				state.index = keptIdx;
			} else if (state.index >= state.items.length) {
				state.index = 0;
			}
		} else {
			state.index = 0;
		}

		renderFocus();
		scheduleScanTick();
	}

	function scheduleRefresh(forceReset) {
		if (state.refreshTimerId !== null) {
			clearTimeout(state.refreshTimerId);
		}
		state.refreshTimerId = setTimeout(function () {
			state.refreshTimerId = null;
			refreshItems(Boolean(forceReset));
		}, 70);
	}

	function activateIndex(idx) {
		var item = state.items[idx];
		if (!item || !item.el) return;
		var el = item.el;
		el.classList.add('bci-picked');
		setTimeout(function () { el.classList.remove('bci-picked'); }, 350);

		try {
			if (typeof Engine !== 'undefined' && Engine.resumeAudioContext) {
				Engine.resumeAudioContext();
			}
		} catch (e) {}

		el.click();
		state.previousIndex = -1;
		state.advancedAtMs = 0;
		scheduleRefresh(false);
	}

	function handleSpaceAction(action) {
		if (typeof Space === 'undefined' || Space.done) return;
		if (state.spaceStopTimerId !== null) {
			clearTimeout(state.spaceStopTimerId);
			state.spaceStopTimerId = null;
		}
		if (action === 'look_left') {
			Space.left = true;
			Space.right = false;
			state.spaceStopTimerId = setTimeout(function () {
				Space.left = false;
			}, 420);
		} else if (action === 'look_right') {
			Space.right = true;
			Space.left = false;
			state.spaceStopTimerId = setTimeout(function () {
				Space.right = false;
			}, 420);
		} else if (action === 'bite') {
			Space.left = false;
			Space.right = false;
			Space.up = false;
			Space.down = false;
		}
	}

	function handleAction(action) {
		if (cfg.paused) return;
		if (isSpaceActive()) {
			handleSpaceAction(action);
			return;
		}

		if (!state.items.length) {
			refreshItems(false);
			if (!state.items.length) return;
		}

		var n = state.items.length;
		if (action === 'look_left' || action === 'look_right') {
			var step = action === 'look_left' ? -1 : 1;
			if (cfg.manual) {
				state.index = (state.index + step + n) % n;
				state.previousIndex = -1;
				state.advancedAtMs = 0;
			} else {
				state.direction = step;
				state.previousIndex = -1;
				state.advancedAtMs = 0;
			}
			renderFocus();
			scheduleScanTick();
			return;
		}

		if (action === 'bite') {
			var targetIdx = state.index;
			if (!cfg.manual && state.previousIndex >= 0 && state.previousIndex < n && state.advancedAtMs > 0) {
				if (Date.now() - state.advancedAtMs < cfg.graceMs) {
					targetIdx = state.previousIndex;
					state.index = targetIdx;
					notifyHostFeedback('已按上一项执行（延迟补偿）');
				}
			}
			activateIndex(targetIdx);
		}
	}

	function applyPausedState(paused) {
		// Pause/resume audio cleanly
		try {
			if (typeof AudioEngine !== 'undefined' && AudioEngine._audioContext) {
				if (paused) {
					AudioEngine._audioContext.suspend && AudioEngine._audioContext.suspend();
				} else if (typeof $SM !== 'undefined' && $SM.get('config.soundOn')) {
					AudioEngine._audioContext.resume && AudioEngine._audioContext.resume();
				}
			}
		} catch (e) {}

		// Save game on pause
		if (paused) {
			try {
				if (typeof Engine !== 'undefined' && typeof State !== 'undefined' && Object.keys(State).length > 0) {
					Engine.saveGame();
				}
			} catch (e) {}
		}

		// Pause/resume active combat enemy attacks so patient is never hit while caregiver sheet or confirm overlay is open
		try {
			if (typeof Events !== 'undefined') {
				if (paused) {
					if (Events._enemyAttackTimer) {
						clearInterval(Events._enemyAttackTimer);
						Events._enemyAttackTimer = null;
						state.combatPausedByHost = true;
					}
				} else if (state.combatPausedByHost) {
					state.combatPausedByHost = false;
					if (document.getElementById('fight') && !Events.won && Events.activeEvent && Events.activeEvent()) {
						Events.startEnemyAttacks();
					}
				}
			}
		} catch (e) {}
	}

	function installGameHooks() {
		// 1. Slow enemy attacks by 2.2x for fair BCI 3-key combat
		if (typeof Events !== 'undefined' && Events.startEnemyAttacks) {
			var origEnemyAttacks = Events.startEnemyAttacks;
			Events.startEnemyAttacks = function (delay) {
				if (cfg.paused) {
					state.combatPausedByHost = true;
					return;
				}
				var scene = Events.activeEvent && Events.activeEvent() && Events.activeEvent().scenes[Events.activeScene];
				var baseDelay = (typeof delay === 'number') ? delay : (scene ? scene.attackDelay : 2);
				return origEnemyAttacks.call(Events, baseDelay * 2.2);
			};
		}

		// 2. Slow asteroid speed/spawn in Space for fair 3-key gliding
		if (typeof Space !== 'undefined') {
			Space.BASE_ASTEROID_SPEED = 2700;
			Space.SHIP_SPEED = 5;
			var origCreateAsteroid = Space.createAsteroid;
			if (origCreateAsteroid) {
				Space.createAsteroid = function (noNext) {
					if (cfg.paused && !Space.done) {
						Engine.setTimeout(Space.createAsteroid, 800, true);
						return;
					}
					return origCreateAsteroid.call(Space, noNext);
				};
			}
		}

		// 3. Hook module & event transitions so scan ring refreshes immediately
		if (typeof Engine !== 'undefined' && Engine.travelTo) {
			var origTravelTo = Engine.travelTo;
			Engine.travelTo = function (mod) {
				var res = origTravelTo.apply(Engine, arguments);
				scheduleRefresh(true);
				return res;
			};
		}
		if (typeof Events !== 'undefined') {
			['startEvent', 'endEvent', 'loadScene'].forEach(function (fnName) {
				if (Events[fnName]) {
					var orig = Events[fnName];
					Events[fnName] = function () {
						var res = orig.apply(Events, arguments);
						scheduleRefresh(true);
						return res;
					};
				}
			});
		}

		// 4. Wire top-right utility buttons
		var soundBtn = document.getElementById('bciSoundBtn');
		if (soundBtn) {
			soundBtn.addEventListener('click', function () {
				if (typeof Engine !== 'undefined' && Engine.toggleVolume) {
					Engine.toggleVolume();
					updateSoundButtonLabel();
					notifyHostFeedback(soundBtn.textContent);
					scheduleRefresh(false);
				}
			});
		}

		var restartBtn = document.getElementById('bciRestartBtn');
		if (restartBtn) {
			restartBtn.addEventListener('click', function () {
				if (typeof Engine !== 'undefined' && Engine.confirmDelete) {
					Engine.confirmDelete();
					scheduleRefresh(true);
				}
			});
		}

		var exitBtn = document.getElementById('bciExitBtn');
		if (exitBtn) {
			exitBtn.addEventListener('click', function () {
				try {
					if (typeof Engine !== 'undefined' && typeof State !== 'undefined') {
						Engine.saveGame();
					}
				} catch (e) {}
				var h = host();
				if (h && h.requestExit) h.requestExit();
			});
		}

		// 5. Watch DOM mutations (cooldown completions, newly unlocked buildings/buttons, loot changes)
		if (typeof MutationObserver !== 'undefined') {
			var observer = new MutationObserver(function (mutations) {
				for (var i = 0; i < mutations.length; i++) {
					var target = mutations[i].target;
					if (target && target.classList && (
						target.classList.contains('bci-dwell-track') ||
						target.classList.contains('bci-dwell-fill') ||
						target.classList.contains('bci-dir-badge') ||
						target.classList.contains('cooldown') ||
						target.classList.contains('asteroid')
					)) {
						continue;
					}
					scheduleRefresh(false);
					break;
				}
			});
			observer.observe(document.body, {
				childList: true,
				subtree: true,
				attributes: true,
				attributeFilter: ['class', 'style']
			});
		}
	}

	window.inputDsAdr = {
		configure: function (intervalMs, manual, paused, graceMs, isDark) {
			var prevPaused = cfg.paused;
			cfg.intervalMs = Math.max(1100, Math.min(3000, Number(intervalMs) || 1500));
			cfg.manual = Boolean(manual);
			cfg.paused = Boolean(paused);
			cfg.graceMs = Math.max(0, Number(graceMs) || 0);
			cfg.isDark = Boolean(isDark);

			document.body.classList.toggle('bci-dark', cfg.isDark);
			if (prevPaused !== cfg.paused) {
				applyPausedState(cfg.paused);
			}
			state.previousIndex = -1;
			state.advancedAtMs = 0;
			renderFocus();
			scheduleScanTick();
		},
		handleAction: handleAction,
		debugTick: function () {
			advanceAutoTick();
		},
		saveAndPause: function () {
			applyPausedState(true);
		}
	};

	window.addEventListener('DOMContentLoaded', function () {
		// Wait briefly for jQuery ready / Engine.init() to populate Room
		setTimeout(function () {
			installGameHooks();
			refreshItems(true);
		}, 180);
	});
})();
