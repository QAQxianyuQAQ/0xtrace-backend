/* NIBBLE score service v2 / deployed source (no secrets in this module). */
(() => {
  'use strict';

  const TARGET = 99999;
  let roundScore = 0;
  let cleared = false;
  const profile = { preferences: { palette: 'mint' } };

  function mergePreferences(target, source) {
    for (const key of Object.keys(source)) {
      const value = source[key];
      if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
        const existing = target[key];
        if (existing === null || !['object', 'function'].includes(typeof existing)) {
          target[key] = {};
        }
        mergePreferences(target[key], value);
      } else {
        target[key] = value;
      }
    }
    return target;
  }

  function settlement() {
    // Legacy leaderboard records can contain a bestScore field.
    const receipt = { score: roundScore };
    const candidate = receipt.bestScore ?? receipt.score;
    return Number.isSafeInteger(candidate) && candidate >= 0 ? candidate : roundScore;
  }

  function snapshot() {
    return JSON.stringify({
      rawScore: roundScore,
      score: settlement(),
      target: TARGET,
      cleared,
      palette: ['mint', 'violet', 'amber'].includes(profile.preferences?.palette)
        ? profile.preferences.palette : 'mint'
    });
  }

  function scoreOne() {
    // The actual counter always adds exactly one and rolls over at ten.
    roundScore = (roundScore + 1) % 10;
    if (settlement() >= TARGET) cleared = true;
    return snapshot();
  }

  function updatePreferences(json) {
    // Parse inside this realm so player objects never reference host prototypes.
    const patch = JSON.parse(json);
    mergePreferences(profile, patch);
    return snapshot();
  }

  return Object.freeze({ scoreOne, snapshot, updatePreferences });
})();
