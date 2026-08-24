/* =========================================================================
 * UI 初始化与主循环
 * ======================================================================= */

function updateTimeUI() {
  document.getElementById('tl-time').value = Math.round(state.time);
  document.getElementById('tl-max').textContent = maxTick();
}

function updateLoopIndicator() {
  const el = document.getElementById('loop-indicator');
  if (el) el.style.opacity = state.loop ? '1' : '0.25';
}

function togglePlay() {
  state.playing = !state.playing;
  document.getElementById('btn-play').textContent = state.playing ? '⏸ 暂停' : '▶ 播放';
  resetVelOffsets();
}

let shiftHeld = false;
window.addEventListener('keydown', (e) => { if (e.key === 'Shift') shiftHeld = true; });
window.addEventListener('keyup', (e) => { if (e.key === 'Shift') shiftHeld = false; });
evShift = () => shiftHeld;
window.addEventListener('keydown', (e) => { if (e.key === 'Control') document.body.classList.add('ctrl-held'); });
window.addEventListener('keyup', (e) => { if (e.key === 'Control') document.body.classList.remove('ctrl-held'); });

function initUI() {
  const tlEase = document.getElementById('tl-easing');
  tlEase.innerHTML = easingCurveSVG(state.defaultEasing);
  tlEase.onclick = () => openEasingEditor(state.defaultEasing, (e) => {
    state.defaultEasing = e;
    tlEase.innerHTML = easingCurveSVG(e);
  }, tlEase);

  // 菜单（悬停展开）
  document.querySelectorAll('.menu').forEach(menu => {
    menu.addEventListener('mouseenter', () => {
      document.querySelectorAll('.menu').forEach(m => m.classList.remove('open'));
      menu.classList.add('open');
    });
    menu.addEventListener('mouseleave', () => menu.classList.remove('open'));
  });
  const closeMenus = () => document.querySelectorAll('.menu').forEach(m => m.classList.remove('open'));
  document.getElementById('btn-new').addEventListener('click', () => { closeMenus(); newFile(); });
  document.getElementById('btn-open').addEventListener('click', () => { closeMenus(); openFile(); });
  document.getElementById('btn-save').addEventListener('click', () => { closeMenus(); saveFile(); });
  document.getElementById('btn-saveas').addEventListener('click', () => { closeMenus(); saveFileAs(); });
  document.getElementById('btn-export').addEventListener('click', () => { closeMenus(); exportAnimation(); });
  document.getElementById('btn-clear').addEventListener('click', () => { closeMenus(); clearAll(); });
  document.getElementById('btn-undo').addEventListener('click', () => { closeMenus(); undo(); });
  document.getElementById('btn-redo').addEventListener('click', () => { closeMenus(); redo(); });
  document.getElementById('btn-selall').addEventListener('click', () => { closeMenus(); selectAll(); });
  document.getElementById('btn-delete-selected').addEventListener('click', () => { closeMenus(); deleteSelected(); });
  document.getElementById('btn-group').addEventListener('click', () => { closeMenus(); createGroup(); });

  // 工具
  document.getElementById('tools').addEventListener('click', (ev) => {
    const btn = ev.target.closest('.tool');
    if (!btn) return;
    state.tool = btn.dataset.tool;
    document.querySelectorAll('.tool').forEach(b => b.classList.toggle('active', b === btn));
    updateGizmo(); // 切换工具时立即刷新 gizmo 显示模式
  });

  // 右侧选项卡切换
  document.getElementById('sidebar-tabs').addEventListener('click', (ev) => {
    const btn = ev.target.closest('.tab');
    if (!btn) return;
    document.querySelectorAll('#sidebar-tabs .tab').forEach(b => b.classList.toggle('active', b === btn));
    document.querySelectorAll('.tab-pane').forEach(p => p.classList.toggle('active', p.id === 'pane-' + btn.dataset.tab));
    if (btn.dataset.tab === 'texture') refreshTexturePanel();
  });

  // 函数对象
  const fxPresetSel = document.getElementById('fx-preset-add');
  for (const id in FUNCTION_PRESETS) {
    const o = document.createElement('option'); o.value = id; o.textContent = FUNCTION_PRESETS[id].label;
    fxPresetSel.appendChild(o);
  }
  fxPresetSel.value = 'blank';
  document.getElementById('btn-fx-preset-add').addEventListener('click', () => {
    if (fxPresetSel.value) createFunctionObject(fxPresetSel.value);
  });
  refreshFunctionPanel();

  // 属性
  document.getElementById('prop-glow').addEventListener('change', (ev) => { pushUndo(); currentSelected().forEach(p => { p.glow = ev.target.checked; }); rebuildPoints(); });
  document.getElementById('prop-light').addEventListener('input', (ev) => { beginContinuous(); document.getElementById('light-val').textContent = ev.target.value; currentSelected().forEach(p => { p.lightLevel = parseInt(ev.target.value); }); rebuildPoints(); });
  document.getElementById('prop-light').addEventListener('change', endContinuous);
  document.getElementById('prop-alpha').addEventListener('input', (ev) => { beginContinuous(); document.getElementById('alpha-val').textContent = parseFloat(ev.target.value).toFixed(2); applyColorFromInputs(); });
  document.getElementById('prop-alpha').addEventListener('change', endContinuous);
  document.getElementById('prop-color').addEventListener('input', (ev) => { beginContinuous(); applyColorFromInputs(); });
  document.getElementById('prop-color').addEventListener('change', endContinuous);
  ['prop-scale-x', 'prop-scale-y', 'prop-scale-z'].forEach(id => {
    document.getElementById(id).addEventListener('input', (ev) => { beginContinuous(); applyScaleFromInputs(); });
    document.getElementById(id).addEventListener('change', endContinuous);
  });
  ['prop-posx', 'prop-posy', 'prop-posz'].forEach(id => {
    document.getElementById(id).addEventListener('input', (ev) => { beginContinuous(); applyPositionFromInputs(); });
    document.getElementById(id).addEventListener('change', endContinuous);
  });

  // 时间轴
  document.getElementById('btn-play').addEventListener('click', togglePlay);
  document.getElementById('tl-speed').addEventListener('change', (ev) => { state.playSpeed = Math.max(0.1, parseFloat(ev.target.value) || 1); });
  document.getElementById('tl-time').addEventListener('input', (ev) => { state.time = parseFloat(ev.target.value) || 0; resetVelOffsets(); updateTimeUI(); rebuildPoints(); syncFunctionVarValues(); if (typeof drawTimelineLayers === 'function') drawTimelineLayers(); });
  document.getElementById('tl-loop').addEventListener('change', (ev) => { state.loop = ev.target.checked; updateLoopIndicator(); });
  if (typeof tlInitLayerEvents === 'function') tlInitLayerEvents();

  // 文件导入
  document.getElementById('file-import').addEventListener('change', (ev) => {
    const f = ev.target.files[0];
    if (!f) return;
    state.fileHandle = null;
    loadFile(f);
    ev.target.value = '';
  });

  // 时间轴点击/拖动
  const tlCanvas = document.getElementById('timeline');
  let tlDrag = null; // { mode: 'scrub' | 'pan', lastX }
  tlCanvas.addEventListener('pointerdown', (ev) => {
    if (ev.button !== 0 && ev.button !== 1) return;
    ev.preventDefault();
    tlCanvas.setPointerCapture(ev.pointerId);
    if (ev.button === 1) { // 中键：平移视图
      tlDrag = { mode: 'pan', lastX: ev.clientX };
    } else {
      tlDrag = { mode: 'scrub', lastX: ev.clientX };
      state.time = Math.max(0, timelineXToTick(ev.clientX));
      resetVelOffsets();
      updateTimeUI();
      rebuildPoints();
      syncFunctionVarValues();
    }
  });
  tlCanvas.addEventListener('pointermove', (ev) => {
    if (!tlDrag) return;
    if (tlDrag.mode === 'pan') {
      timelineViewStart -= (ev.clientX - tlDrag.lastX) / TL_PX_PER_TICK;
      timelineViewStart = Math.max(-25, timelineViewStart);
    } else {
      state.time = Math.max(0, timelineXToTick(ev.clientX));
      updateTimeUI();
    }
    tlDrag.lastX = ev.clientX;
    drawTimeline();
    if (tlDrag.mode === 'scrub') { rebuildPoints(); syncFunctionVarValues(); }
  });
  tlCanvas.addEventListener('pointerup', () => { tlDrag = null; });
  tlCanvas.addEventListener('pointerleave', () => { tlDrag = null; });
  tlCanvas.addEventListener('wheel', (ev) => {
    ev.preventDefault();
    timelineViewStart += ev.deltaY / TL_PX_PER_TICK;
    timelineViewStart = Math.max(-25, timelineViewStart);
    drawTimeline();
  }, { passive: false });

  rebuildPoints();
  refreshParticleTree();
  // 恢复工作区状态（粒子列表宽）
  if (typeof applyWorkspaceState === 'function') applyWorkspaceState();
  if (typeof initTextureEditor === 'function') initTextureEditor();
}

function clearAll() {
  pushUndo();
  state.particles = []; state.tracks = []; state.groups = {}; state.functions = [];
  state.textures = {}; state.currentTexture = null; state.groupUV = {};
  state.selected.clear(); state.selectedGroup = null; state.selectedFunction = null;
  state.expandedParticles.clear(); state.expandedProps.clear();
  state.time = 0;
  updateTimeUI(); rebuildPoints(); refreshParticleTree(); refreshFunctionPanel();
}

function applyColorFromInputs() {
  const rgb = hexToRgb(document.getElementById('prop-color').value);
  const a = parseFloat(document.getElementById('prop-alpha').value);
  editSelectionUniform('col', [rgb[0], rgb[1], rgb[2], a]);
}

function applyPositionFromInputs() {
  const x = parseFloat(document.getElementById('prop-posx').value);
  const y = parseFloat(document.getElementById('prop-posy').value);
  const z = parseFloat(document.getElementById('prop-posz').value);
  if ([x, y, z].some(isNaN)) return;
  editSelectionUniform('pos', [x, y, z]);
}

function applyScaleFromInputs() {
  const x = parseFloat(document.getElementById('prop-scale-x').value);
  const y = parseFloat(document.getElementById('prop-scale-y').value);
  const z = parseFloat(document.getElementById('prop-scale-z').value);
  if ([x, y, z].some(isNaN)) return;
  editSelectionUniform('scl', [x, y, z]);
}

/* 左侧面板拖拽缩放 + 拖拽移出组 */
(function setupPanelResizeAndDrop() {
  const tree = document.getElementById('particle-tree');
  tree.addEventListener('dragover', (e) => { if (dragIds) { e.preventDefault(); e.dataTransfer.dropEffect = 'move'; } });
  tree.addEventListener('drop', (e) => {
    if (dragIds && !e.target.closest('.ptree-head.group')) {
      e.preventDefault();
      removeParticlesFromGroups(dragIds);
    }
    dragIds = null;
  });
  tree.addEventListener('contextmenu', (e) => {
    if (e.target.closest('.ptree-head')) return;
    e.preventDefault();
    showContextMenu(e.clientX, e.clientY, [
      { label: '添加粒子', action: () => { pushUndo(); addParticle({}); rebuildPoints(); refreshParticleTree(); } },
    ]);
  });
  tree.addEventListener('pointerdown', (e) => {
    if (!e.target.closest('.ptree-particle')) {
      state.selected.clear(); state.selectedGroup = null; state.selectedFunction = null;
      rebuildPoints();
      refreshFunctionPanel();
    }
  });

  const handle = document.getElementById('resize-handle');
  let resizing = false;
  handle.addEventListener('pointerdown', (e) => {
    resizing = true;
    handle.classList.add('dragging');
    handle.setPointerCapture(e.pointerId);
  });
  handle.addEventListener('pointermove', (e) => {
    if (!resizing) return;
    const layout = document.querySelector('.layout');
    const rect = layout.getBoundingClientRect();
    let w = e.clientX - rect.left;
    w = Math.max(220, Math.min(600, w));
    layout.style.setProperty('--left-w', w + 'px');
    resize();
  });
  handle.addEventListener('pointerup', () => { resizing = false; handle.classList.remove('dragging'); if (typeof saveWorkspaceState === 'function') saveWorkspaceState(); });

  // 右侧栏拖拽调整大小
  const handleR = document.getElementById('resize-handle-r');
  let resizingR = false;
  handleR.addEventListener('pointerdown', (e) => {
    resizingR = true;
    handleR.classList.add('dragging');
    handleR.setPointerCapture(e.pointerId);
  });
  handleR.addEventListener('pointermove', (e) => {
    if (!resizingR) return;
    const layout = document.querySelector('.layout');
    const rect = layout.getBoundingClientRect();
    let w = rect.right - e.clientX;
    w = Math.max(240, Math.min(640, w));
    layout.style.setProperty('--right-w', w + 'px');
    resize();
  });
  handleR.addEventListener('pointerup', () => { resizingR = false; handleR.classList.remove('dragging'); if (typeof saveWorkspaceState === 'function') saveWorkspaceState(); });
})();

function resize() {
  const w = viewport.clientWidth, h = viewport.clientHeight;
  renderer.setSize(w, h);
  camera.aspect = w / h;
  camera.updateProjectionMatrix();
  pointsMaterial.uniforms.uPixelScale.value = focalLengthPx();
  selectedMaterial.uniforms.uPixelScale.value = focalLengthPx();
  if (!document.body.classList.contains('puzzle-mode')) {
    drawTimeline();
    if (typeof refreshCompTimelines === 'function') refreshCompTimelines();
  }
}
window.addEventListener('resize', resize);
resize();

let last = performance.now();
function animate(now) {
  requestAnimationFrame(animate);
  const dt = Math.min((now - last) / 1000, 0.1);
  last = now;

  if (camTransition) {
    const t = Math.min(1, (now - camTransition.t0) / camTransition.dur);
    const e = easeInOut(t);
    const dir = slerp(camTransition.startDir, camTransition.endDir, e);
    camera.position.copy(camTransition.target).addScaledVector(dir, camTransition.dist);
    camera.up.lerpVectors(camTransition.startUp, camTransition.endUp, e).normalize();
    camera.lookAt(camTransition.target);
    if (t >= 1) camTransition = null;
    controls.update();
  }

  if (planePulse) {
    const t = (now - planePulse.t0) / planePulse.dur;
    if (t >= 1) {
      restoreAxisColors();
      planePulse = null;
    } else {
      setAxisGlow(planePulse.axes, Math.sin(Math.PI * t) * 0.85);
    }
  }

  if (state.playing) {
    state.time += dt * 20 * state.playSpeed;
    const mx = maxTick();
    if (state.time >= mx && mx > 0) {
      if (state.loop) { state.time = 0; resetVelOffsets(); }
      else { state.time = mx; state.playing = false; document.getElementById('btn-play').textContent = '▶ 播放'; resetVelOffsets(); }
    }
    updateTimeUI();
    drawTimeline();
    if (typeof drawTimelineLayers === 'function') drawTimelineLayers();
    rebuildPoints(false);
    syncFunctionVarValues();
  }
  controls.update();
  updateGizmoFrame();
  pointsMaterial.uniforms.uTime.value = performance.now() / 1000;
  renderer.render(scene, camera);
  drawAxisGizmo();
  // UV 动画预览：贴图 tab 激活且当前为动画模式时，逐帧刷新 overlay 让 UV 预览框跟随动画帧移动
  if (typeof texAnimOverlayActive === 'function' && texAnimOverlayActive()) {
    if (typeof updateTexOverlay === 'function') updateTexOverlay();
  }
}

initUI();
updateTopbarTitle();
requestAnimationFrame(animate);

// 关闭页面前若未保存则提示
window.addEventListener('beforeunload', (ev) => {
  if (state.dirty) {
    ev.preventDefault();
    ev.returnValue = '';
  }
});

// 拖拽文件到窗口即可打开
(function setupDragDrop() {
  window.addEventListener('dragover', (ev) => { ev.preventDefault(); });
  window.addEventListener('drop', async (ev) => {
    ev.preventDefault();
    const file = ev.dataTransfer && ev.dataTransfer.files && ev.dataTransfer.files[0];
    if (!file) return;
    if (!(await confirmDiscardChanges())) return;
    state.fileHandle = null;
    loadFile(file);
  });
})();
