/** Synthetic-only behavior checks. Run with jsdom installed or BUCEA_JSDOM_MODULE set. */
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { JSDOM } = require(process.env.BUCEA_JSDOM_MODULE || 'jsdom');
const adapter = await readFile(new URL('../app/src/main/assets/mobile/adapter.js', import.meta.url), 'utf8');
const dom = new JSDOM(`<!doctype html><html><head><meta name="viewport" content="width=1024"></head><body>
<form action="/self/reserve"><input name="SYNCHRONIZER_TOKEN" type="hidden" value="synthetic-token">
<input id="search" type="button" class="searchBtn" value=""><input name="seat" value="test-seat"></form>
<div class="scrollPic"><a class="prev" href="#"></a><div class="scrollable"><div class="items">
<ul class="item"><li><a class="seat" data-seat="test-A" href="#">A</a></li></ul>
<ul class="item"><li><a class="seat" data-seat="test-B" href="#">B</a></li></ul></div></div><a class="next" href="#"></a></div>
<div class="seatLayoutList" style="display:none"><div class="seatInfr"><a class="quit" href="#">X</a></div><div class="seatLayout"><a href="#">12</a></div></div>
<div class="seatTime" style="display:none"><div class="startTime"><dl><ul><li><a time="480">08:00</a></li></ul></dl></div><a class="cancelBtn" href="#">取消</a></div>
</body></html>`, { runScripts: 'outside-only', pretendToBeVisual: true, url: 'http://10.1.20.7/self' });
const { window } = dom;
const document = window.document;
let frameCount = 0;
const requestFrame = window.requestAnimationFrame.bind(window);
window.requestAnimationFrame = (callback) => requestFrame((time) => { frameCount++; callback(time); });
const settle = () => new Promise((resolve) => window.setTimeout(resolve, 65));
const scroller = document.querySelector('.scrollable');
const wrap = scroller.querySelector('.items');
let width = 288;
let index = 1;
let seekCount = 0;
let selected = null;
let clicks = 0;
Object.defineProperty(scroller, 'clientWidth', { get: () => width });
const itemWrapper = (nodes) => ({
  ...nodes,
  length: nodes.length,
  eq: (i) => itemWrapper(nodes[i] ? [nodes[i]] : []),
  position: () => ({ left: index * (document.documentElement.hasAttribute('data-bucea-mobile') ? width : 660) })
});
const api = {
  getIndex: () => index,
  getItems: () => itemWrapper([...wrap.children]),
  getItemWrap: () => ({ 0: wrap, length: 1, stop() { return this; }, css(name, value) { wrap.style[name] = value; return this; } }),
  seekTo: () => { seekCount++; }
};
window.jQuery = () => ({ data: () => api });
scroller.addEventListener('click', (event) => { selected = event.target.closest('.seat')?.dataset.seat || selected; });
document.querySelector('#search').addEventListener('click', () => { clicks++; });

try {
  window.eval(adapter);
  await settle();
  assert.equal(wrap.style.left, '-288px');
  assert.equal(seekCount, 0, 'adapter resizing must not invoke AJAX seek handlers');
  document.querySelector('.seat[data-seat="test-B"]').click();
  document.querySelector('#search').click();
  assert.equal(selected, 'test-B');
  assert.equal(clicks, 1, 'inherited click handlers remain attached');
  assert.equal(document.querySelector('[name="SYNCHRONIZER_TOKEN"]').value, 'synthetic-token');
  assert.equal(document.querySelector('form').getAttribute('action'), '/self/reserve');
  assert.equal(document.querySelector('.seatTime').style.display, 'none');
  assert.equal(document.querySelector('.next').getAttribute('aria-label'), '下一页');

  width = 360;
  window.dispatchEvent(new window.Event('resize'));
  await settle();
  assert.equal(wrap.style.left, '-360px', 'current page stays aligned after resize');
  const added = document.createElement('ul');
  added.className = 'item';
  added.innerHTML = '<li><a class="seat" data-seat="test-C" href="#">C</a></li>';
  wrap.appendChild(added);
  document.querySelector('[data-seat="test-C"]').click();
  await settle();
  assert.equal(selected, 'test-C', 'AJAX-added seats keep delegated handlers');
  const settledFrames = frameCount;
  await settle();
  assert.equal(frameCount, settledFrames, 'mutation observer settles without an animation-frame loop');
  window.eval(adapter);
  await settle();
  assert.equal(document.querySelectorAll('#bucea-mobile-style').length, 1, 'repeat injection is idempotent');
  assert.equal(document.querySelectorAll('.bucea-map-hint').length, 1);

  window.BuceaMobile.setEnabled(false);
  assert.equal(document.querySelector('#search').value, '');
  assert.equal(document.querySelector('.next').hasAttribute('aria-label'), false);
  assert.equal(document.querySelector('meta[name="viewport"]').content, 'width=1024');
  assert.equal(wrap.style.left, '-660px', 'desktop toggle keeps the selected original page');
  assert.equal(document.querySelector('.seatLayoutList').style.display, 'none');
  assert.equal(seekCount, 0);
  console.log('PASS: inherited handlers, AJAX seats, page resize, tokens/forms, dialog state, observer settling, repeat injection and layout restoration.');
} finally {
  window.close();
}
