(function () {
  "use strict";

  const Core = window.MahjongCore;
  const ScanCursor = window.MahjongScan.ScanCursor;
  const HUMAN = 0;
  const SEAT_NAMES = ["你 · 东", "右家 · 南", "对家 · 西", "左家 · 北"];
  const CLAIM_PRIORITY = { hu: 4, kong: 3, pong: 3, chow: 2 };
  const AI_ACTION_DELAY_MS = 1500;
  const TILE_AUDIO_FILES = [
    "wan_1.wav", "wan_2.wav", "wan_3.wav", "wan_4.wav", "wan_5.wav", "wan_6.wav", "wan_7.wav", "wan_8.wav", "wan_9.wav",
    "tong_1.wav", "tong_2.wav", "tong_3.wav", "tong_4.wav", "tong_5.wav", "tong_6.wav", "tong_7.wav", "tong_8.wav", "tong_9.wav",
    "tiao_1.wav", "tiao_2.wav", "tiao_3.wav", "tiao_4.wav", "tiao_5.wav", "tiao_6.wav", "tiao_7.wav", "tiao_8.wav", "tiao_9.wav",
    "wind_east.wav", "wind_south.wav", "wind_west.wav", "wind_north.wav",
    "dragon_red.wav", "dragon_green.wav", "dragon_white.wav"
  ];
  const ACTION_AUDIO_FILES = {
    chow: "action_chi.wav",
    pong: "action_peng.wav",
    kong: "action_gang.wav",
    hu: "action_hu.wav",
    zimo: "action_zimo.wav",
    buhua: "action_buhua.wav"
  };

  // 0=玩家，1=右家，2=对家，3=左家。后续只需调整这里即可互换角色声音。
  const SEAT_AUDIO_VOICES = [
    "voice_03_youth_male",
    "voice_01_deep_male",
    "voice_04_dubbed_male",
    "voice_05_entertainment_female"
  ];
  const NARRATOR_AUDIO_VOICE = "voice_02_female_narrator";

  class MahjongAudio {
    constructor() {
      this.enabled = this.loadEnabledSetting();
      this.queue = [];
      this.current = null;
      this.generation = 0;
      this.bgm = new Audio("audio/bgm/mahjong_bgm.mp3");
      this.bgm.loop = true;
      this.bgm.preload = "auto";
      this.bgm.volume = 0.36;
    }

    loadEnabledSetting() {
      try {
        const saved = window.localStorage.getItem("mahjong_sound_enabled");
        if (saved !== null) return saved !== "false";
        return window.localStorage.getItem("mahjong_speech_enabled") !== "false";
      } catch (_error) {
        return true;
      }
    }

    setEnabled(enabled) {
      this.enabled = Boolean(enabled);
      try {
        window.localStorage.setItem("mahjong_sound_enabled", this.enabled ? "true" : "false");
        window.localStorage.setItem("mahjong_speech_enabled", this.enabled ? "true" : "false");
      } catch (_error) {
        // The game still works if a WebView blocks local storage.
      }
      if (this.enabled) {
        this.startBgm(true);
      } else {
        this.stopAll();
        this.stopBgm(true);
      }
    }

    toggle() {
      this.setEnabled(!this.enabled);
      return this.enabled;
    }

    stopAll() {
      this.generation += 1;
      this.queue.length = 0;
      if (this.current) {
        this.current.pause();
        this.current.removeAttribute("src");
        this.current.load();
        this.current = null;
      }
    }

    startBgm(restart) {
      if (!this.enabled) return;
      if (restart) {
        try { this.bgm.currentTime = 0; } catch (_error) { }
      }
      const playResult = this.bgm.play();
      if (playResult && typeof playResult.catch === "function") playResult.catch(() => {});
    }

    stopBgm(reset) {
      this.bgm.pause();
      if (reset) {
        try { this.bgm.currentTime = 0; } catch (_error) { }
      }
    }

    playTile(seat, tileType) {
      const filename = TILE_AUDIO_FILES[tileType];
      if (filename) this.enqueue(SEAT_AUDIO_VOICES[seat], filename);
    }

    playAction(seat, action) {
      const filename = ACTION_AUDIO_FILES[action];
      if (filename) this.enqueue(SEAT_AUDIO_VOICES[seat], filename);
    }

    playSystem(filename) {
      this.enqueue(NARRATOR_AUDIO_VOICE, filename);
    }

    enqueue(voice, filename) {
      if (!this.enabled || !voice || !filename) return;
      this.queue.push("audio/" + voice + "/" + filename);
      this.pump();
    }

    pump() {
      if (!this.enabled || this.current || !this.queue.length) return;
      const generation = this.generation;
      const audio = new Audio(this.queue.shift());
      let finished = false;
      const finish = () => {
        if (finished) return;
        finished = true;
        if (this.current === audio) this.current = null;
        if (generation === this.generation) this.pump();
      };
      this.current = audio;
      audio.preload = "auto";
      audio.volume = 1;
      audio.addEventListener("ended", finish, { once: true });
      audio.addEventListener("error", finish, { once: true });
      const playResult = audio.play();
      if (playResult && typeof playResult.catch === "function") playResult.catch(finish);
    }
  }

  class MahjongGame {
    constructor() {
      this.players = [];
      this.wall = [];
      this.turn = HUMAN;
      this.phase = "idle";
      this.selectedTileId = null;
      this.lastDiscard = null;
      this.pendingClaim = null;
      this.roundToken = 0;
      this.lastEvent = "";
      this.gameOver = false;
      this.currentActions = [];
      this.audio = new MahjongAudio();
      this.scan = {
        cursor: new ScanCursor(),
        intervalMs: 1500,
        timer: null,
        firstDwellUntil: 0,
        settingsReturn: null,
        confirmReturn: null,
        pending: null
      };
      this.cacheElements();
      this.bindEvents();
      this.startRound();
    }

    cacheElements() {
      this.el = {
        wallCount: document.getElementById("wall-count"),
        message: document.getElementById("message"),
        actionBar: document.getElementById("action-bar"),
        gameOver: document.getElementById("game-over"),
        gameOverTitle: document.getElementById("game-over-title"),
        gameOverDetail: document.getElementById("game-over-detail"),
        settings: document.getElementById("settings-panel"),
        confirm: document.getElementById("confirm-panel"),
        confirmTitle: document.getElementById("confirm-title"),
        confirmDetail: document.getElementById("confirm-detail"),
        settingsSound: document.getElementById("settings-sound-button")
      };
      this.updateSoundButton();
    }

    bindEvents() {
      document.getElementById("rules-settings-button").addEventListener("click", () => this.openSettings());
      document.getElementById("settings-return-button").addEventListener("click", () => this.closeSettings());
      document.getElementById("settings-sound-button").addEventListener("click", () => this.toggleSound());
      document.getElementById("settings-restart-button").addEventListener("click", () => {
        this.requestConfirmation({
          kind: "restart",
          title: "确定要重新开始吗？",
          detail: "当前牌局会被清除并重新发牌。",
          execute: () => this.startRound()
        });
      });
      document.getElementById("settings-exit-button").addEventListener("click", () => {
        this.requestConfirmation({
          kind: "exit",
          title: "确定要退出麻将游戏吗？",
          detail: "确定后将返回 APP 主页面。",
          execute: () => this.requestExit()
        });
      });
      document.getElementById("confirm-yes-button").addEventListener("click", () => this.confirmPending());
      document.getElementById("confirm-no-button").addEventListener("click", () => this.cancelConfirmation());
      document.getElementById("play-again-button").addEventListener("click", () => this.startRound());
      document.getElementById("game-exit-button").addEventListener("click", () => {
        this.requestConfirmation({
          kind: "exit",
          title: "确定要退出麻将游戏吗？",
          detail: "确定后将返回 APP 主页面。",
          execute: () => this.requestExit()
        });
      });
      document.getElementById("hand-bottom").addEventListener("click", (event) => {
        const tile = event.target.closest(".human-tile");
        if (tile) this.selectHumanTile(Number(tile.dataset.id));
      });
    }

    requestExit() {
      if (window.InputDsMahjongHost && typeof window.InputDsMahjongHost.requestExit === "function") {
        window.InputDsMahjongHost.requestExit();
      } else if (history.length > 1) {
        history.back();
      }
    }

    startRound() {
      this.roundToken += 1;
      this.audio.stopAll();
      this.audio.startBgm(false);
      this.gameOver = false;
      this.el.gameOver.classList.add("hidden");
      this.el.settings.classList.add("hidden");
      this.el.confirm.classList.add("hidden");
      this.lastEvent = "";
      this.selectedTileId = null;
      this.lastDiscard = null;
      this.pendingClaim = null;
      this.currentActions = [];
      this.scan.settingsReturn = null;
      this.scan.confirmReturn = null;
      this.scan.pending = null;
      this.players = SEAT_NAMES.map(function (name) {
        return { name: name, hand: [], melds: [], discards: [], flowers: [] };
      });
      this.wall = Core.shuffle(Core.createDeck());

      for (let round = 0; round < 13; round += 1) {
        for (let seat = 0; seat < 4; seat += 1) this.players[seat].hand.push(this.wall.shift());
      }
      for (let seat = 0; seat < 4; seat += 1) this.replaceFlowersInHand(seat);
      this.players.forEach(function (player) { Core.sortTiles(player.hand); });
      this.drawFor(HUMAN, false, false, true);
      this.turn = HUMAN;
      this.phase = "humanDiscard";
      this.addMessage("新牌局开始，你是东家，请选择一张牌打出");
      this.audio.playSystem("system_game_start.wav");
      this.audio.playSystem("system_your_turn.wav");
      this.prepareHumanTurn();
      this.render();
      this.syncBciForGamePhase();
    }

    replaceFlowersInHand(seat) {
      const player = this.players[seat];
      let foundFlower = true;
      while (foundFlower) {
        foundFlower = false;
        for (let i = player.hand.length - 1; i >= 0; i -= 1) {
          if (!Core.isFlower(player.hand[i].type)) continue;
          player.flowers.push(player.hand.splice(i, 1)[0]);
          const replacement = this.wall.pop();
          if (replacement) player.hand.push(replacement);
          foundFlower = true;
        }
      }
    }

    drawFor(seat, fromTail, announce, keepAtEnd) {
      const player = this.players[seat];
      let tile = fromTail ? this.wall.pop() : this.wall.shift();
      while (tile && Core.isFlower(tile.type)) {
        player.flowers.push(tile);
        if (announce) {
          this.addMessage(player.name + " 补花 " + Core.tileName(tile.type));
          this.audio.playAction(seat, "buhua");
        }
        tile = this.wall.pop();
      }
      if (!tile) return false;
      player.hand.push(tile);
      if (!keepAtEnd) Core.sortTiles(player.hand);
      return true;
    }

    beginTurn(seat) {
      if (this.gameOver) return;
      this.turn = seat;
      this.phase = "drawing";
      this.selectedTileId = null;
      this.setActions([]);
      this.render();
      this.setScanWaiting("等待摸牌");
      this.schedule(() => {
        if (!this.drawFor(seat, false, true, seat === HUMAN)) {
          this.finishDrawGame();
          return;
        }
        if (seat === HUMAN) {
          this.phase = "humanDiscard";
          this.addMessage("轮到你摸牌，请选择一张牌打出");
          this.audio.playSystem("system_your_turn.wav");
          this.prepareHumanTurn();
          this.render();
          this.syncBciForGamePhase();
        } else {
          this.phase = "aiTurn";
          this.render();
          this.setScanWaiting("等待其他玩家");
          this.schedule(() => this.playAiTurn(seat), AI_ACTION_DELAY_MS);
        }
      }, 350);
    }

    prepareHumanTurn() {
      const player = this.players[HUMAN];
      const actions = [];
      if (Core.isWinningHand(player.hand.map(t => t.type), player.melds.length)) {
        actions.push({
          kind: "hu",
          label: "胡",
          accent: "win",
          confirmTitle: "确定要胡牌吗？",
          confirmDetail: "确认后本局立即结束。",
          run: () => this.finishWin(HUMAN, "自摸", null)
        });
      }
      Core.kongOptions(player.hand.map(t => t.type), player.melds).forEach((option) => {
        actions.push({
          kind: "kong",
          label: (option.kind === "concealed" ? "暗杠 " : "补杠 ") + Core.tileName(option.type),
          accent: "claim",
          confirmTitle: "确定要杠牌吗？",
          confirmDetail: (option.kind === "concealed" ? "暗杠 " : "补杠 ") + Core.tileName(option.type),
          run: () => this.performSelfKong(HUMAN, option)
        });
      });
      this.setActions(actions);
    }

    selectHumanTile(id) {
      if (this.phase !== "humanDiscard" || this.turn !== HUMAN || this.gameOver) return;
      if (this.selectedTileId === id) {
        this.discardSelected();
        return;
      }
      this.selectedTileId = id;
      this.renderHumanHand();
    }

    discardSelected() {
      if (this.phase !== "humanDiscard" || this.turn !== HUMAN || this.selectedTileId === null) return;
      const index = this.players[HUMAN].hand.findIndex(tile => tile.id === this.selectedTileId);
      if (index >= 0) this.discardAt(HUMAN, index);
    }

    discardAt(seat, index) {
      if (this.gameOver) return;
      const player = this.players[seat];
      const tile = player.hand.splice(index, 1)[0];
      if (!tile) return;
      if (seat === HUMAN) Core.sortTiles(player.hand);
      player.discards.push(tile);
      this.lastDiscard = { seat: seat, tile: tile };
      this.selectedTileId = null;
      this.phase = "checkingClaims";
      this.setActions([]);
      this.addMessage(player.name + " 打出 " + Core.tileName(tile.type));
      this.audio.playTile(seat, tile.type);
      this.render();
      this.setScanWaiting("等待其他玩家响应");
      this.schedule(() => this.offerClaims(seat, tile), 420);
    }

    offerClaims(discarder, tile) {
      if (this.gameOver) return;
      const optionsBySeat = {};
      for (let distance = 1; distance <= 3; distance += 1) {
        const seat = (discarder + distance) % 4;
        optionsBySeat[seat] = this.claimOptionsFor(seat, discarder, tile);
      }
      const humanOptions = optionsBySeat[HUMAN] || [];
      if (humanOptions.length > 0) {
        this.phase = "humanClaim";
        this.pendingClaim = { discarder: discarder, tile: tile, optionsBySeat: optionsBySeat };
        const actions = humanOptions.map(option => ({
          kind: option.kind,
          label: this.claimLabel(option),
          accent: option.kind === "hu" ? "win" : "claim",
          confirmTitle: "确定要“" + this.claimLabel(option) + "”吗？",
          confirmDetail: "响应 " + Core.tileName(tile.type),
          run: () => this.resolveClaims(option)
        }));
        actions.push({
          kind: "pass",
          label: "过",
          accent: "pass",
          confirmTitle: "确定要放弃本次操作吗？",
          confirmDetail: "将继续等待下一次摸牌。",
          run: () => this.resolveClaims(null)
        });
        this.setActions(actions);
        this.addMessage("可以响应 " + Core.tileName(tile.type) + "，请选择操作");
        this.render();
        this.syncBciForGamePhase();
      } else {
        this.pendingClaim = { discarder: discarder, tile: tile, optionsBySeat: optionsBySeat };
        this.resolveClaims(null);
      }
    }

    claimOptionsFor(seat, discarder, tile) {
      const player = this.players[seat];
      const types = player.hand.map(t => t.type);
      const options = [];
      if (Core.isWinningHand(types.concat(tile.type), player.melds.length)) options.push({ kind: "hu" });
      const sameCount = types.filter(type => type === tile.type).length;
      if (sameCount >= 3) options.push({ kind: "kong" });
      if (sameCount >= 2) options.push({ kind: "pong" });
      if (seat === (discarder + 1) % 4) {
        Core.chowOptions(types, tile.type).forEach(sequence => options.push({ kind: "chow", sequence: sequence }));
      }
      return options;
    }

    claimLabel(option) {
      if (option.kind === "hu") return "胡";
      if (option.kind === "kong") return "杠";
      if (option.kind === "pong") return "碰";
      return "吃 " + option.sequence.map(Core.tileName).join("·");
    }

    resolveClaims(humanChoice) {
      const pending = this.pendingClaim;
      if (!pending || this.gameOver) return;
      this.pendingClaim = null;
      const candidates = [];
      if (humanChoice) candidates.push({ seat: HUMAN, option: humanChoice });
      for (let seat = 1; seat < 4; seat += 1) {
        const choice = this.chooseAiClaim(seat, pending.optionsBySeat[seat] || [], pending.tile.type);
        if (choice) candidates.push({ seat: seat, option: choice });
      }
      candidates.sort((a, b) => {
        const priority = CLAIM_PRIORITY[b.option.kind] - CLAIM_PRIORITY[a.option.kind];
        if (priority !== 0) return priority;
        const aDistance = (a.seat - pending.discarder + 4) % 4;
        const bDistance = (b.seat - pending.discarder + 4) % 4;
        return aDistance - bDistance;
      });
      this.setActions([]);
      if (candidates.length === 0) {
        this.beginTurn((pending.discarder + 1) % 4);
      } else {
        const winner = candidates[0];
        if (humanChoice && winner.seat !== HUMAN) {
          this.addMessage("其他玩家的操作优先于你的选择");
        }
        this.applyClaim(winner.seat, winner.option, pending.discarder, pending.tile);
      }
    }

    chooseAiClaim(seat, options, incomingType) {
      const hu = options.find(option => option.kind === "hu");
      if (hu) return hu;
      const kong = options.find(option => option.kind === "kong");
      if (kong) return kong;

      const player = this.players[seat];
      const before = Core.structureScore(player.hand.map(t => t.type), player.melds.length);
      const candidates = options.filter(option => option.kind === "pong" || option.kind === "chow").map(option => {
        const remaining = player.hand.map(t => t.type);
        if (option.kind === "pong") {
          this.removeTypeValues(remaining, incomingType, 2);
        } else {
          const needed = option.sequence.slice();
          needed.splice(needed.indexOf(incomingType), 1);
          needed.forEach(type => this.removeTypeValues(remaining, type, 1));
        }
        return { option: option, score: Core.structureScore(remaining, player.melds.length + 1) };
      }).sort((a, b) => b.score - a.score);
      if (!candidates.length) return null;
      const best = candidates[0];
      if (best.option.kind === "pong" && incomingType >= 27) return best.option;
      return best.score >= before + 2 ? best.option : null;
    }

    removeTypeValues(values, type, amount) {
      for (let count = 0; count < amount; count += 1) {
        const index = values.indexOf(type);
        if (index >= 0) values.splice(index, 1);
      }
    }

    takeTileOfType(seat, type) {
      const hand = this.players[seat].hand;
      const index = hand.findIndex(tile => tile.type === type);
      return index >= 0 ? hand.splice(index, 1)[0] : null;
    }

    applyClaim(seat, option, discarder, tile) {
      const discarded = this.players[discarder].discards;
      if (discarded.length && discarded[discarded.length - 1].id === tile.id) discarded.pop();
      if (option.kind === "hu") {
        this.players[seat].hand.push(tile);
        Core.sortTiles(this.players[seat].hand);
        this.finishWin(seat, "荣胡 " + this.players[discarder].name, tile);
        return;
      }

      const player = this.players[seat];
      if (option.kind === "pong") {
        this.takeTileOfType(seat, tile.type);
        this.takeTileOfType(seat, tile.type);
        player.melds.push({ kind: "pong", tiles: [tile.type, tile.type, tile.type], from: discarder });
        this.addMessage(player.name + " 碰 " + Core.tileName(tile.type));
        this.audio.playAction(seat, "pong");
      } else if (option.kind === "kong") {
        this.takeTileOfType(seat, tile.type);
        this.takeTileOfType(seat, tile.type);
        this.takeTileOfType(seat, tile.type);
        player.melds.push({ kind: "kong", tiles: [tile.type, tile.type, tile.type, tile.type], from: discarder });
        this.addMessage(player.name + " 明杠 " + Core.tileName(tile.type));
        this.audio.playAction(seat, "kong");
      } else {
        const needed = option.sequence.slice();
        needed.splice(needed.indexOf(tile.type), 1);
        needed.forEach(type => this.takeTileOfType(seat, type));
        player.melds.push({ kind: "chow", tiles: option.sequence.slice(), from: discarder });
        this.addMessage(player.name + " 吃 " + option.sequence.map(Core.tileName).join("、"));
        this.audio.playAction(seat, "chow");
      }

      Core.sortTiles(player.hand);
      this.turn = seat;
      this.selectedTileId = null;
      if (option.kind === "kong") {
        if (!this.drawFor(seat, true, true, seat === HUMAN)) {
          this.finishDrawGame();
          return;
        }
      }
      if (seat === HUMAN) {
        this.phase = "humanDiscard";
        this.prepareHumanTurn();
        this.render();
        this.syncBciForGamePhase();
      } else {
        this.phase = "aiTurn";
        this.render();
        this.setScanWaiting("等待其他玩家");
        this.schedule(() => this.playAiTurn(seat), AI_ACTION_DELAY_MS);
      }
    }

    performSelfKong(seat, option) {
      if (this.gameOver || this.turn !== seat) return;
      const player = this.players[seat];
      if (option.kind === "concealed") {
        for (let i = 0; i < 4; i += 1) this.takeTileOfType(seat, option.type);
        player.melds.push({ kind: "concealedKong", tiles: [option.type, option.type, option.type, option.type], from: seat });
        this.addMessage(player.name + " 暗杠 " + Core.tileName(option.type));
      } else {
        this.takeTileOfType(seat, option.type);
        const meld = player.melds[option.meldIndex];
        meld.kind = "kong";
        meld.tiles.push(option.type);
        this.addMessage(player.name + " 补杠 " + Core.tileName(option.type));
      }
      this.audio.playAction(seat, "kong");
      if (!this.drawFor(seat, true, true, seat === HUMAN)) {
        this.finishDrawGame();
        return;
      }
      if (seat !== HUMAN) Core.sortTiles(player.hand);
      if (seat === HUMAN) {
        this.phase = "humanDiscard";
        this.prepareHumanTurn();
        this.render();
        this.syncBciForGamePhase();
      } else {
        this.phase = "aiTurn";
        this.render();
        this.setScanWaiting("等待其他玩家");
        this.schedule(() => this.playAiTurn(seat), AI_ACTION_DELAY_MS);
      }
    }

    playAiTurn(seat) {
      if (this.gameOver || this.turn !== seat) return;
      const player = this.players[seat];
      if (Core.isWinningHand(player.hand.map(t => t.type), player.melds.length)) {
        this.finishWin(seat, "自摸", null);
        return;
      }
      const kong = Core.kongOptions(player.hand.map(t => t.type), player.melds)[0];
      if (kong && this.wall.length > 0) {
        this.performSelfKong(seat, kong);
        return;
      }
      const discardIndex = Core.chooseDiscardIndex(
        player.hand,
        player.melds.length,
        this.visibleCounts()
      );
      this.discardAt(seat, discardIndex);
    }

    visibleCounts() {
      const counts = new Array(Core.FLOWER_START).fill(0);
      this.players.forEach(player => {
        player.discards.forEach(tile => { if (tile.type < counts.length) counts[tile.type] += 1; });
        player.melds.forEach(meld => meld.tiles.forEach(type => { if (type < counts.length) counts[type] += 1; }));
      });
      return counts;
    }

    finishWin(seat, method, winningTile) {
      this.gameOver = true;
      this.phase = "gameOver";
      this.setActions([]);
      this.addMessage(this.players[seat].name + " 胡牌（" + method + "）");
      this.audio.playAction(seat, method === "自摸" ? "zimo" : "hu");
      this.audio.playSystem(seat === HUMAN ? "result_player_win.wav" : "result_player_lose.wav");
      this.render();
      this.el.gameOverTitle.textContent = seat === HUMAN ? "恭喜，你胡牌了！" : this.players[seat].name + "胡牌";
      this.el.gameOverDetail.textContent = method + (winningTile ? " · " + Core.tileName(winningTile.type) : "");
      this.el.gameOver.classList.remove("hidden");
      this.startGameOverScan();
    }

    finishDrawGame() {
      this.gameOver = true;
      this.phase = "gameOver";
      this.setActions([]);
      this.addMessage("牌墙已摸完，本局流局");
      this.audio.playSystem("result_draw.wav");
      this.render();
      this.el.gameOverTitle.textContent = "本局流局";
      this.el.gameOverDetail.textContent = "牌墙已经摸完，四家均未胡牌";
      this.el.gameOver.classList.remove("hidden");
      this.startGameOverScan();
    }

    setActions(actions) {
      this.currentActions = actions.slice();
      this.el.actionBar.innerHTML = "";
      actions.forEach((action, index) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "text-button action-button " + (action.accent || "");
        button.textContent = action.label;
        button.dataset.actionIndex = String(index);
        button.addEventListener("click", action.run);
        this.el.actionBar.appendChild(button);
      });
    }

    addMessage(message) {
      this.lastEvent = String(message || "");
    }

    schedule(callback, delay) {
      const token = this.roundToken;
      window.setTimeout(() => {
        if (token === this.roundToken) callback();
      }, delay);
    }

    gameSignature() {
      return [
        this.phase,
        this.turn,
        this.wall.length,
        this.players.map(player => player.hand.map(tile => tile.id).join(",")).join("|")
      ].join(":");
    }

    setScanInterval(value) {
      const parsed = Number(value);
      if (!Number.isFinite(parsed)) return;
      const firstDwellActive = this.scan.firstDwellUntil > Date.now();
      this.scan.intervalMs = Math.max(1100, Math.min(3000, Math.round(parsed)));
      this.restartScanTimer(firstDwellActive ? this.scan.intervalMs * 2 : undefined);
    }

    setScanPhase(phase, candidates, direction, startIndex, initialDelayMs) {
      this.scan.cursor.setPhase(phase, candidates, direction, startIndex);
      this.restartScanTimer(initialDelayMs);
      this.renderBciFocus();
    }

    setScanWaiting() {
      this.setScanPhase("waiting", [], 1);
    }

    restartScanTimer(initialDelayMs) {
      if (this.scan.timer) {
        window.clearTimeout(this.scan.timer);
        window.clearInterval(this.scan.timer);
      }
      this.scan.timer = null;
      if (!this.scan.cursor.candidates.length) return;
      const advance = () => {
        this.scan.cursor.advance();
        this.renderBciFocus();
      };
      const firstDelay = Number(initialDelayMs);
      if (Number.isFinite(firstDelay) && firstDelay > this.scan.intervalMs) {
        this.scan.firstDwellUntil = Date.now() + firstDelay;
        this.scan.timer = window.setTimeout(() => {
          this.scan.firstDwellUntil = 0;
          advance();
          this.scan.timer = window.setInterval(advance, this.scan.intervalMs);
        }, firstDelay);
      } else {
        this.scan.firstDwellUntil = 0;
        this.scan.timer = window.setInterval(advance, this.scan.intervalMs);
      }
    }

    removeBciUtilityButtons() {
      this.el.actionBar.querySelectorAll(".bci-utility").forEach(button => button.remove());
    }

    appendBciUtilityButton(id, label) {
      let button = document.getElementById(id);
      if (button) return button;
      button = document.createElement("button");
      button.id = id;
      button.type = "button";
      button.className = "text-button action-button bci-utility";
      button.textContent = label;
      this.el.actionBar.appendChild(button);
      return button;
    }

    syncBciForGamePhase() {
      if (!this.el.confirm.classList.contains("hidden") || !this.el.settings.classList.contains("hidden")) return;
      this.removeBciUtilityButtons();
      if (this.gameOver) {
        this.startGameOverScan();
        return;
      }
      if (this.phase === "humanClaim") {
        const candidates = this.currentActions.map((action, index) => ({
          kind: "action",
          actionIndex: index,
          label: action.label
        }));
        candidates.push({ kind: "settings", label: "规则/设置" });
        this.setScanPhase("actions", candidates, 1, 0);
        return;
      }
      if (this.phase === "humanDiscard" && this.turn === HUMAN) {
        if (this.currentActions.length > 0) {
          const candidates = this.currentActions.map((action, index) => ({
            kind: "action",
            actionIndex: index,
            label: action.label
          }));
          this.appendBciUtilityButton("bci-choose-discard", "选择要打出的牌");
          candidates.push({ kind: "choose_discard", label: "选择要打出的牌" });
          candidates.push({ kind: "settings", label: "规则/设置" });
          this.setScanPhase("actions", candidates, 1, 0);
        } else {
          this.startDiscardScan();
        }
        return;
      }
      this.setScanWaiting("等待其他玩家");
    }

    startDiscardScan() {
      this.removeBciUtilityButtons();
      const candidates = [{ kind: "settings", label: "规则/设置" }];
      if (this.currentActions.length > 0) {
        this.appendBciUtilityButton("bci-back-actions", "返回胡/杠选择");
        candidates.push({ kind: "back_actions", label: "返回胡/杠选择" });
      }
      this.players[HUMAN].hand.forEach(tile => {
        candidates.push({ kind: "tile", tileId: tile.id, tileType: tile.type, label: Core.tileName(tile.type) });
      });
      // The newly drawn tile is the first highlighted card. Keep it visible for
      // two configured scan periods before normal scanning resumes, so this
      // accessibility pause follows the APP-wide scan-speed setting as well.
      this.setScanPhase(
        "discard",
        candidates,
        -1,
        candidates.length - 1,
        this.scan.intervalMs * 2
      );
    }

    startGameOverScan() {
      this.removeBciUtilityButtons();
      this.setScanPhase("gameOver", [
        { kind: "again", label: "再来一局" },
        { kind: "result_exit", label: "退出游戏" }
      ], 1, 0);
    }

    updateSoundButton() {
      if (!this.el.settingsSound) return;
      this.el.settingsSound.textContent = "游戏声音：" + (this.audio.enabled ? "开" : "关");
      this.el.settingsSound.setAttribute("aria-pressed", this.audio.enabled ? "true" : "false");
    }

    settingsCandidates() {
      return [
        { kind: "settings_return", label: "返回牌局" },
        { kind: "settings_sound", label: "游戏声音：" + (this.audio.enabled ? "开" : "关") },
        { kind: "settings_restart", label: "重新开始" },
        { kind: "settings_exit", label: "退出麻将" }
      ];
    }

    toggleSound() {
      this.audio.toggle();
      this.updateSoundButton();
      if (!this.el.settings.classList.contains("hidden")) {
        this.setScanPhase("settings", this.settingsCandidates(), 1, 1);
      }
    }

    openSettings() {
      if (!this.el.confirm.classList.contains("hidden")) return;
      if (this.el.settings.classList.contains("hidden")) {
        this.scan.settingsReturn = {
          cursor: this.scan.cursor.snapshot(),
          signature: this.gameSignature()
        };
      }
      this.el.settings.classList.remove("hidden");
      this.setScanPhase("settings", this.settingsCandidates(), 1, 0);
    }

    closeSettings() {
      this.el.settings.classList.add("hidden");
      const saved = this.scan.settingsReturn;
      this.scan.settingsReturn = null;
      if (saved && saved.signature === this.gameSignature()) {
        this.scan.cursor.restore(saved.cursor);
        this.restartScanTimer();
        this.renderBciFocus();
      } else {
        this.syncBciForGamePhase();
      }
    }

    requestConfirmation(request) {
      if (!request || !request.execute) return;
      this.scan.pending = request;
      this.scan.confirmReturn = this.scan.cursor.snapshot();
      this.el.confirmTitle.textContent = request.title || "确定要执行吗？";
      this.el.confirmDetail.textContent = request.detail || "咬牙选择当前高亮项。";
      this.el.confirm.classList.remove("hidden");
      this.setScanPhase("confirm", [
        { kind: "confirm_yes", label: "确定" },
        { kind: "confirm_no", label: "返回选择" }
      ], 1, 0);
    }

    cancelConfirmation() {
      this.el.confirm.classList.add("hidden");
      const pending = this.scan.pending;
      this.scan.pending = null;
      if (pending && pending.kind === "discard") {
        this.selectedTileId = null;
        this.renderHumanHand();
      }
      const saved = this.scan.confirmReturn;
      this.scan.confirmReturn = null;
      if (saved) {
        this.scan.cursor.restore(saved);
        this.restartScanTimer();
        this.renderBciFocus();
      } else {
        this.syncBciForGamePhase();
      }
    }

    confirmPending() {
      const request = this.scan.pending;
      this.scan.pending = null;
      this.scan.confirmReturn = null;
      this.el.confirm.classList.add("hidden");
      this.setScanWaiting("正在执行操作");
      if (request) request.execute();
    }

    requestBciDiscard(candidate) {
      const tile = this.players[HUMAN].hand.find(item => item.id === candidate.tileId);
      if (!tile) {
        this.syncBciForGamePhase();
        return;
      }
      this.selectedTileId = tile.id;
      this.renderHumanHand();
      this.requestConfirmation({
        kind: "discard",
        title: "确定要打出“" + Core.tileName(tile.type) + "”吗？",
        detail: "确定后将进入其他玩家的响应阶段。",
        execute: () => {
          const index = this.players[HUMAN].hand.findIndex(item => item.id === tile.id);
          if (index >= 0) this.discardAt(HUMAN, index);
        }
      });
    }

    requestBciAction(index) {
      const action = this.currentActions[index];
      if (!action) {
        this.syncBciForGamePhase();
        return;
      }
      this.requestConfirmation({
        kind: action.kind || "action",
        title: action.confirmTitle || ("确定要“" + action.label + "”吗？"),
        detail: action.confirmDetail || "确定后执行当前操作。",
        execute: action.run
      });
    }

    activateBciCandidate(candidate) {
      if (!candidate) return;
      if (candidate.kind === "tile") this.requestBciDiscard(candidate);
      else if (candidate.kind === "action") this.requestBciAction(candidate.actionIndex);
      else if (candidate.kind === "choose_discard") this.startDiscardScan();
      else if (candidate.kind === "back_actions") this.syncBciForGamePhase();
      else if (candidate.kind === "settings") this.openSettings();
      else if (candidate.kind === "settings_return") this.closeSettings();
      else if (candidate.kind === "settings_sound") this.toggleSound();
      else if (candidate.kind === "settings_restart") {
        this.requestConfirmation({
          kind: "restart",
          title: "确定要重新开始吗？",
          detail: "当前牌局会被清除并重新发牌。",
          execute: () => this.startRound()
        });
      } else if (candidate.kind === "settings_exit" || candidate.kind === "result_exit") {
        this.requestConfirmation({
          kind: "exit",
          title: "确定要退出麻将游戏吗？",
          detail: "确定后将返回 APP 主页面。",
          execute: () => this.requestExit()
        });
      } else if (candidate.kind === "again") this.startRound();
      else if (candidate.kind === "confirm_yes") this.confirmPending();
      else if (candidate.kind === "confirm_no") this.cancelConfirmation();
    }

    handleBciAction(action) {
      if (action === "look_left" || action === "look_right") {
        this.scan.cursor.setDirectionFromAction(action);
        this.restartScanTimer();
        this.renderBciFocus();
        return;
      }
      if (action === "bite") this.activateBciCandidate(this.scan.cursor.current());
    }

    renderBciFocus() {
      document.querySelectorAll(".bci-focus").forEach(element => element.classList.remove("bci-focus"));
      const cursor = this.scan.cursor;
      const current = cursor.current();
      this.updateCenterPrompt(current);
      if (!current) return;

      let target = null;
      if (current.kind === "tile") target = document.querySelector('.human-tile[data-id="' + current.tileId + '"]');
      else if (current.kind === "action") target = document.querySelector('[data-action-index="' + current.actionIndex + '"]');
      else if (current.kind === "choose_discard") target = document.getElementById("bci-choose-discard");
      else if (current.kind === "back_actions") target = document.getElementById("bci-back-actions");
      else if (current.kind === "settings") target = document.getElementById("rules-settings-button");
      else if (current.kind === "settings_return") target = document.getElementById("settings-return-button");
      else if (current.kind === "settings_sound") target = document.getElementById("settings-sound-button");
      else if (current.kind === "settings_restart") target = document.getElementById("settings-restart-button");
      else if (current.kind === "settings_exit") target = document.getElementById("settings-exit-button");
      else if (current.kind === "confirm_yes") target = document.getElementById("confirm-yes-button");
      else if (current.kind === "confirm_no") target = document.getElementById("confirm-no-button");
      else if (current.kind === "again") target = document.getElementById("play-again-button");
      else if (current.kind === "result_exit") target = document.getElementById("game-exit-button");
      if (target) {
        target.classList.add("bci-focus");
        target.scrollIntoView({ block: "nearest", inline: "center" });
      }
    }

    updateCenterPrompt(current) {
      let prompt;
      if (this.gameOver) {
        prompt = "本局已经结束";
      } else if (current && current.kind === "tile") {
        prompt = "咬牙打出：" + current.label;
      } else if (current && current.label) {
        prompt = (current.kind === "confirm_yes" || current.kind === "confirm_no")
          ? "咬牙确认：" + current.label
          : "咬牙选择：" + current.label;
      } else if (this.phase === "checkingClaims") {
        prompt = "其他玩家正在思考…";
      } else if (this.turn !== HUMAN) {
        prompt = this.players[this.turn].name.split(" · ")[0] + "正在思考…";
      } else if (this.phase === "drawing") {
        prompt = "正在摸牌…";
      } else {
        prompt = "请选择要打出的牌";
      }
      this.el.message.textContent = prompt;
    }

    render() {
      this.el.wallCount.textContent = String(this.wall.length);
      ["bottom", "right", "top", "left"].forEach((position, seat) => this.renderSeat(seat, position));
      this.renderHumanHand();
      this.renderBciFocus();
    }

    renderSeat(seat, position) {
      const player = this.players[seat];
      const name = document.getElementById("name-" + position);
      const concealed = document.getElementById("concealed-" + position);
      const melds = document.getElementById("melds-" + position);
      const discards = document.getElementById("discards-" + position);
      name.textContent = player.name + (this.turn === seat && !this.gameOver ? " · 行动中" : "");
      name.classList.toggle("active", this.turn === seat && !this.gameOver);

      if (seat !== HUMAN) {
        this.renderConcealedHand(concealed, player.hand, this.gameOver);
      }
      const meldHtml = player.melds.map(meld => {
        const tiles = meld.tiles.map(type => this.tileHtml(type, "mini face-up")).join("");
        return "<span class=\"meld-group\">" + tiles + "</span>";
      }).join("");
      const flowerHtml = player.flowers.length
        ? "<span class=\"flower-group\">" + player.flowers.map(tile => this.tileHtml(tile.type, "mini face-up flower")).join("") + "</span>"
        : "";
      const nextMeldHtml = meldHtml + flowerHtml;
      if (melds.dataset.renderedHtml !== nextMeldHtml) {
        melds.innerHTML = nextMeldHtml;
        melds.dataset.renderedHtml = nextMeldHtml;
      }
      this.renderDiscards(discards, player.discards);
    }

    renderConcealedHand(container, hand, reveal) {
      const liveIds = new Set(hand.map(tile => tile.id));
      Array.from(container.children).forEach(element => {
        if (!liveIds.has(Number(element.dataset.tileId))) element.remove();
      });

      const existingById = new Map();
      Array.from(container.children).forEach(element => {
        existingById.set(Number(element.dataset.tileId), element);
      });

      hand.forEach((tile, index) => {
        const tileName = Core.tileName(tile.type);
        let element = existingById.get(tile.id);
        if (!element) {
          element = document.createElement("span");
          element.className = "tile small tile-back";
          element.dataset.tileId = String(tile.id);
          element.setAttribute("aria-label", "未公开的牌");
        }

        element.classList.toggle("tile-back", !reveal);
        element.classList.toggle("face-up", reveal);
        if (reveal) {
          element.title = tileName;
          element.setAttribute("aria-label", tileName);
          let image = element.querySelector("img");
          if (!image) {
            image = document.createElement("img");
            element.appendChild(image);
          }
          const asset = Core.tileAsset(tile.type);
          if (image.getAttribute("src") !== asset) image.src = asset;
          image.alt = tileName;
        } else {
          element.removeAttribute("title");
          element.setAttribute("aria-label", "未公开的牌");
          if (element.firstElementChild) element.textContent = "";
        }

        const nodeAtPosition = container.children[index] || null;
        if (nodeAtPosition !== element) container.insertBefore(element, nodeAtPosition);
      });
    }

    renderDiscards(container, tiles) {
      const liveIds = new Set(tiles.map(tile => tile.id));
      Array.from(container.children).forEach(element => {
        if (!liveIds.has(Number(element.dataset.tileId))) element.remove();
      });

      const existingById = new Map();
      Array.from(container.children).forEach(element => {
        existingById.set(Number(element.dataset.tileId), element);
      });

      tiles.forEach((tile, index) => {
        const tileName = Core.tileName(tile.type);
        let element = existingById.get(tile.id);
        if (!element) {
          element = document.createElement("span");
          element.className = "tile mini face-up";
          element.dataset.tileId = String(tile.id);
          const image = document.createElement("img");
          image.src = Core.tileAsset(tile.type);
          image.alt = tileName;
          element.appendChild(image);
        }

        element.title = tileName;
        element.setAttribute("aria-label", tileName);
        element.classList.toggle(
          "last-discard",
          Boolean(this.lastDiscard && this.lastDiscard.tile.id === tile.id)
        );

        const nodeAtPosition = container.children[index] || null;
        if (nodeAtPosition !== element) container.insertBefore(element, nodeAtPosition);
      });
    }

    renderHumanHand() {
      const container = document.getElementById("hand-bottom");
      const player = this.players[HUMAN];
      const liveIds = new Set(player.hand.map(tile => tile.id));

      // Keep existing tile/image nodes alive. Rebuilding the whole hand with
      // innerHTML on every table update makes SVG tiles flash in Android WebView,
      // even when only the selection or an AI player's discard changed.
      Array.from(container.children).forEach(element => {
        if (!liveIds.has(Number(element.dataset.id))) element.remove();
      });

      const existingById = new Map();
      Array.from(container.children).forEach(element => {
        existingById.set(Number(element.dataset.id), element);
      });

      player.hand.forEach((tile, index) => {
        const tileName = Core.tileName(tile.type);
        let button = existingById.get(tile.id);
        if (!button) {
          button = document.createElement("button");
          button.type = "button";
          button.className = "tile human-tile face-up";
          button.dataset.id = String(tile.id);

          const image = document.createElement("img");
          image.src = Core.tileAsset(tile.type);
          image.alt = tileName;
          button.appendChild(image);
        }

        button.setAttribute("aria-label", tileName);
        button.classList.toggle("selected", tile.id === this.selectedTileId);

        const nodeAtPosition = container.children[index] || null;
        if (nodeAtPosition !== button) container.insertBefore(button, nodeAtPosition);
      });
    }

    tileHtml(type, classes) {
      return "<span class=\"tile " + classes + "\" title=\"" + Core.tileName(type) + "\">" +
        "<img src=\"" + Core.tileAsset(type) + "\" alt=\"" + Core.tileName(type) + "\"></span>";
    }

  }

  window.addEventListener("DOMContentLoaded", function () {
    window.mahjongGame = new MahjongGame();
    window.inputDsMahjong = {
      handleAction: function (action) { window.mahjongGame.handleBciAction(action); },
      setScanInterval: function (value) { window.mahjongGame.setScanInterval(value); }
    };
  });
})();
