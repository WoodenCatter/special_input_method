const assert = require("assert");
const core = require("../../main/assets/mahjong/js/engine.js");
const { ScanCursor } = require("../../main/assets/mahjong/js/scan.js");

const deck = core.createDeck();
assert.strictEqual(deck.length, 144, "the deck must contain 144 tiles");
for (let type = 0; type < 34; type += 1) {
  assert.strictEqual(deck.filter(tile => tile.type === type).length, 4, "standard tiles need four copies");
}
for (let type = 34; type < 42; type += 1) {
  assert.strictEqual(deck.filter(tile => tile.type === type).length, 1, "flower tiles need one copy");
}

assert.strictEqual(
  core.isWinningHand([0, 1, 2, 3, 4, 5, 9, 10, 11, 27, 27, 27, 31, 31], 0),
  true,
  "four melds and a pair should win"
);
assert.strictEqual(
  core.isWinningHand([0, 0, 1, 1, 9, 9, 10, 10, 18, 18, 27, 27, 31, 31], 0),
  true,
  "seven pairs should win"
);
assert.strictEqual(
  core.isWinningHand([0, 1, 2, 9, 9, 9, 18, 19, 20, 31, 31], 1),
  true,
  "an exposed meld should reduce the concealed meld count"
);
assert.strictEqual(
  core.isWinningHand([0, 1, 2, 3, 4, 6, 9, 10, 11, 27, 27, 27, 31, 31], 0),
  false,
  "an incomplete hand must not win"
);

assert.deepStrictEqual(
  core.chowOptions([0, 1, 3, 4], 2),
  [[0, 1, 2], [1, 2, 3], [2, 3, 4]],
  "all legal chow arrangements should be offered"
);
assert.deepStrictEqual(core.chowOptions([27, 27], 27), [], "honor tiles cannot form a chow");

const sampleHand = core.createDeck().filter(tile => tile.type < 14).slice(0, 14);
const discardIndex = core.chooseDiscardIndex(sampleHand, 0, new Array(34).fill(0), () => 0);
assert.ok(discardIndex >= 0 && discardIndex < sampleHand.length, "AI must choose a valid discard");

const discardCursor = new ScanCursor();
const discardCandidates = ["settings", "left", "middle", "right"];
discardCursor.setPhase("discard", discardCandidates, -1, discardCandidates.length - 1);
assert.strictEqual(discardCursor.current(), "right", "discard scanning must start at the rightmost tile");
assert.strictEqual(discardCursor.advance(), "middle", "discard scanning must move from right to left");
discardCursor.setDirectionFromAction("look_right");
assert.strictEqual(discardCursor.advance(), "right", "right look must change scanning to left-to-right");
discardCursor.setDirectionFromAction("look_left");
assert.strictEqual(discardCursor.advance(), "middle", "left look must change scanning to right-to-left");

const actionCursor = new ScanCursor();
actionCursor.setPhase("actions", ["碰", "胡", "设置"], 1, 0);
assert.strictEqual(actionCursor.current(), "碰", "action scanning must begin on the left");
assert.strictEqual(actionCursor.advance(), "胡", "action scanning must move left-to-right by default");

console.log("Mahjong engine and scan tests passed");
