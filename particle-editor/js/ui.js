/* =========================================================================
 * UI 弹窗：替代浏览器原生 prompt / alert / confirm，符合主题风格
 * ======================================================================= */

let uiModalOverlay = null;

function closeUIModal() {
  if (!uiModalOverlay) return;
  const ov = uiModalOverlay;
  uiModalOverlay = null;
  ov.classList.add('closing');
  // 出现/消失动画结束后再移除 DOM
  const finish = () => ov.remove();
  ov.addEventListener('animationend', finish, { once: true });
  setTimeout(finish, 260); // 保险：animationend 未触发时兜底移除
}

// 底层：构建弹窗，返回 { box, input, resolve }
function buildModal({ title, message, input, buttons }) {
  return new Promise((resolve) => {
    closeUIModal();
    const overlay = document.createElement('div');
    overlay.className = 'ui-modal-overlay';
    const box = document.createElement('div');
    box.className = 'ui-modal';
    if (title) {
      const t = document.createElement('div'); t.className = 'ui-modal-title'; t.textContent = title;
      box.appendChild(t);
    }
    if (message) {
      const m = document.createElement('div'); m.className = 'ui-modal-msg'; m.textContent = message;
      box.appendChild(m);
    }
    let inp = null;
    if (input) {
      inp = document.createElement('input');
      inp.className = 'ui-modal-input';
      inp.type = 'text';
      inp.value = input.value != null ? String(input.value) : '';
      if (input.placeholder) inp.placeholder = input.placeholder;
      box.appendChild(inp);
    }
    const btns = document.createElement('div');
    btns.className = 'ui-modal-btns';
    let settled = false;
    const close = (v) => { if (settled) return; settled = true; resolve(v); closeUIModal(); };
    for (const b of buttons) {
      const btn = document.createElement('button');
      btn.className = 'ui-modal-btn' + (b.primary ? ' primary' : '') + (b.danger ? ' danger' : '');
      btn.textContent = b.label;
      btn.onclick = () => close(b.inputValue ? inp.value : b.value);
      btns.appendChild(btn);
    }
    box.appendChild(btns);
    overlay.appendChild(box);
    document.body.appendChild(overlay);
    uiModalOverlay = overlay;
    overlay.addEventListener('pointerdown', (e) => { if (e.target === overlay) close(null); });
    const onKey = (e) => {
      if (e.key === 'Escape') { close(null); return; }
      if (e.key === 'Enter' && inp) {
        const primary = buttons.find(b => b.primary);
        if (primary && primary.inputValue) close(inp.value);
      }
    };
    overlay.addEventListener('keydown', onKey);
    if (inp) setTimeout(() => { inp.focus(); inp.select(); }, 0);
    invisibleFocus(overlay);
  });
}
// 让无输入框的弹窗也能响应键盘（Esc）
function invisibleFocus(el) {
  el.tabIndex = -1;
  el.focus();
}

function modalPrompt(title, def, placeholder) {
  return buildModal({
    title,
    input: { value: def, placeholder },
    buttons: [{ label: '取消', value: null }, { label: '确定', value: null, primary: true, inputValue: true }],
  });
}
function modalAlert(title, message) {
  return buildModal({
    title, message,
    buttons: [{ label: '确定', value: undefined, primary: true }],
  });
}
function modalConfirm(title, message) {
  return buildModal({
    title, message,
    buttons: [{ label: '取消', value: false }, { label: '确定', value: true, primary: true }],
  });
}