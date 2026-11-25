/*
 * BCI 3-Key (LEFT_LOOK / RIGHT_LOOK / BITE) & Touch Adapter for A Dark Room
 * Matches the app's unified ScanCursor behavior:
 * - AUTO & MANUAL scan modes, graceMs delay compensation, linear dwell bar, Coral day/night sync
 * - Self-mutation guard on MutationObserver so focus/dwell updates NEVER reset the scan timer
 * - Full coverage: Room, Outside (+1/-1/+10/-10), Path (+1/-1/+10/-10), World (bounds-safe moves,
 *   real item healing without charging travel steps, @ auto-scroll), stacked Events, #dropMenu,
 *   BCI-fair Combat (2.2x delay + full pause freeze), Ship, Fabricator, Space (pausable 3-key flight),
 *   Expansion Outro (#wait-btn), and Score Screen (.endGameOption restart)
 */
(function () {
	'use strict';

	var cfg = {
		intervalMs: 1500,
		manual: false,
		paused: false,
		frozen: false,
		graceMs: 400,
		isDark: false
	};

	window.__bciAdrPaused = false;

	var state = {
		items: [],
		itemsSig: '',
		index: 0,
		direction: 1,
		previousIndex: -1,
		advancedAtMs: 0,
		timerId: null,
		refreshTimerId: null,
		observer: null,
		isUpdatingDom: false,
		lastContextKey: '',
		combatPausedByHost: false,
		spaceStopTimerId: null,
		lastReported: '',
		ready: false
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

	function withAdapterDomMutation(fn) {
		state.isUpdatingDom = true;
		try {
			return fn();
		} finally {
			try {
				if (state.observer && state.observer.takeRecords) {
					state.observer.takeRecords();
				}
			} catch (e) {}
			state.isUpdatingDom = false;
		}
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

	function extractCostSummary(el) {
		if (!el) return '';
		var tt = el.querySelector(':scope > .tooltip');
		if (!tt) return '';
		var keys = tt.querySelectorAll('.row_key');
		var vals = tt.querySelectorAll('.row_val');
		if (!keys.length || keys.length !== vals.length) return '';
		var parts = [];
		for (var i = 0; i < keys.length && parts.length < 3; i++) {
			var k = (keys[i].textContent || '').trim();
			var v = (vals[i].textContent || '').trim();
			if (k && v) parts.push(k + v);
		}
		return parts.length ? ('(' + parts.join('·') + ')') : '';
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

	// Return the TOP-MOST visible .eventPanel (upstream stacks multiple #event panels in DOM order)
	function getTopEventEl() {
		var panels = document.querySelectorAll('.eventPanel');
		for (var i = panels.length - 1; i >= 0; i--) {
			if (isVisibleEl(panels[i])) return panels[i];
		}
		return null;
	}

	function healInWorld(itemKey, healAmount, soundKey) {
		if (typeof World === 'undefined' || typeof Path === 'undefined' || !Path.outfit) return;
		var count = Path.outfit[itemKey] || 0;
		if (count <= 0 || World.health >= World.getMaxHealth()) return;
		Path.outfit[itemKey] = count - 1;
		var nextHp = Math.min(World.getMaxHealth(), World.health + healAmount);
		World.setHp(nextHp);
		if (World.updateSupplies) World.updateSupplies();
		try {
			if (typeof AudioEngine !== 'undefined' && typeof AudioLibrary !== 'undefined' && AudioLibrary[soundKey]) {
				AudioEngine.playSound(AudioLibrary[soundKey]);
			}
		} catch (e) {}
		if (typeof Notifications !== 'undefined') {
			Notifications.notify(World, itemKey === 'cured meat' ? '吃了熏肉，恢复了生命。' : '服用了药剂，恢复了生命。');
		}
	}

	function scrollWorldToPlayer() {
		try {
			var wp = document.getElementById('worldPanel');
			var mapEl = document.getElementById('map');
			if (!wp || !mapEl) return;
			var landmarks = mapEl.querySelectorAll('span.landmark');
			var playerEl = null;
			for (var i = 0; i < landmarks.length; i++) {
				var first = landmarks[i].firstChild;
				if (first && first.nodeType === 3 && first.nodeValue && first.nodeValue.trim() === '@') {
					playerEl = landmarks[i];
					break;
				}
			}
			if (playerEl) {
				var wpRect = wp.getBoundingClientRect();
				var pRect = playerEl.getBoundingClientRect();
				var targetScrollTop = wp.scrollTop + (pRect.top - wpRect.top) - (wp.clientHeight * 0.42);
				wp.scrollTop = Math.max(0, targetScrollTop);
			}
		} catch (e) {}
	}

	function ensureWorldPad() {
		return withAdapterDomMutation(function () {
			var pad = document.getElementById('bciWorldPad');
			if (!pad) {
				pad = document.createElement('div');
				pad.id = 'bciWorldPad';
				document.body.appendChild(pad);
			}
			var show = isWorldActive() && !getTopEventEl();
			pad.classList.toggle('visible', show);
			if (!show) return pad;

			var pos = (typeof World !== 'undefined' && World.curPos) ? World.curPos : [30, 30];
			var maxIdx = (typeof World !== 'undefined' && World.RADIUS) ? World.RADIUS * 2 : 60;
			var canN = pos[1] > 0;
			var canE = pos[0] < maxIdx;
			var canS = pos[1] < maxIdx;
			var canW = pos[0] > 0;
			var injured = typeof World !== 'undefined' && World.health < World.getMaxHealth();
			var canMeat = injured && typeof Path !== 'undefined' && Path.outfit && (Path.outfit['cured meat'] || 0) > 0;
			var canMeds = injured && typeof Path !== 'undefined' && Path.outfit && (Path.outfit['medicine'] || 0) > 0;
			var canHypo = injured && typeof Path !== 'undefined' && Path.outfit && (Path.outfit['hypo'] || 0) > 0;

			var sig = [canN ? 1 : 0, canE ? 1 : 0, canS ? 1 : 0, canW ? 1 : 0, canMeat ? 1 : 0, canMeds ? 1 : 0, canHypo ? 1 : 0].join(':');
			if (pad.dataset.sig === sig) return pad;
			pad.dataset.sig = sig;
			pad.innerHTML = '';

			var specs = [];
			if (canN) specs.push({ id: 'bciWorldN', label: '↑ 向北', fn: function () { World.moveNorth(); scrollWorldToPlayer(); } });
			if (canE) specs.push({ id: 'bciWorldE', label: '→ 向东', fn: function () { World.moveEast(); scrollWorldToPlayer(); } });
			if (canS) specs.push({ id: 'bciWorldS', label: '↓ 向南', fn: function () { World.moveSouth(); scrollWorldToPlayer(); } });
			if (canW) specs.push({ id: 'bciWorldW', label: '← 向西', fn: function () { World.moveWest(); scrollWorldToPlayer(); } });
			if (canMeat) specs.push({ id: 'bciWorldMeat', label: '吃熏肉', fn: function () { healInWorld('cured meat', World.meatHeal(), 'EAT_MEAT'); } });
			if (canMeds) specs.push({ id: 'bciWorldMeds', label: '用药剂', fn: function () { healInWorld('medicine', World.medsHeal(), 'USE_MEDS'); } });
			if (canHypo) specs.push({ id: 'bciWorldHypo', label: '用注射剂', fn: function () { healInWorld('hypo', World.hypoHeal(), 'USE_MEDS'); } });

			specs.forEach(function (sp) {
				var b = document.createElement('button');
				b.type = 'button';
				b.id = sp.id;
				b.className = 'bci-world-btn';
				b.textContent = sp.label;
				b.addEventListener('click', function () {
					if (cfg.paused) return;
					sp.fn();
					scheduleRefresh(false);
				});
				pad.appendChild(b);
			});
			return pad;
		});
	}

	function ensureSpaceHud() {
		withAdapterDomMutation(function () {
			var hud = document.getElementById('bciSpaceHud');
			if (!hud) {
				hud = document.createElement('div');
				hud.id = 'bciSpaceHud';
				hud.textContent = '左看：向左躲避  ·  咬牙：悬停稳定  ·  右看：向右躲避';
				document.body.appendChild(hud);
			}
			hud.classList.toggle('visible', isSpaceActive());
		});
	}

	function updateSoundButtonLabel() {
		withAdapterDomMutation(function () {
			var btn = document.getElementById('bciSoundBtn');
			if (!btn || typeof $SM === 'undefined') return;
			var on = Boolean($SM.get('config.soundOn'));
			var nextText = on ? '音效：开' : '音效：关';
			if (btn.textContent !== nextText) {
				btn.textContent = nextText;
			}
		});
	}

	function syncHeaderClearance() {
		var header = document.getElementById('header');
		var slider = document.getElementById('locationSlider');
		if (!header || !slider) return;
		var h = header.offsetHeight;
		if (h > 0) {
			var nextTop = (h + 20) + 'px';
			if (slider.style.top !== nextTop) {
				withAdapterDomMutation(function () {
					slider.style.top = nextTop;
				});
			}
		}
	}

	function collectScanItems() {
		syncHeaderClearance();
		ensureWorldPad();
		ensureSpaceHud();
		updateSoundButtonLabel();

		if (isSpaceActive()) {
			return [];
		}

		var out = [];
		var exitBtn = document.getElementById('bciExitBtn');

		// Priority 0: Expansion outro (#wait-btn) or Score ending (.centerCont .endGameOption)
		var outroBtn = document.querySelector('.outroContainer #wait-btn:not(.disabled)');
		if (outroBtn && isVisibleEl(outroBtn)) {
			out.push({ el: outroBtn, label: cleanButtonText(outroBtn) || '等待', id: 'wait-btn' });
			if (exitBtn && isVisibleEl(exitBtn)) {
				out.push({ el: exitBtn, label: '离开小黑屋', id: 'bciExitBtn' });
			}
			return out;
		}
		var endOptions = document.querySelectorAll('.centerCont .endGameOption');
		if (endOptions.length > 0 && !getTopEventEl()) {
			for (var eo = 0; eo < endOptions.length; eo++) {
				if (!isVisibleEl(endOptions[eo])) continue;
				var eoText = cleanButtonText(endOptions[eo]);
				if (eoText) {
					out.push({ el: endOptions[eo], label: eoText, id: endOptions[eo].id || ('end_' + eo) });
				}
			}
			if (exitBtn && isVisibleEl(exitBtn)) {
				out.push({ el: exitBtn, label: '离开小黑屋', id: 'bciExitBtn' });
			}
			if (out.length > 0) return out;
		}

		// Priority 1: Top-most visible Event modal (dropMenu > loot + story/combat buttons)
		var ev = getTopEventEl();
		if (ev) {
			var dropMenu = ev.querySelector('#dropMenu');
			if (dropMenu && isVisibleEl(dropMenu)) {
				var dropItems = dropMenu.querySelectorAll(':scope > div');
				var dropList = [];
				for (var d = 0; d < dropItems.length; d++) {
					var dEl = dropItems[d];
					if (!isVisibleEl(dEl)) continue;
					var dTxt = cleanButtonText(dEl);
					if (!dTxt) continue;
					var isNothing = dEl.id === 'dropNothing' || d === dropItems.length - 1;
					dropList.push({
						el: dEl,
						label: isNothing ? ('取消：' + dTxt) : ('丢弃 ' + dTxt),
						id: 'drop_' + d + '_' + dTxt
					});
				}
				if (dropList.length > 0) return dropList;
			}

			var evBtns = ev.querySelectorAll('#lootButtons .button:not(.disabled), #buttons .button:not(.disabled)');
			var list = [];
			for (var i = 0; i < evBtns.length; i++) {
				var el = evBtns[i];
				if (!isVisibleEl(el)) continue;
				var lbl = cleanButtonText(el);
				if (!lbl) continue;
				var cost = extractCostSummary(el);
				list.push({ el: el, label: cost ? (lbl + ' ' + cost) : lbl, id: el.id || ('ev_' + i + '_' + lbl) });
			}
			// Cancel-first safety ordering for confirmation dialogs (no/yes, wait/fly)
			var pairs = [['no', 'yes'], ['wait', 'fly']];
			for (var pIdx = 0; pIdx < pairs.length; pIdx++) {
				var cancelId = pairs[pIdx][0];
				var confirmId = pairs[pIdx][1];
				var cPos = -1;
				var kPos = -1;
				for (var j = 0; j < list.length; j++) {
					if (list[j].id === cancelId) cPos = j;
					if (list[j].id === confirmId) kPos = j;
				}
				if (cPos > 0 && kPos >= 0 && cPos > kPos) {
					var cancelItem = list.splice(cPos, 1)[0];
					list.unshift(cancelItem);
				}
			}
			// If no event buttons are enabled (e.g., all cooling down), keep host exit reachable only if not in active combat
			if (list.length === 0 && !ev.querySelector('#fight') && exitBtn && isVisibleEl(exitBtn)) {
				list.push({ el: exitBtn, label: '离开小黑屋', id: 'bciExitBtn' });
			}
			return list;
		}

		// Priority 2: Wasteland World navigation pad + exit
		if (isWorldActive()) {
			var padBtns = document.querySelectorAll('#bciWorldPad .bci-world-btn');
			for (var w = 0; w < padBtns.length; w++) {
				if (isVisibleEl(padBtns[w])) {
					out.push({ el: padBtns[w], label: padBtns[w].textContent.trim(), id: padBtns[w].id });
				}
			}
			if (exitBtn && isVisibleEl(exitBtn)) {
				out.push({ el: exitBtn, label: '离开小黑屋', id: 'bciExitBtn' });
			}
			return out;
		}

		// Priority 3: Standard game module panel (Room / Outside / Path / Ship / Fabricator)
		var panel = (typeof Engine !== 'undefined' && Engine.activeModule && Engine.activeModule.panel)
			? Engine.activeModule.panel[0]
			: document.querySelector('div.location');

		if (panel) {
			// 3a. Primary enabled buttons inside active panel (include compact cost hint so BCI players see costs!)
			var panelBtns = panel.querySelectorAll('.button:not(.disabled)');
			for (var p = 0; p < panelBtns.length; p++) {
				var btn = panelBtns[p];
				if (!isVisibleEl(btn)) continue;
				var text = cleanButtonText(btn);
				if (!text) continue;
				var costHint = extractCostSummary(btn);
				out.push({
					el: btn,
					label: costHint ? (text + ' ' + costHint) : text,
					id: btn.id || ('btn_' + text)
				});
			}

			// 3b. Worker assignment +/- (+10/-10 when >= 5 available) in Outside
			var gatherers = (typeof Outside !== 'undefined' && Outside.getNumGatherers) ? Outside.getNumGatherers() : 0;
			var workerRows = panel.querySelectorAll('.workerRow');
			for (var wr = 0; wr < workerRows.length; wr++) {
				var row = workerRows[wr];
				if (!isVisibleEl(row)) continue;
				var keyName = (row.querySelector('.row_key') || {}).textContent || '';
				keyName = keyName.trim();
				var curCount = parseInt(((row.querySelector('.row_val > span') || {}).textContent || '0'), 10) || 0;
				var upW = row.querySelector('.upBtn:not(.disabled)');
				var dnW = row.querySelector('.dnBtn:not(.disabled)');
				var upManyW = row.querySelector('.upManyBtn:not(.disabled)');
				var dnManyW = row.querySelector('.dnManyBtn:not(.disabled)');
				if (upW && isVisibleEl(upW)) out.push({ el: upW, label: '增加' + keyName, id: (row.id || wr) + '_up' });
				if (upManyW && gatherers >= 5 && isVisibleEl(upManyW)) out.push({ el: upManyW, label: '增加' + keyName + ' +10', id: (row.id || wr) + '_up10' });
				if (dnW && isVisibleEl(dnW)) out.push({ el: dnW, label: '减少' + keyName, id: (row.id || wr) + '_dn' });
				if (dnManyW && curCount >= 5 && isVisibleEl(dnManyW)) out.push({ el: dnManyW, label: '减少' + keyName + ' -10', id: (row.id || wr) + '_dn10' });
			}

			// 3c. Outfitting supply +/- (+10/-10 when >= 5) in Path
			var outfitRows = panel.querySelectorAll('.outfitRow');
			for (var or = 0; or < outfitRows.length; or++) {
				var orow = outfitRows[or];
				if (!isVisibleEl(orow)) continue;
				var itemName = (orow.querySelector('.row_key') || {}).textContent || '';
				itemName = itemName.trim();
				var carried = parseInt(((orow.querySelector('.row_val > span') || {}).textContent || '0'), 10) || 0;
				var avail = parseInt(((orow.querySelector('.tooltip .numAvailable') || {}).textContent || '0'), 10) || 0;
				var upO = orow.querySelector('.upBtn:not(.disabled)');
				var dnO = orow.querySelector('.dnBtn:not(.disabled)');
				var upManyO = orow.querySelector('.upManyBtn:not(.disabled)');
				var dnManyO = orow.querySelector('.dnManyBtn:not(.disabled)');
				if (upO && isVisibleEl(upO)) out.push({ el: upO, label: '携带' + itemName + ' +1', id: (orow.id || or) + '_up' });
				if (upManyO && avail >= 5 && isVisibleEl(upManyO)) out.push({ el: upManyO, label: '携带' + itemName + ' +10', id: (orow.id || or) + '_up10' });
				if (dnO && isVisibleEl(dnO)) out.push({ el: dnO, label: '减少' + itemName + ' -1', id: (orow.id || or) + '_dn' });
				if (dnManyO && carried >= 5 && isVisibleEl(dnManyO)) out.push({ el: dnManyO, label: '减少' + itemName + ' -10', id: (orow.id || or) + '_dn10' });
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

		// 3e. Utility buttons (Sound, Restart [opens safe cancel-first confirm dialog], Exit)
		var soundBtn = document.getElementById('bciSoundBtn');
		if (soundBtn && isVisibleEl(soundBtn)) {
			out.push({ el: soundBtn, label: soundBtn.textContent.trim(), id: 'bciSoundBtn' });
		}
		var restartBtn = document.getElementById('bciRestartBtn');
		if (restartBtn && isVisibleEl(restartBtn)) {
			out.push({ el: restartBtn, label: '重开', id: 'bciRestartBtn' });
		}
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

	function ensureFocusVisible(el) {
		try {
			if (!el || !el.isConnected) return;
			// Fixed utility bar & sticky header tabs are always visible without scrolling #main.
			if (el.closest('#bciTopTools, #header, #bciWorldPad')) return;

			var scroller = el.closest('.eventPanel, #pathScroller, #main, #worldPanel');
			if (!scroller) {
				el.scrollIntoView({ block: 'nearest', inline: 'nearest', behavior: 'smooth' });
				return;
			}

			var sRect = scroller.getBoundingClientRect();
			var eRect = el.getBoundingClientRect();
			var visTop = sRect.top + 14;
			var visBottom = sRect.bottom - 18;

			if (scroller.id === 'main') {
				var header = document.getElementById('header');
				if (header) {
					var hRect = header.getBoundingClientRect();
					if (hRect.bottom > visTop) {
						visTop = hRect.bottom + 18;
					}
				}
			}

			if (eRect.top < visTop) {
				var targetTop = scroller.scrollTop + (eRect.top - visTop);
				// Snap cleanly to top when close so legends (#stores, #buildBtns) also reveal completely.
				if (targetTop < 36) targetTop = 0;
				scroller.scrollTo({ top: Math.max(0, targetTop), behavior: 'smooth' });
			} else if (eRect.bottom > visBottom) {
				var targetDown = scroller.scrollTop + (eRect.bottom - visBottom);
				scroller.scrollTo({ top: Math.max(0, targetDown), behavior: 'smooth' });
			}
		} catch (e) {
			try {
				el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
			} catch (e2) {}
		}
	}

	function renderFocus() {
		withAdapterDomMutation(function () {
			clearVisualFocus();

			if (isSpaceActive()) {
				reportActionHints('向左躲避', '悬停稳定', '向右躲避', 0);
				return;
			}

			var items = state.items;
			if (!items.length) {
				var inEvent = !!getTopEventEl();
				reportActionHints('', inEvent ? '等待冷却…' : '等待中…', '', 0);
				return;
			}

			if (state.index < 0 || state.index >= items.length) {
				state.index = 0;
			}
			var current = items[state.index];
			var el = current.el;
			el.classList.add('bci-focus');

			ensureFocusVisible(el);

			var dir = cfg.manual ? 0 : state.direction;
			if (!cfg.manual && items.length > 1) {
				var badge = document.createElement('span');
				badge.className = 'bci-dir-badge';
				badge.textContent = state.direction < 0 ? '←' : '→';
				el.appendChild(badge);
			}

			if (!cfg.manual && items.length > 1 && (!cfg.paused || cfg.frozen)) {
				var track = document.createElement('div');
				track.className = 'bci-dwell-track' + (state.direction < 0 ? ' rtl' : '');
				var fill = document.createElement('div');
				fill.className = 'bci-dwell-fill';
				track.appendChild(fill);
				el.appendChild(track);
				if (cfg.frozen) {
					fill.style.width = '50%';
				} else {
					void fill.offsetWidth;
					fill.style.transition = 'width ' + cfg.intervalMs + 'ms linear';
					fill.style.width = '100%';
				}
			}

			if (cfg.manual) {
				reportActionHints('上一项', '选「' + current.label + '」', '下一项', 0);
			} else {
				reportActionHints('向左轮转', '选「' + current.label + '」', '向右轮转', dir);
			}
		});
	}

	function stopScanTimer() {
		if (state.timerId !== null) {
			clearTimeout(state.timerId);
			state.timerId = null;
		}
	}

	function scheduleScanTick() {
		stopScanTimer();
		if (cfg.paused || cfg.frozen || cfg.manual || isSpaceActive() || state.items.length <= 1) {
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

	function advanceAutoTick(allowWhenFrozen) {
		if ((cfg.paused && !allowWhenFrozen) || cfg.manual || isSpaceActive()) return;
		var n = state.items.length;
		if (n <= 1) return;
		state.previousIndex = state.index;
		state.advancedAtMs = Date.now();
		state.index = (state.index + state.direction + n) % n;
		renderFocus();
	}

	function currentContextKey() {
		var outroBtn = document.querySelector('.outroContainer #wait-btn:not(.disabled)');
		if (outroBtn && isVisibleEl(outroBtn)) return 'outro';
		var ev = getTopEventEl();
		if (ev) {
			var dropMenu = ev.querySelector('#dropMenu');
			if (dropMenu && isVisibleEl(dropMenu)) return 'event:dropMenu';
			var title = (ev.querySelector('.eventTitle') || {}).textContent || '';
			var scene = (typeof Events !== 'undefined' && Events.activeScene) ? Events.activeScene : '';
			return 'event:' + title + ':' + scene;
		}
		if (document.querySelector('.centerCont .endGameOption')) return 'ending';
		if (isSpaceActive()) return 'space';
		if (isWorldActive()) return 'world';
		var mod = (typeof Engine !== 'undefined' && Engine.activeModule && Engine.activeModule.name) ? Engine.activeModule.name : 'init';
		return 'mod:' + mod;
	}

	function buildItemsSignature(items) {
		var parts = [];
		for (var i = 0; i < items.length; i++) {
			parts.push(items[i].id + '=' + items[i].label);
		}
		return parts.join('\n');
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

		var nextItems = collectScanItems();
		var nextSig = buildItemsSignature(nextItems);

		// Critical: if neither context nor item list changed and not forced, keep running dwell timer untouched!
		if (!forceReset && !contextChanged && nextSig === state.itemsSig && state.items.length === nextItems.length) {
			var sameNodes = true;
			for (var k = 0; k < nextItems.length; k++) {
				if (nextItems[k].el !== state.items[k].el) {
					sameNodes = false;
					break;
				}
			}
			if (sameNodes) return;
		}

		state.items = nextItems;
		state.itemsSig = nextSig;

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
		withAdapterDomMutation(function () {
			el.classList.add('bci-picked');
		});
		setTimeout(function () {
			withAdapterDomMutation(function () {
				el.classList.remove('bci-picked');
			});
		}, 350);

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
		if (typeof Space === 'undefined' || Space.done || cfg.paused) return;
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
		window.__bciAdrPaused = Boolean(paused);
		if (paused) {
			if (state.spaceStopTimerId !== null) {
				clearTimeout(state.spaceStopTimerId);
				state.spaceStopTimerId = null;
			}
			if (typeof Space !== 'undefined') {
				Space.left = false;
				Space.right = false;
				Space.up = false;
				Space.down = false;
			}
		}

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

		// Pause/resume active combat enemy attacks (specials & DOT also check window.__bciAdrPaused)
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
		// 1. Slow enemy attacks by 2.2x for fair BCI 3-key combat, and respect pause
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

		// 2. Fair Space tuning (asteroids already pause natively via window.__bciAdrPaused in space.js)
		if (typeof Space !== 'undefined') {
			Space.BASE_ASTEROID_SPEED = 2700;
			Space.SHIP_SPEED = 5;
		}

		// 3. Hook module & event transitions so scan ring refreshes immediately
		if (typeof Engine !== 'undefined' && Engine.travelTo) {
			var origTravelTo = Engine.travelTo;
			Engine.travelTo = function () {
				var res = origTravelTo.apply(Engine, arguments);
				scheduleRefresh(true);
				return res;
			};
		}
		if (typeof World !== 'undefined' && World.onArrival) {
			var origWorldArrival = World.onArrival;
			World.onArrival = function () {
				var res = origWorldArrival.apply(World, arguments);
				setTimeout(scrollWorldToPlayer, 100);
				scheduleRefresh(true);
				return res;
			};
		}
		if (typeof Events !== 'undefined') {
			['startEvent', 'endEvent', 'loadScene', 'drawDrop'].forEach(function (fnName) {
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

		// 5. Watch genuine game DOM mutations (ignoring our own adapter mutations via state.isUpdatingDom)
		if (typeof MutationObserver !== 'undefined') {
			state.observer = new MutationObserver(function (mutations) {
				if (state.isUpdatingDom) return;
				for (var i = 0; i < mutations.length; i++) {
					var target = mutations[i].target;
					if (target && target.classList && (
						target.classList.contains('bci-dwell-track') ||
						target.classList.contains('bci-dwell-fill') ||
						target.classList.contains('bci-dir-badge') ||
						target.classList.contains('cooldown') ||
						target.classList.contains('asteroid') ||
						target.id === 'notifications' ||
						target.classList.contains('notification')
					)) {
						continue;
					}
					scheduleRefresh(false);
					break;
				}
			});
			state.observer.observe(document.body, {
				childList: true,
				subtree: true,
				attributes: true,
				attributeFilter: ['class', 'style']
			});
		}
	}

	function applyDarkClass(isDark) {
		withAdapterDomMutation(function () {
			if (document.documentElement) {
				document.documentElement.classList.toggle('bci-dark', Boolean(isDark));
			}
			if (document.body) {
				document.body.classList.toggle('bci-dark', Boolean(isDark));
			}
		});
	}

	function syncViewportHeight() {
		var vh = window.innerHeight || (document.documentElement && document.documentElement.clientHeight) || 592;
		if (vh < 200) vh = 592;
		withAdapterDomMutation(function () {
			if (document.documentElement) {
				document.documentElement.style.setProperty('--adr-vh', vh + 'px');
			}
		});
	}

	// Apply initial theme & viewport height immediately to avoid any first-paint flash or 0px vh collapse.
	try {
		if (window.location && window.location.search && window.location.search.indexOf('theme=dark') !== -1) {
			cfg.isDark = true;
			applyDarkClass(true);
		}
		syncViewportHeight();
		window.addEventListener('resize', syncViewportHeight);
	} catch (e) {}

	window.inputDsAdr = {
		configure: function (intervalMs, manual, paused, frozen, graceMs, isDark) {
			var prevPaused = cfg.paused;
			cfg.intervalMs = Math.max(1100, Math.min(3000, Number(intervalMs) || 1500));
			cfg.manual = Boolean(manual);
			cfg.paused = Boolean(paused);
			cfg.frozen = Boolean(frozen);
			cfg.graceMs = Math.max(0, Number(graceMs) || 0);
			cfg.isDark = Boolean(isDark);

			syncViewportHeight();
			applyDarkClass(cfg.isDark);
			if (prevPaused !== cfg.paused) {
				applyPausedState(cfg.paused);
			}
			state.previousIndex = -1;
			state.advancedAtMs = 0;
			if (state.ready) {
				renderFocus();
				scheduleScanTick();
			}
		},
		handleAction: handleAction,
		debugTick: function () {
			advanceAutoTick(true);
		},
		saveAndPause: function () {
			applyPausedState(true);
		}
	};

	// Initialize right after jQuery's ready handler finishes Engine.init()
	function bootAdapter() {
		if (state.ready) return;
		syncViewportHeight();
		applyDarkClass(cfg.isDark);
		installGameHooks();
		state.ready = true;
		refreshItems(true);
		try {
			var h = host();
			if (h && h.onAdapterReady) h.onAdapterReady();
		} catch (e) {}
	}

	if (window.jQuery) {
		window.jQuery(function () {
			setTimeout(bootAdapter, 0);
		});
	} else {
		window.addEventListener('DOMContentLoaded', function () {
			setTimeout(bootAdapter, 0);
		});
	}
})();
