import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { test } from 'node:test';

const require = createRequire(import.meta.url);
const assets = '../../main/assets/';
const chessSource = readFileSync(new URL(assets + 'chinese_chess/scan.js', import.meta.url));
const chess = await import('data:text/javascript;base64,' + chessSource.toString('base64'));
const mahjong = require(assets + 'mahjong/js/scan.js');
const ddz = require(assets + 'doudizhu/js/bci.js');
const options = [{ kind: 'cancel' }, { kind: 'confirm' }];

for (const [game, Cursor, enter] of [
  ['象棋', chess.ScanCursor, c => c.setPhase('confirm', options, 'cancel')],
  ['麻将', mahjong.ScanCursor, c => c.setPhase('confirm', options, 1, 0)],
  ['斗地主', ddz.DdzScanCursor, c => c.setPhase('confirm', options)],
  ['斗地主出牌', ddz.DdzScanCursor, c => c.setPhase('confirm_inline', options)],
]) {
  test(game + '确认扫描', () => {
    const c = new Cursor();
    enter(c);
    assert.equal(c.current(), options[0]);
    assert.equal(c.confirmationDelay(1500), 2250);
    for (let tick = 1; tick <= 6; tick++) {
      for (const look of ['look_left', 'look_right']) {
        const before = c.current();
        c.setDirectionFromAction(look);
        assert.equal(c.chosen(), before);
      }
      c.advance();
      assert.equal(c.current(), options[tick % 2]);
      assert.equal(c.chosen(), options[(tick - 1) % 2]);
      assert.equal(c.confirmationDelay(1500), 1500);
      assert.equal(c.confirmationExpired, tick === 6);
    }
    enter(c);
    assert.equal(c.confirmationExpired, false);
    c.manualConfirm = true;
    for (let i = 0; i < 8; i++) c.advance();
    assert.equal(c.chosen(), options[0]);
    assert.equal(c.confirmationExpired, false);
    c.setDirectionFromAction('look_right');
    assert.equal(c.chosen(), options[1]);
    c.setDirectionFromAction('look_left');
    assert.equal(c.chosen(), options[0]);
  });
}

// Exercise the page entry points and their actual timeout callbacks without a WebView.
test('三套棋牌在第六次计时回调取消并返回原上下文', async t => {
  const { runInNewContext } = await import('node:vm');
  let next;
  const messages = [];
  const host = { notifyFeedback: msg => messages.push(msg) };
  const element = { classList: { add() {}, remove() {} }, style: { setProperty() {} } };
  const document = {
    documentElement: element, getElementById: () => element,
    querySelector: () => element, addEventListener() {},
  };
  const browser = {
    document, addEventListener() {}, clearInterval() {}, clearTimeout() {},
    InputDsChessHost: host, InputDsMahjongHost: host, InputDsDoudizhuHost: host,
    MahjongScan: mahjong, MahjongCore: {},
  };
  t.mock.method(globalThis, 'setTimeout', (callback, delay) => { next = { callback, delay }; return 1; });
  globalThis.window = browser;
  globalThis.document = document;
  t.after(() => { delete globalThis.window; delete globalThis.document; });

  function runRounds(cursor, cancelKind, hold) {
    assert.equal(cursor.current().kind, cancelKind);
    for (let i = 0; i < 6; i++) {
      assert.equal(messages.length, 0);
      const before = cursor.current();
      hold(i % 2 ? 'look_left' : 'look_right');
      assert.equal(cursor.current(), before);
      assert.equal(next.delay, i === 0 ? 2250 : 1500);
      next.callback();
    }
    assert.match(messages.shift(), /^三轮都没有选择/);
    assert.equal(cursor.confirming, false);
  }
  const { GameController } = await import(assets + 'chinese_chess/main.js');
  const board = Object.assign(Object.create(GameController.prototype), {
    chess: { currentPlayer: 'red' }, scanCursor: new chess.ScanCursor(), scanIntervalMs: 1500,
    renderScanFocus() {}, startMenuScan() { this.scanCursor.setPhase('menu', []); },
  });
  board.startConfirmScan('exit');
  runRounds(board.scanCursor, 'cancel', action => board.handleBciAction(action));
  assert.equal(board.pendingConfirmedAction, null);
  assert.equal(board.scanCursor.phase, 'menu');

  const MahjongGame = require(assets + 'mahjong/js/game.js');
  const table = Object.assign(Object.create(MahjongGame.prototype), {
    scan: { cursor: new mahjong.ScanCursor(), intervalMs: 1500 },
    el: { confirmTitle: {}, confirmDetail: {}, confirm: element }, renderBciFocus() {},
  });
  table.requestConfirmation({ kind: 'exit', execute() { assert.fail('unexpected exit'); } });
  runRounds(table.scan.cursor, 'confirm_no', action => table.handleBciAction(action));
  assert.equal(table.scan.pending, null);
  assert.equal(table.scan.cursor.phase, 'waiting');

  const context = {
    document, window: browser, DdzScanCursor: ddz.DdzScanCursor, setTimeout,
    clearInterval() {}, setInterval() { return 2; },
  };
  const script = readFileSync(new URL(assets + 'doudizhu/js/ui.js', import.meta.url), 'utf8');
  const cards = runInNewContext(script + `
    bciRender = () => {};
    BciScan.confirmationEnabled = false;
    bciRequest({kind:'exit'});
    ({scan:BciScan, handle:handleDoudizhuBciAction});
  `, context);
  runRounds(cards.scan.cursor, 'cancel', cards.handle);
  assert.equal(cards.scan.pendingAction, null);
  assert.equal(cards.scan.cursor.phase, 'waiting');
});
