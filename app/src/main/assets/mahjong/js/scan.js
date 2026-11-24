(function (root, factory) {
  const api = factory();
  if (typeof module === "object" && module.exports) module.exports = api;
  root.MahjongScan = api;
})(typeof globalThis !== "undefined" ? globalThis : this, function () {
  "use strict";

  class ScanCursor {
    constructor() {
      this.phase = "waiting";
      this.candidates = [];
      this.index = -1;
      this.direction = 1;
    }

    setPhase(phase, candidates, direction, startIndex) {
      this.phase = phase;
      this.confirmTicks = 0;
      this.previousConfirm = null;
      this.candidates = candidates.slice();
      this.direction = direction < 0 ? -1 : 1;
      if (!this.candidates.length) {
        this.index = -1;
      } else if (Number.isInteger(startIndex)) {
        this.index = Math.max(0, Math.min(startIndex, this.candidates.length - 1));
      } else {
        this.index = this.direction < 0 ? this.candidates.length - 1 : 0;
      }
      return this.current();
    }

    get confirming() { return this.phase === 'confirm' || this.phase === 'confirm_inline'; }

    confirmationDelay(interval) { return this.confirmTicks === 0 ? interval * 1.5 : interval; }

    get confirmationExpired() { return this.confirming && this.confirmTicks >= this.candidates.length * 3; }

    setDirectionFromAction(action) {
      if (this.confirming) {
        if (this.manualConfirm) this.index = (this.index + 1) % this.candidates.length;
        this.previousConfirm = null;
        return this.direction;
      }
      if (action === "look_left") this.direction = -1;
      if (action === "look_right") this.direction = 1;
      return this.direction;
    }

    advance() {
      if (this.confirming) {
        if (this.manualConfirm) return this.current();
        this.previousConfirm = this.current();
        this.advancedAt = Date.now();
        this.confirmTicks++;
      }
      if (!this.candidates.length) return null;
      this.index = (this.index + this.direction + this.candidates.length) % this.candidates.length;
      return this.current();
    }

    current() {
      return this.index >= 0 ? this.candidates[this.index] : null;
    }

    chosen() {
      if (this.confirming && !this.manualConfirm && this.previousConfirm && Date.now() - this.advancedAt < (this.graceMs ?? 400))
        return this.previousConfirm;
      return this.current();
    }

    snapshot() {
      return {
        phase: this.phase,
        candidates: this.candidates.slice(),
        index: this.index,
        direction: this.direction
      };
    }

    restore(snapshot) {
      if (!snapshot) return null;
      this.phase = snapshot.phase;
      this.candidates = snapshot.candidates.slice();
      this.direction = snapshot.direction < 0 ? -1 : 1;
      this.index = this.candidates.length
        ? Math.max(0, Math.min(snapshot.index, this.candidates.length - 1))
        : -1;
      return this.current();
    }
  }

  return { ScanCursor: ScanCursor };
});
