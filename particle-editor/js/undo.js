/* =========================================================================
 * 撤回 / 重做
 * ======================================================================= */

const undoStack = [];
const redoStack = [];

function snapshot() {
  return {
    particles: state.particles.map(p => ({ ...p, color: p.color.slice(), pos: p.pos.slice(), vel: p.vel ? p.vel.slice() : [0, 0, 0] })),
    tracks: state.tracks.map(tr => ({ pr: tr.pr, m: tr.m, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], k[1].slice(), k[2]]) })),
    groups: JSON.parse(JSON.stringify(state.groups)),
    name: state.name,
    loop: state.loop,
    selected: [...state.selected],
    selectedGroup: state.selectedGroup,
  };
}

function restore(s) {
  state.particles = s.particles.map(p => ({ ...p, color: p.color.slice(), pos: p.pos.slice(), vel: p.vel ? p.vel.slice() : [0, 0, 0] }));
  state.tracks = s.tracks.map(tr => ({ pr: tr.pr, m: tr.m, ids: tr.ids.slice(), kf: tr.kf.map(k => [k[0], k[1].slice(), k[2]]) }));
  state.groups = JSON.parse(JSON.stringify(s.groups));
  state.name = s.name;
  state.loop = s.loop;
  state.selected = new Set(s.selected);
  state.selectedGroup = s.selectedGroup;
  document.getElementById('tl-loop').checked = state.loop;
  updateLoopIndicator();
  rebuildPoints();
  refreshParticleTree();
}

function pushUndo() {
  undoStack.push(snapshot());
  if (undoStack.length > 100) undoStack.shift();
  redoStack.length = 0;
  state.dirty = true;
}

function popUndo() { undoStack.pop(); }

let continuousDirty = false;
function beginContinuous() { if (!continuousDirty) { pushUndo(); continuousDirty = true; } }
function endContinuous() { continuousDirty = false; }

function undo() {
  if (undoStack.length === 0) return;
  redoStack.push(snapshot());
  restore(undoStack.pop());
  state.dirty = true;
}

function redo() {
  if (redoStack.length === 0) return;
  undoStack.push(snapshot());
  restore(redoStack.pop());
  state.dirty = true;
}
