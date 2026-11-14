// ==UserScript==
// @name         抖音网页版全能优化
// @name:zh-CN   抖音网页版全能优化
// @namespace    https://space.bilibili.com/482343
// @homepage     https://space.bilibili.com/398794136
// @version      4.8.28
// @description  弹幕关键词屏蔽、弹幕池、屏蔽池、正则测试、智能画质切换、礼物消息过滤、自定义倍速、开屏封面（全参数可调）、后台静音恢复、键盘快捷键自定义、全局防暂停、跳过直播、正则屏蔽视频、全功能调试模式
// @author       古海沉舟 · DeepSeek · 空谈华虚
// @license      CC-BY-NC-SA
// @icon         https://www.douyin.com/favicon.ico
// @match        https://www.douyin.com/*
// @include      https://*.douyin.com/*
// @run-at       document-start
// @require      https://cdnjs.cloudflare.com/ajax/libs/vue/3.2.31/vue.global.min.js
// @grant        GM_info
// @grant        GM_registerMenuCommand
// @grant        GM_setValue
// @grant        GM_getValue
// @grant        GM_addStyle
// @grant        unsafeWindow
// @grant        window.close
// @noframes
// @downloadURL https://update.greasyfork.org/scripts/584735/%E6%8A%96%E9%9F%B3%E7%BD%91%E9%A1%B5%E7%89%88%E5%85%A8%E8%83%BD%E4%BC%98%E5%8C%96.user.js
// @updateURL https://update.greasyfork.org/scripts/584735/%E6%8A%96%E9%9F%B3%E7%BD%91%E9%A1%B5%E7%89%88%E5%85%A8%E8%83%BD%E4%BC%98%E5%8C%96.meta.js
// ==/UserScript==

// 本插件为第三方增强工具，与抖音官方无关，仅供个人学习使用。
"use strict";

// ★ 版本号唯一来源：读 @version 头部声明
const SCRIPT_VERSION = (() => {
    try { return GM_info.script.version; } catch (e) { return '0.0.0'; }
})();

// =========================================================
//          加载状态提示（最早执行，用于自检）
// =========================================================
(function initLoadTip() {
    let tipEl = null;
    let resolved = false;

    const ensureEl = () => {
        if (tipEl && tipEl.parentNode) return tipEl;
        if (!document.body) return null;
        tipEl = document.createElement('div');
        tipEl.id = 'dyLoadTip';
        tipEl.style.cssText = [
            'position:fixed',
            'top:calc(6vh - 38px)',         /* ★ 和保存弹窗位置一致 */
            'right:max(2vw, 10px)',
            'z-index:2147483646',
            'padding:0 16px',
            'height:32px',
            'line-height:32px',
            'border-radius:6px',
            'font-size:13px',
            'font-weight:600',
            "font-family:'PingFang SC','Microsoft YaHei',sans-serif",
            'box-shadow:0 4px 16px rgba(0,0,0,0.4)',
            'pointer-events:none',
            'transition:opacity 0.4s ease',
            'opacity:0',
            'max-width:92vw',
            'overflow:hidden',
            'text-overflow:ellipsis',
            'white-space:nowrap',
        ].join(';');
        document.body.appendChild(tipEl);
        return tipEl;
    };

    const showTip = (text, color, bg) => {
        const el = ensureEl();
        if (!el) return;
        el.textContent = text;
        el.style.background = bg;
        el.style.color = color;
        el.style.opacity = '1';
    };

    const hideTip = (delayMs) => {
        setTimeout(() => {
            if (!tipEl) return;
            tipEl.style.opacity = '0';
            const el = tipEl;
            tipEl = null;
            setTimeout(() => { if (el.parentNode) el.remove(); }, 500);
        }, delayMs || 0);
    };

    const waitBody = (cb) => {
        if (document.body) { cb(); return; }
        const iv = setInterval(() => {
            if (document.body) { clearInterval(iv); cb(); }
        }, 30);
    };

    waitBody(() => {
        showTip('⏳ 插件加载中…', '#fff', 'rgba(64,64,64,0.95)');
    });

    // 捕获脚本执行过程中的错误
    window.addEventListener('error', (e) => {
        if (resolved) return;
        const msg = (e && e.message) ? e.message : '未知错误';
        showTip('❌ 插件加载失败：' + msg.slice(0, 50), '#fff', 'rgba(180,40,40,0.96)');
        hideTip(10000);
    }, true);

    // 超时兜底
    const timeoutId = setTimeout(() => {
        if (resolved) return;
        showTip('⚠️ 插件加载超时，请检查油猴是否正常 / 网络是否可达', '#fff', 'rgba(200,120,20,0.96)');
        hideTip(10000);
    }, 6000);

    // 对外接口
    window.__dyLoadOk = () => {
        if (resolved) return;
        resolved = true;
        clearTimeout(timeoutId);
        // 加载成功 → 直接淡出，不提示
        if (tipEl) {
            tipEl.style.opacity = '0';
            const el = tipEl;
            tipEl = null;
            setTimeout(() => { if (el.parentNode) el.remove(); }, 400);
        }
    };

    window.__dyLoadFail = (msg) => {
        if (resolved) return;
        resolved = true;
        clearTimeout(timeoutId);
        showTip('❌ 插件加载失败：' + (msg || '').slice(0, 50), '#fff', 'rgba(180,40,40,0.96)');
        hideTip(10000);
    };
})();

// =========================================================
//                          配置
// =========================================================
const DEFAULT_SETTINGS = {
    blockedDanmu_Switch: false,
    blockedDanmu_UseRegular: true,   // 新增屏蔽词时的默认模式（正则 or 关键词）
    blockedDanmu_Array: [],           // 每项结构：{ text: string, useRegex: boolean }
    enableQualitySwitch: true,
    enablePayHide: true,
    enableMirror: false,
    enableGiftFilter: true,
    enableKeepAlive: true,
    enableDOMClean: true,
    hideNonVideoElements_Switch: true,
    interactionLockDelay: 3.0,
    pollingQuality: 1.5,
    pollingDanmu: 0.5,
    pollingClean: 10.0,
    consoleOutputLog_Switch: false,
    hideBlockedWordsInMenu_Switch: false,
    poolSizeLimit: 50,
    customPlaybackRate: 1.0,
    enableSplashScreen: false,
    enableMsgFloat: true,

    // === 跳过直播 / 正则屏蔽视频 ===
    skipLive_Switch: false,
    skipVideoRegex_Switch: false,
    skipVideoRegex_UseRegular: true,  // 新增视频屏蔽规则时的默认模式
    skipVideoRegex_Array: [],         // 每项结构：{ text: string, useRegex: boolean }
    skipVideoMask_Switch: true,
    skipVideoPolling: 0.3,            // 兜底检测间隔（秒）
    skipVideoCooldown: 3000,          // 同卡片同指纹冷却（毫秒）
    skipVideoRetryInterval: 1500,     // 切换未生效时的重试间隔（毫秒）
    skipVideoRetryTimeout: 8000,      // 切换重试截止（毫秒）
    skipVideoBtnFallbackDelay: 300,   // 按钮点击后改用键盘的延迟（毫秒）

    // 防暂停细项（默认全开，由 enableKeepAlive 总开关控制）
    pauseGuard_visibilitySpoof: true,
    pauseGuard_eventBlocking: true,
    pauseGuard_rafReplacement: true,
    pauseGuard_mouseSimulation: true,
    pauseGuard_popupClick: true,
    pauseGuard_backgroundResume: true,

    // === 键盘快捷键 ===
    enableKeyboardShortcuts: true,
    keyTogglePayHide: '=',
    keyToggleGiftFilter: '*',
    keyToggleMirror: '/',

    // === 导航栏按钮 ===
    enableNavButton: true,

    // === 消息悬浮窗 ===
    enableMsgFloatHover: true,

    // === 防暂停 - 时间参数 ===
    pauseGuard_mouseSimInterval: 60,
    pauseGuard_backgroundResumeInterval: 30,
    pauseGuard_visibilityCheckInterval: 3,
    pauseGuard_popupClickInterval: 2,
    pauseGuard_popupClickDedupe: 1.5,
    pauseGuard_domMutationDebounce: 500,

    // === DOM 清理参数 ===
    domClean_minCardThreshold: 10,
    domClean_keepAround: 3,
    domClean_triggerProbability: 30,

    // === 开屏封面 ===
    splashDuration: 1200,
    splashFadeoutMs: 600,
    splashBgColor: '#000000',
    splashSlogan: '记录美好生活',

    // === 调试 ===
    debugMode: false,
};

let settings = GM_getValue("DY_Settings", {});
for (const key in DEFAULT_SETTINGS) {
    if (!(key in settings)) settings[key] = DEFAULT_SETTINGS[key];
}

// =========================================================
//              旧数据迁移：字符串 → {text, useRegex}
// =========================================================
(function migrateSettings() {
    let changed = false;

    function migrateArray(arr, defaultRegex) {
        if (!Array.isArray(arr)) return { arr: [], changed: false };
        let localChanged = false;
        const out = arr.map(item => {
            if (typeof item === 'string') {
                localChanged = true;
                return { text: item, useRegex: !!defaultRegex };
            }
            if (item && typeof item === 'object' && typeof item.text === 'string') {
                if (typeof item.useRegex !== 'boolean') {
                    localChanged = true;
                    return { text: item.text, useRegex: !!defaultRegex };
                }
                return item;
            }
            localChanged = true;
            return null;
        }).filter(Boolean);
        return { arr: out, changed: localChanged };
    }

    const r1 = migrateArray(settings.blockedDanmu_Array, settings.blockedDanmu_UseRegular);
    if (r1.changed) { settings.blockedDanmu_Array = r1.arr; changed = true; }
    else settings.blockedDanmu_Array = r1.arr;

    const r2 = migrateArray(settings.skipVideoRegex_Array, settings.skipVideoRegex_UseRegular);
    if (r2.changed) { settings.skipVideoRegex_Array = r2.arr; changed = true; }
    else settings.skipVideoRegex_Array = r2.arr;

    if (changed) GM_setValue('DY_Settings', settings);
})();

GM_setValue('DY_Settings', settings);

// =========================================================
//                    日志
// =========================================================
const colors = { reset: '\x1b[0m', red: '\x1b[31m', green: '\x1b[32m', yellow: '\x1b[33m', blue: '\x1b[34m', magenta: '\x1b[35m', cyan: '\x1b[36m', white: '\x1b[37m' };
function cc(color, ...args) { console.log(colors[color] + args.join(' ') + colors.reset); }
function log(...args) { if (settings.consoleOutputLog_Switch) console.log(`[${new Date().toLocaleTimeString()}]`, ...args); }
function plog(...args) { if (settings.consoleOutputLog_Switch) console.log('[防暂停]', ...args); }
function dlog(module, ...args) {
    if (settings.consoleOutputLog_Switch && settings.debugMode) {
        console.log(`%c[🔬 ${module}]`, 'color:#9C27B0;font-weight:bold', new Date().toLocaleTimeString(), ...args);
    }
}

// =========================================================
//         规则条目通用工具（每个词独立正则/关键词）
// =========================================================
function ruleGetText(item) {
    if (item == null) return '';
    if (typeof item === 'string') return item;
    return item.text || '';
}
function ruleGetUseRegex(item, fallback) {
    if (item == null) return !!fallback;
    if (typeof item === 'string') return !!fallback;
    return item.useRegex !== false;
}
function ruleTestItem(item, text, fallbackRegex) {
    const kw = ruleGetText(item);
    if (!kw) return false;
    const useRe = ruleGetUseRegex(item, fallbackRegex);
    if (useRe) {
        try { return new RegExp(kw, 'i').test(text); } catch (e) { return false; }
    }
    return text.includes(kw);
}

// =========================================================
//              全局防暂停模块（document-start 立即执行）
// =========================================================
const PauseGuard = (function () {
    const W = (typeof unsafeWindow !== 'undefined') ? unsafeWindow : window;
    const D = document;
    const DocProto = Object.getPrototypeOf(D);

    let _enabled = settings.enableKeepAlive !== false;
    let _dedupeMs = (settings.pauseGuard_popupClickDedupe || 1.5) * 1000;

    // --- 缓存原生 API ---
    let _nativeHiddenGetter = null;
    let _nativeVisGetter = null;
    let _nativeHasFocusFn = null;
    try {
        _nativeHiddenGetter = Object.getOwnPropertyDescriptor(DocProto, 'hidden').get;
        _nativeVisGetter = Object.getOwnPropertyDescriptor(DocProto, 'visibilityState').get;
        _nativeHasFocusFn = DocProto.hasFocus;
    } catch (e) { }

    const _nativeAddEventListener = EventTarget.prototype.addEventListener;
    const _nativeRemoveEventListener = EventTarget.prototype.removeEventListener;

    const realHidden = () => _nativeHiddenGetter ? _nativeHiddenGetter.call(D) : D.hidden;

    // --- 1. 可见性伪装 ---
    function patchVisibility() {
        if (!settings.pauseGuard_visibilitySpoof) return;
        const hijack = (proto, name, onValue) => {
            try {
                Object.defineProperty(proto, name, {
                    configurable: true,
                    get: () => _enabled ? onValue : (name.toLowerCase().indexOf('hidden') >= 0 ? realHidden() : (name.toLowerCase().indexOf('vis') >= 0 ? (_nativeVisGetter ? _nativeVisGetter.call(D) : 'visible') : onValue)),
                    set: () => { }
                });
            } catch (e) { }
        };
        hijack(DocProto, 'hidden', false);
        hijack(DocProto, 'visibilityState', 'visible');
        hijack(DocProto, 'webkitHidden', false);
        hijack(DocProto, 'webkitVisibilityState', 'visible');
        hijack(DocProto, 'mozHidden', false);
        hijack(DocProto, 'mozVisibilityState', 'visible');
        hijack(DocProto, 'msHidden', false);
        hijack(DocProto, 'msVisibilityState', 'visible');
        try {
            DocProto.hasFocus = function () {
                if (_enabled) return true;
                return _nativeHasFocusFn ? _nativeHasFocusFn.call(D) : true;
            };
        } catch (e) { }
        plog('可见性伪装已安装');
    }

    // --- 2. 事件拦截 ---
    const BLOCKED_EVENTS = {
        visibilitychange: 1, webkitvisibilitychange: 1,
        mozvisibilitychange: 1, msvisibilitychange: 1
    };

    function shouldBlockEvent(type, target) {
        if (!_enabled) return false;
        if (!settings.pauseGuard_eventBlocking) return false;
        if (!BLOCKED_EVENTS[type]) return false;
        return target === D || target === W;
    }

    function patchEventTarget() {
        try {
            EventTarget.prototype.addEventListener = function (type, listener, options) {
                if (shouldBlockEvent(type, this)) return;
                return _nativeAddEventListener.call(this, type, listener, options);
            };
            EventTarget.prototype.removeEventListener = function (type, listener, options) {
                if (shouldBlockEvent(type, this)) return;
                return _nativeRemoveEventListener.call(this, type, listener, options);
            };
            plog('事件拦截已安装');
        } catch (e) { }
    }

    // --- 3. rAF → setTimeout ---
    function patchRaf() {
        if (!settings.pauseGuard_rafReplacement) return;
        try {
            W.requestAnimationFrame = (cb) => setTimeout(() => cb(performance.now()), 16);
            W.cancelAnimationFrame = (id) => clearTimeout(id);
            plog('rAF 已替换为 setTimeout');
        } catch (e) { }
    }

    // --- 工具：穿透 Shadow DOM ---
    const collectEls = (root, out) => {
        if (!root) return;
        const walker = D.createTreeWalker(root, NodeFilter.SHOW_ELEMENT);
        let el;
        while ((el = walker.nextNode())) {
            out.push(el);
            if (el.shadowRoot) collectEls(el.shadowRoot, out);
        }
    };

    // --- 4. 资格管理 ---
    let eligibleVideos = new WeakSet();
    let eligibleCount = 0;
    const mutedOrig = new WeakMap();

    function snapshotPlayingAtHide() {
        const list = [];
        collectEls(D.documentElement, list);
        for (const v of list) {
            if (v.tagName === 'VIDEO' && !v.paused && !eligibleVideos.has(v)) {
                eligibleVideos.add(v);
                eligibleCount++;
            }
        }
        if (eligibleCount) plog(`[资格] 快照：${eligibleCount} 个视频允许后台恢复`);
    }

    function markPausedWhileHidden(v) {
        if (!eligibleVideos.has(v)) {
            eligibleVideos.add(v);
            eligibleCount++;
            plog('[资格] 隐藏期间视频被暂停，允许恢复');
        }
    }

    function playVideos(forceMute) {
        const list = [];
        collectEls(D.documentElement, list);
        let resumed = 0;
        for (const v of list) {
            if (v.tagName !== 'VIDEO' || v.paused === false || v.ended) continue;
            if (!eligibleVideos.has(v)) continue;
            if (forceMute && !v.muted) {
                try { mutedOrig.set(v, false); v.muted = true; } catch (e) { }
            }
            v.play().catch(() => { });
            resumed++;
        }
        if (resumed) plog(`已恢复 ${resumed} 个视频`);
    }

    function restoreVideos() {
        const list = [];
        collectEls(D.documentElement, list);
        for (const v of list) {
            if (v.tagName === 'VIDEO' && mutedOrig.has(v)) {
                try { v.muted = mutedOrig.get(v); } catch (e) { }
                mutedOrig.delete(v);
            }
        }
    }

    // --- 5. 弹窗自动点击 ---
    const RESUME_SELECTORS = [
        '.igUiNOJ9 .TxVs4ENa.dRu312SK.eE0e9Gi3',
        '.TxVs4ENa.dRu312SK.eE0e9Gi3',
        '.dRu312SK.eE0e9Gi3',
        '.dRu312SK'
    ];
    const PAUSE_DIALOG_SELECTORS = ['.igUiNOJ9', '.cfrKAQ5G', '.kSQMetim'];
    const PAUSE_DIALOG_HINT = '长时间无操作';
    const POPUP_TEXTS = ['继续播放', '继续观看', '恢复播放'];
    const FORBIDDEN_TEXTS = ['使用客户端免弹窗', '免弹窗', '客户端'];

    let lastClickedBtn = null;
    let lastClickedAt = 0;

    function isForbidden(txt) { return FORBIDDEN_TEXTS.some(f => txt.indexOf(f) >= 0); }

    function isPauseDialogVisible() {
        for (const sel of PAUSE_DIALOG_SELECTORS) {
            try {
                const el = D.querySelector(sel);
                if (el && (el.textContent || '').indexOf(PAUSE_DIALOG_HINT) >= 0) return true;
            } catch (e) { }
        }
        try {
            const all = D.querySelectorAll('.TxVs4ENa, .kSQMetim');
            for (const el of all) {
                if ((el.textContent || '').indexOf(PAUSE_DIALOG_HINT) >= 0) return true;
            }
        } catch (e) { }
        return false;
    }

    function findResumeBtn() {
        for (const sel of RESUME_SELECTORS) {
            let els;
            try { els = D.querySelectorAll(sel); } catch (e) { continue; }
            for (const el of els) {
                const txt = (el.textContent || '').trim();
                if (isForbidden(txt)) continue;
                if (POPUP_TEXTS.some(t => txt === t || txt.indexOf(t) === 0)) return el;
                if (!txt && sel.indexOf('dRu312SK') >= 0) return el;
            }
        }
        for (const sel of PAUSE_DIALOG_SELECTORS) {
            let containers;
            try { containers = D.querySelectorAll(sel); } catch (e) { continue; }
            for (const c of containers) {
                if ((c.textContent || '').indexOf(PAUSE_DIALOG_HINT) < 0) continue;
                const btns = c.querySelectorAll('div, button, span, a, p');
                for (const b of btns) {
                    const txt = (b.textContent || '').trim();
                    if (isForbidden(txt)) continue;
                    if (POPUP_TEXTS.some(t => txt === t || txt.indexOf(t) === 0)) return b;
                }
            }
        }
        const list = [];
        collectEls(D.documentElement, list);
        let best = null;
        for (const el of list) {
            const tag = el.tagName;
            if (tag !== 'BUTTON' && tag !== 'SPAN' && tag !== 'DIV' && tag !== 'A' && tag !== 'P') continue;
            const txt = (el.textContent || '').trim();
            if (isForbidden(txt)) continue;
            if (!POPUP_TEXTS.some(t => txt === t || (txt.indexOf(t) === 0 && txt.length <= t.length + 2))) continue;
            if (!best || el.getElementsByTagName('*').length < best.getElementsByTagName('*').length) best = el;
        }
        return best;
    }

    function clickResumeBtn() {
        if (!_enabled || !settings.pauseGuard_popupClick) return;
        if (realHidden() && eligibleCount === 0) return;
        if (!isPauseDialogVisible()) return;
        const btn = findResumeBtn();
        if (!btn) return;
        const now = Date.now();
        if (btn === lastClickedBtn && now - lastClickedAt < _dedupeMs) return;
        lastClickedBtn = btn;
        lastClickedAt = now;
        plog('[弹窗] 点击继续播放:', btn.tagName, (btn.textContent || '').trim().slice(0, 20));
        dlog('防暂停', '点击继续播放按钮');
        try {
            btn.click();
            ['mousedown', 'mouseup', 'pointerdown', 'pointerup'].forEach(type => {
                try {
                    btn.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: W }));
                } catch (e) { }
            });
        } catch (e) { }
        if (_enabled) playVideos(realHidden());
    }

    // --- 6. 定时器/观察器 ---
    let timers = [];
    let _mutationObserver = null;

    function stopTimers() {
        timers.forEach(id => clearInterval(id));
        timers = [];
        if (_mutationObserver) { _mutationObserver.disconnect(); _mutationObserver = null; }
    }

    function startTimers() {
        stopTimers();

        const debounceMs = settings.pauseGuard_domMutationDebounce || 500;
        const clickInterval = Math.max(200, (settings.pauseGuard_popupClickInterval || 2) * 1000);
        const mouseInterval = Math.max(5000, (settings.pauseGuard_mouseSimInterval || 60) * 1000);
        const visCheckInterval = Math.max(500, (settings.pauseGuard_visibilityCheckInterval || 3) * 1000);
        const bgResumeInterval = Math.max(3000, (settings.pauseGuard_backgroundResumeInterval || 30) * 1000);

        _dedupeMs = (settings.pauseGuard_popupClickDedupe || 1.5) * 1000;

        try {
            let popDebounce = null;
            _mutationObserver = new MutationObserver(() => {
                if (popDebounce) return;
                popDebounce = setTimeout(() => { popDebounce = null; clickResumeBtn(); }, debounceMs);
            });
            _mutationObserver.observe(D.documentElement, { childList: true, subtree: true });
        } catch (e) { }

        timers.push(setInterval(clickResumeBtn, clickInterval));

        timers.push(setInterval(() => {
            if (!_enabled || !settings.pauseGuard_mouseSimulation) return;
            if (!realHidden()) return;
            const ae = D.activeElement;
            if (ae && /^(INPUT|TEXTAREA|SELECT)$/i.test(ae.tagName)) return;
            try {
                D.dispatchEvent(new MouseEvent('mousemove', {
                    bubbles: true, cancelable: true, view: W,
                    clientX: 5 + Math.random() * 40,
                    clientY: 5 + Math.random() * 40
                }));
                dlog('防暂停', '派发假鼠标移动');
            } catch (e) { }
        }, mouseInterval));

        let prevHidden = realHidden();
        timers.push(setInterval(() => {
            if (!_enabled) return;
            const h = realHidden();
            if (h === prevHidden) return;
            prevHidden = h;
            if (h) {
                snapshotPlayingAtHide();
            } else {
                eligibleVideos = new WeakSet();
                eligibleCount = 0;
                restoreVideos();
                plog('[可见性] 回到前台：清除资格、恢复音量');
            }
        }, visCheckInterval));

        timers.push(setInterval(() => {
            if (!_enabled || !settings.pauseGuard_backgroundResume) return;
            if (realHidden()) playVideos(true);
            else restoreVideos();
        }, bgResumeInterval));

        try {
            _nativeAddEventListener.call(W, 'visibilitychange', () => {
                if (realHidden()) snapshotPlayingAtHide();
            }, true);
        } catch (e) { }

        plog(`定时器已启动：弹窗${clickInterval/1000}s / 鼠标${mouseInterval/1000}s / 可见性${visCheckInterval/1000}s / 后台恢复${bgResumeInterval/1000}s`);
    }

    // --- 7. 视频事件时间线 ---
    function installVideoTimeline() {
        ['play', 'pause'].forEach((type) => {
            try {
                _nativeAddEventListener.call(D, type, (e) => {
                    const v = e.target;
                    if (!v || v.tagName !== 'VIDEO') return;
                    if (type === 'pause' && realHidden()) markPausedWhileHidden(v);
                }, true);
            } catch (e) { }
        });
    }

    // --- 对外接口 ---
    function setEnabled(v) {
        _enabled = !!v;
        plog('开关 → ' + (_enabled ? '运行中' : '已关闭'));
    }

    // 立即安装（document-start）
    patchVisibility();
    patchEventTarget();
    patchRaf();
    installVideoTimeline();
    startTimers();

    return {
        setEnabled,
        isEnabled: () => _enabled,
        realHidden,
        playVideos: () => playVideos(realHidden()),
        restoreVideos,
        restartTimers: startTimers,
        get eligibleCount() { return eligibleCount; }
    };
})();

// =========================================================
//                        开屏封面
// =========================================================
(function showSplashScreenImmediately() {
    if (!settings.enableSplashScreen) return;
    if (document.getElementById('dySplashScreen')) return;
    if (sessionStorage.getItem('dySplashShown')) return;
    sessionStorage.setItem('dySplashShown', 'true');

    const duration = settings.splashDuration ?? 1200;
    const fadeout = settings.splashFadeoutMs ?? 600;
    const bgColor = settings.splashBgColor || '#000000';
    const slogan = settings.splashSlogan || '记录美好生活';

    const splash = document.createElement('div');
    splash.id = 'dySplashScreen';
    splash.style.cssText = `
        position: fixed; top: 0; left: 0;
        width: 100vw; height: 100vh;
        background: ${bgColor};
        display: flex; flex-direction: column;
        justify-content: center; align-items: center;
        z-index: 9999; opacity: 1;
        pointer-events: none;
        transition: opacity ${fadeout}ms ease;
    `;
    splash.innerHTML = `
        <div style="animation: dySplashBounce 0.8s ease; margin-bottom: 0px;">
            <svg width="200" height="200" viewBox="0 0 200 200" fill="none" xmlns="http://www.w3.org/2000/svg">
                <g clip-path="url(#clip0_2540_39617)">
                    <path d="M28.5975 33.0616V29.8161C27.4863 29.6643 26.3555 29.5713 25.2002 29.5713C11.3026 29.5762 -0.000488281 40.8793 -0.000488281 54.7769C-0.000488281 63.3044 4.25837 70.848 10.7593 75.4103C6.56403 70.9067 3.98913 64.8758 3.98913 58.2525C3.98913 44.5556 14.9741 33.3847 28.5975 33.0665V33.0616Z" fill="#00FAF0"/>
                    <path d="M29.1902 69.7612C35.3925 69.7612 40.4492 64.8317 40.6793 58.6833L40.6989 3.81254H50.7244C50.5139 2.68663 50.4013 1.52646 50.4013 0.336914H36.7093L36.6897 55.2077C36.4596 61.3512 31.4028 66.2856 25.2006 66.2856C23.2718 66.2856 21.4606 65.8059 19.8599 64.9639C21.9501 67.8619 25.3474 69.7612 29.1902 69.7612Z" fill="#00FAF0"/>
                    <path d="M69.4482 22.434V19.3843C65.625 19.3843 62.0613 18.2486 59.0752 16.3003C61.7333 19.3549 65.346 21.5578 69.4482 22.434Z" fill="#00FAF0"/>
                    <path d="M59.0754 16.3003C56.1627 12.9519 54.3907 8.58536 54.3907 3.8125H50.7241C51.6885 9.03083 54.7872 13.5051 59.0754 16.3003Z" fill="#FF0050"/>
                    <path d="M25.2001 43.2682C18.8559 43.2682 13.6914 48.4327 13.6914 54.777C13.6914 59.1974 16.1978 63.0352 19.8643 64.964C18.4985 63.0744 17.6859 60.7589 17.6859 58.2526C17.6859 51.9083 22.8504 46.7439 29.1946 46.7439C30.3793 46.7439 31.515 46.9397 32.5919 47.2774V33.3015C31.4807 33.1498 30.3499 33.0568 29.1946 33.0568C28.9939 33.0568 28.7981 33.0666 28.5974 33.0715V43.8067C27.5205 43.4689 26.3848 43.2731 25.2001 43.2731V43.2682Z" fill="#FF0050"/>
                    <path d="M69.4483 22.4341V33.0763C62.3502 33.0763 55.771 30.8049 50.4009 26.9524V54.7769C50.4009 68.6745 39.0978 79.9776 25.2002 79.9776C19.8302 79.9776 14.8517 78.2839 10.7593 75.4104C15.3608 80.3546 21.9204 83.4533 29.1948 83.4533C43.0924 83.4533 54.3955 72.1502 54.3955 58.2526V30.428C59.7655 34.2806 66.3447 36.552 73.4429 36.552V22.86C72.0722 22.86 70.7407 22.7131 69.4532 22.4341H69.4483Z" fill="#FF0050"/>
                    <path d="M50.4006 54.7769V26.9523C55.7706 30.8049 62.3499 33.0763 69.448 33.0763V22.434C65.3457 21.5578 61.7331 19.3549 59.0749 16.3003C54.7867 13.5051 51.688 9.02593 50.7237 3.8125H40.6982L40.6786 58.6833C40.4485 64.8268 35.3918 69.7612 29.1895 69.7612C25.3516 69.7612 21.9494 67.8619 19.8592 64.9639C16.1975 63.0351 13.6863 59.1973 13.6863 54.7769C13.6863 48.4326 18.8507 43.2682 25.195 43.2682C26.3796 43.2682 27.5153 43.464 28.5923 43.8017V33.0665C14.9688 33.3847 3.98389 44.5556 3.98389 58.2525C3.98389 64.8758 6.55878 70.9116 10.754 75.4103C14.8464 78.2838 19.8249 79.9776 25.195 79.9776C39.0926 79.9776 50.3957 68.6745 50.3957 54.7769H50.4006Z" fill="white"/>
                    <path d="M102.472 20.0255H96.6709V28.5873H89.9253V34.3881H96.6709V46.2444L89.9253 47.2528V53.4013L96.6709 52.3928V62.5652C96.6709 63.3729 96.015 64.0288 95.2073 64.0288H90.312V69.8297H97.601C100.293 69.8297 102.472 67.6464 102.472 64.9589V51.5313L108.371 50.6501V44.5017L102.472 45.3829V34.393H108.371V28.5922H102.472V20.0304V20.0255Z" fill="white"/>
                    <path d="M135.471 20.0255H129.67V50.4592L110.52 52.0991V58.2476L129.67 56.6028V70.2948H135.471V56.079L141.35 55.5454V49.397L135.471 49.9305V20.0255Z" fill="white"/>
                    <path d="M126.664 26.8005L112.914 24.348V30.4916L126.664 32.949V26.8005Z" fill="white"/>
                    <path d="M126.664 39.5674L112.914 37.1149V43.2584L126.664 45.7158V39.5674Z" fill="white"/>
                    <path d="M188.599 36.9973L189.514 32.2783H183.366L182.455 36.9973H166.399L165.669 32.2783H159.521L160.25 36.9973H148.629V42.6905H200V36.9973H188.599Z" fill="white"/>
                    <path d="M198.374 24.0543H178.901L177.922 20.0255H170.364L171.343 24.0543H150.249V29.7034H198.374V24.0543Z" fill="white"/>
                    <path d="M190.468 46.876H158.16C155.467 46.876 153.289 49.0593 153.289 51.7467V65.0324C153.289 67.7248 155.472 69.9032 158.16 69.9032H190.468C193.161 69.9032 195.339 67.7199 195.339 65.0324V51.7467C195.339 49.0544 193.156 46.876 190.468 46.876ZM160.206 52.1286H188.422C189.23 52.1286 189.886 52.7845 189.886 53.5922V55.9615H158.747V53.5922C158.747 52.7845 159.403 52.1286 160.211 52.1286H160.206ZM188.422 64.6849H160.206C159.398 64.6849 158.742 64.0289 158.742 63.2212V60.8225H189.881V63.2212C189.881 64.0289 189.225 64.6849 188.417 64.6849H188.422Z" fill="white"/>
                </g>
                <defs>
                    <clipPath id="clip0_2540_39617">
                        <rect width="200" height="200" fill="white" transform="translate(0 0)"/>
                    </clipPath>
                </defs>
            </svg>
        </div>
        <div style="overflow: hidden; width: 200px; margin-top: -100px;">
            <div style="font-size: 24px; color: rgba(255,255,255,0.7); font-family: 'PingFang SC', 'Helvetica Neue', sans-serif; letter-spacing: 2px; transform: translateX(-100%); animation: dySloganSlide 0.6s ease 0.2s forwards; white-space: nowrap;">${slogan}</div>
        </div>
        <style>
            @keyframes dySplashBounce { 0% { transform: scale(0.6); opacity: 0; } 60% { transform: scale(1.1); opacity: 1; } 100% { transform: scale(1); opacity: 1; } }
            @keyframes dySloganSlide { 0% { transform: translateX(-100%); opacity: 0; } 100% { transform: translateX(0%); opacity: 1; } }
        </style>
    `;
    const target = document.body || document.documentElement;
    target.appendChild(splash);
    setTimeout(() => {
        splash.style.opacity = '0';
        setTimeout(() => { if (splash.parentNode) splash.remove(); }, fadeout);
    }, duration);
})();

// =========================================================
//               弹幕池 & 屏蔽池
// =========================================================
let danmuPool = [];
let blockedPool = [];
const MAX_POOL_STORAGE = 500;
let lastVideoContainerId = '';

function getDanmuContainer(danmuEl) {
    return danmuEl.closest('#sliderVideo, [data-e2e="feed-item"]');
}
function getContainerId(container) {
    if (!container) return '';
    const vid = container.getAttribute('data-e2e-vid') || container.id;
    return vid || container.outerHTML.slice(0, 30);
}
function getVideoTimeFromContainer(container) {
    if (!container) return null;
    const timeEl = container.querySelector('xg-icon.xgplayer-time');
    if (!timeEl) return null;
    const currentEl = timeEl.querySelector('.time-current');
    const durationEl = timeEl.querySelector('.time-duration');
    if (!currentEl) return null;
    const duration = durationEl ? durationEl.textContent.trim() : '--:--';
    return { current: currentEl.textContent.trim(), duration };
}
function getContainerCurrentTime(container) {
    const timeInfo = getVideoTimeFromContainer(container);
    if (timeInfo) {
        const parts = timeInfo.current.split(':').map(Number);
        if (parts.length === 2) return parts[0] * 60 + parts[1];
        if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
    }
    const video = container ? container.querySelector('video') : document.querySelector('video');
    if (video && !isNaN(video.currentTime)) return video.currentTime;
    return 0;
}
function getContainerDuration(container) {
    const timeInfo = getVideoTimeFromContainer(container);
    if (timeInfo && timeInfo.duration && timeInfo.duration !== '直播') {
        const parts = timeInfo.duration.split(':').map(Number);
        if (parts.length === 2) return parts[0] * 60 + parts[1];
        if (parts.length === 3) return parts[0] * 3600 + parts[1] * 60 + parts[2];
    }
    const video = container ? container.querySelector('video') : document.querySelector('video');
    if (video && !isNaN(video.duration) && isFinite(video.duration)) return video.duration;
    return 0;
}
function getContainerFullTimeStr(container) {
    const current = getContainerCurrentTime(container);
    const duration = getContainerDuration(container);
    const currentStr = formatVideoTime(current);
    if (duration > 0) {
        const durStr = formatVideoTime(duration);
        return `${currentStr} / ${durStr}`;
    }
    return `${currentStr} / --:--`;
}
function formatVideoTime(seconds) {
    if (!seconds || seconds < 0) return '00:00';
    const m = Math.floor((seconds % 3600) / 60);
    const s = Math.floor(seconds % 60);
    return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`;
}
function createDivider() {
    return { id: 'divider_' + Date.now() + '_' + Math.random().toString(36).slice(2, 6), isDivider: true, text: '──────── 视频切换 ────────', videoTime: 0, fullTimeStr: '─────' };
}
function resetPools() {
    danmuPool = [];
    blockedPool = [];
    lastVideoContainerId = '';
    if (menuApp) {
        const vm = menuApp._instance;
        if (vm && vm.proxy) { vm.proxy.following = false; }
    }
    log('🔄 池子已重置');
    setTimeout(filterDanmu, 100);
    if (menuApp) {
        const vm = menuApp._instance;
        if (vm && vm.proxy && vm.proxy.syncPools) { vm.proxy.syncPools(); }
    }
}
function insertDivider() {
    if (danmuPool.length === 0 && blockedPool.length === 0) return;
    const divider = createDivider();
    danmuPool.push(divider);
    blockedPool.push(divider);
    if (danmuPool.length > MAX_POOL_STORAGE) danmuPool = danmuPool.slice(-MAX_POOL_STORAGE);
    if (blockedPool.length > MAX_POOL_STORAGE) blockedPool = blockedPool.slice(-MAX_POOL_STORAGE);
    log('🔀 插入分割线');
    if (menuApp) {
        const vm = menuApp._instance;
        if (vm && vm.proxy && vm.proxy.syncPools) { vm.proxy.syncPools(); }
    }
}

// =========================================================
//                  菜单 CSS
// =========================================================
GM_addStyle(`
:root {
    --dy-ui-bg: rgb(48,48,48);
    --dy-ui-input-bg: rgb(64,64,64);
    --dy-ui-box-bg: rgb(89,89,89);
    --dy-ui-scrollbar: rgb(141,141,141);
    --dy-ui-text: rgb(250,250,250);
    --dy-ui-btn: rgb(0,174,236);
    --dy-font-size:14px;
    --dy-line-height:24px;
    --dy-radius:4px;
}
#dyMenuUi {
    font-size:var(--dy-font-size);
    position:fixed;
    top:6vh;
    top:6dvh;                        /* 支持动态视口高度的浏览器用 dvh */
    right:max(2vw, 10px);            /* 至少留 10px 边距 */
    z-index:1005;
    width:min(480px, 92vw);          /* 小视口自动缩窄 */
    max-height:88vh;
    max-height:88dvh;
    overflow-y:auto;
    background:var(--dy-ui-bg);
    border-radius:var(--dy-radius);
    padding:0 0 10px 0;
}
#dyMenuUi * { color:var(--dy-ui-text); box-sizing:border-box; border:0; border-radius:var(--dy-radius); line-height:var(--dy-line-height); font-family:"PingFang SC","Helvetica Neue","Microsoft YaHei",sans-serif; }
#dyMenuUi::-webkit-scrollbar { width:7px; }
#dyMenuUi::-webkit-scrollbar-track { background:var(--dy-ui-scrollbar); border-radius:7px; }
#dyMenuUi::-webkit-scrollbar-thumb { background:var(--dy-ui-input-bg); border-radius:7px; }
#dyMenuUi::-webkit-scrollbar-thumb:hover { background:var(--dy-ui-box-bg); }
#dyMenuTitle {
    position:sticky;
    top:0;
    z-index:10;
    background:var(--dy-ui-bg);
    font-size:16px;
    padding:10px 12px;
    font-weight:bold;
    border-bottom:1px solid rgba(255,255,255,0.1);
    display:flex;
    align-items:center;
    gap:8px;
}
#dyMenuTitle .dy-title-close {
    background:none;
    border:none;
    color:rgba(255,255,255,0.6);
    font-size:18px;
    line-height:1;
    cursor:pointer;
    padding:2px 8px;
    border-radius:4px;
    transition:all 0.15s;
    flex-shrink:0;
}
#dyMenuTitle .dy-title-close:hover {
    background:rgba(255,80,80,0.2);
    color:#ff6b6b;
}
#dyMenuTitle .dy-title-text {
    flex:1;
    text-align:center;
}
#dyMenuTitle .dy-title-spacer {
    width:34px;
    flex-shrink:0;
}
.dy-menu-section { background:var(--dy-ui-input-bg); padding:10px 12px; margin:8px 10px; border-radius:var(--dy-radius); }
.dy-menu-section .section-title { font-size:13px; color:rgba(255,255,255,0.5); margin-bottom:6px; letter-spacing:1px; }
.dy-menu-row { display:flex; align-items:center; flex-wrap:wrap; gap:4px 8px; margin-bottom:4px; }
.dy-menu-row label { font-size:14px; cursor:pointer; display:flex; align-items:center; gap:4px; }
.dy-menu-row input[type="checkbox"] { width:18px; height:18px; margin:0; appearance:none; border:2px solid #666; border-radius:4px; cursor:pointer; flex-shrink:0; background:#444; transition:all 0.2s; position:relative; }
.dy-menu-row input[type="checkbox"]:checked { border-color:#4CAF50; background:#4CAF50; }
.dy-menu-row input[type="checkbox"]:checked::after { content:"✓"; position:absolute; top:-2px; left:2px; font-size:16px; color:white; font-weight:bold; }
.dy-menu-row input[type="checkbox"]:hover { border-color:#888; }
.dy-menu-row input[type="checkbox"]:checked:hover { border-color:#66BB6A; background:#66BB6A; }
.dy-status-badge { font-size:12px; padding:0 8px; border-radius:10px; line-height:20px; display:inline-block; font-weight:bold; }
.dy-status-on { background:#4CAF50; color:#fff; }
.dy-status-off { background:#666; color:#aaa; }
.dy-menu-row input[type="text"] { background:var(--dy-ui-box-bg); font-size:var(--dy-font-size); line-height:var(--dy-line-height); border-radius:var(--dy-radius); padding:0 8px; border:none; outline:none; flex:2; min-width:80px; }
.dy-menu-row input[type="number"] { background:var(--dy-ui-box-bg); font-size:var(--dy-font-size); line-height:var(--dy-line-height); border-radius:var(--dy-radius); padding:0 8px; border:none; outline:none; width:80px; flex:0; min-width:60px; }
.dy-menu-row button { line-height:var(--dy-line-height); border-radius:var(--dy-radius); padding:0 10px; background:var(--dy-ui-btn); border:none; cursor:pointer; transition:background 0.15s; white-space:nowrap; }
.dy-menu-row button:hover { background:rgb(17,154,204); }
.dy-tag-list { background:var(--dy-ui-box-bg); padding:4px 4px 0 4px; margin:4px 0 0 0; width:100%; min-height:30px; max-height:80px; overflow-y:auto; border-radius:var(--dy-radius); display:flex; flex-wrap:wrap; gap:4px; }
.dy-tag-list .dy-tag { background:var(--dy-ui-btn); padding:0 6px; border-radius:var(--dy-radius); display:inline-flex; align-items:center; gap:4px; font-size:13px; line-height:22px; }
.dy-tag-list .dy-tag button { background:none; border:none; color:var(--dy-ui-text); font-size:18px; line-height:18px; padding:0 2px; cursor:pointer; opacity:0.7; }
.dy-tag-list .dy-tag button:hover { opacity:1; }
.dy-tag-list .dy-tag .dy-tag-mode { display:inline-block; padding:0 5px; border-radius:3px; font-size:11px; line-height:16px; cursor:pointer; margin-right:2px; font-family:"Consolas",monospace; font-weight:bold; color:#fff; user-select:none; transition:all 0.15s; }
.dy-tag-list .dy-tag .dy-tag-mode.regex { background:#FFC107; color:#666; }
.dy-tag-list .dy-tag .dy-tag-mode.regex:hover { background:#FFB300; color:#666; }
.dy-tag-list .dy-tag .dy-tag-mode.keyword { background:#4CAF50; }
.dy-tag-list .dy-tag .dy-tag-mode.keyword:hover { background:#388E3C; }
.dy-danmu-pool, .dy-blocked-pool { max-height:200px; overflow-y:auto; background:var(--dy-ui-box-bg); border-radius:var(--dy-radius); padding:4px 6px; margin-top:4px; font-size:13px; scroll-behavior:smooth; }
.dy-danmu-item { display:flex; align-items:center; justify-content:space-between; padding:2px 0; border-bottom:1px solid rgba(255,255,255,0.05); gap:6px; cursor:pointer; transition:background 0.15s; }
.dy-danmu-item:hover { background:rgba(255,255,255,0.05); }
.dy-danmu-item .dy-danmu-text { flex:1; white-space:nowrap; overflow:hidden; text-overflow:ellipsis; }
.dy-danmu-item .dy-danmu-time { color:rgba(255,255,255,0.4); font-size:11px; flex-shrink:0; font-family:monospace; min-width:90px; text-align:right; }
.dy-divider { text-align:center; color:rgba(255,255,255,0.15); padding:6px 0 4px 0; border-top:1px solid rgba(255,255,255,0.08); margin:4px 0 2px 0; font-size:11px; letter-spacing:2px; cursor:default !important; pointer-events:none; }
.dy-divider:hover { background:transparent !important; }
.dy-highlight-danmu { animation:dyFlashBorder 0.8s ease 3; border:2px solid #FFD700 !important; background:rgba(255,215,0,0.3) !important; border-radius:4px; padding:2px 4px; }
@keyframes dyFlashBorder { 0%{border-color:#FFD700;background:rgba(255,215,0,0.3);} 50%{border-color:#FF6B6B;background:rgba(255,107,107,0.3);} 100%{border-color:#FFD700;background:rgba(255,215,0,0.3);} }
.dy-toggle-btn { background:var(--dy-ui-btn); border:none; color:#fff; padding:2px 12px; border-radius:4px; cursor:pointer; font-size:13px; line-height:24px; transition:background 0.2s; }
.dy-toggle-btn:hover { background:rgb(17,154,204); }
.dy-blocked-item .dy-danmu-text { color:#ff6b6b; text-decoration:line-through; }
.dy-blocked-item .dy-danmu-time { color:rgba(255,107,107,0.4); }
.dy-refresh-btn { background:#FF9800 !important; }
.dy-refresh-btn:hover { background:#E68900 !important; }
.dy-latest-btn { background:#4CAF50 !important; margin-left:6px; }
.dy-latest-btn:hover { background:#388E3C !important; }
.dy-pool-header { display:flex; align-items:center; justify-content:space-between; flex-wrap:wrap; gap:6px; }
.dy-pool-header .dy-pool-title { display:flex; align-items:center; gap:8px; }
.dy-danger-btn { background:#d32f2f !important; }
.dy-danger-btn:hover { background:#b71c1c !important; }
#dyMenuPrompt {
    position:fixed;
    top:calc(6vh - 38px);
    right:max(2vw, 10px);
    z-index:1006;
    line-height:30px;
    border-radius:var(--dy-radius);
    padding:0 15px;
    height:30px;
    max-width:92vw;
    white-space:nowrap;
    overflow:hidden;
    text-overflow:ellipsis;
    background:var(--dy-ui-box-bg);
    transition:opacity 0.4s ease;
    pointer-events:none;
    box-shadow:0 2px 8px rgba(0,0,0,0.3);
}

/* 设置词条解释气泡 —— 由 JS 全局浮动控制，避免被菜单容器裁切 */
.dy-tip { cursor: help; border-bottom: 1px dashed rgba(255,255,255,0.25); }

#dyGlobalTooltip {
    position: fixed;
    top: 0;
    left: 0;
    z-index: 2147483647;
    background: rgba(15,15,15,0.97);
    color: #eee;
    font-size: 12px;
    font-weight: normal;
    padding: 8px 12px;
    border-radius: 6px;
    line-height: 1.6;
    max-width: 300px;
    min-width: 40px;
    white-space: normal;
    text-align: left;
    box-shadow: 0 6px 20px rgba(0,0,0,0.6);
    border: 1px solid rgba(255,255,255,0.1);
    opacity: 0;
    visibility: hidden;
    pointer-events: none;
    transition: opacity 0.15s ease;
    word-break: break-word;
    font-family: "PingFang SC","Helvetica Neue","Microsoft YaHei",sans-serif;
}
#dyGlobalTooltip.dy-tooltip-show {
    opacity: 1;
    visibility: visible;
}

/* 池子区域的子块 */
.dy-sub-section {
    background: rgba(0,0,0,0.15);
    border-radius: 4px;
    padding: 8px 10px;
    margin-top: 6px;
    border-left: 2px solid rgba(255,165,0,0.4);
}
.dy-sub-section .dy-pool-header { margin-bottom: 6px; }

/* 折叠高级设置 */
.dy-advanced-content {
    background: rgba(0,0,0,0.25);
    border-radius: 4px;
    padding: 8px 10px;
    margin-top: 6px;
    border-left: 2px solid rgba(255,152,0,0.5);
}
.dy-advanced-content .dy-menu-row { margin-bottom: 6px; }
`);

// =========================================================
//                       菜单 HTML
// =========================================================
const menuHTML = `
<div id="dyMenuUi">
    <div id="dyMenuTitle">
        <span class="dy-title-spacer"></span>
        <span class="dy-title-text">🎯 抖音优化 v${SCRIPT_VERSION}</span>
        <button class="dy-title-close" @click="closeMenu()" title="关闭菜单">✕</button>
    </div>
    <!-- ========== 📋 池子区域（弹幕池 + 屏蔽池）========== -->
    <div class="dy-menu-section" style="border-left:3px solid #FFA500;">
        <div class="section-title dy-pool-header">
            <span class="dy-pool-title">📋 弹幕池 & 🛡️ 屏蔽池</span>
            <span style="display:flex; gap:4px;">
                <button class="dy-refresh-btn" v-show="poolsAreaExpanded" @click="manualRefresh()"
                        style="font-size:12px;padding:0 10px;line-height:22px;border:none;border-radius:4px;color:#fff;cursor:pointer;">重置池子</button>
                <button class="dy-toggle-btn" @click="poolsAreaExpanded = !poolsAreaExpanded"
                        style="font-size:12px;padding:0 10px;line-height:22px;">
                    {{ poolsAreaExpanded ? '收起 ▲' : '展开 ▼' }}
                </button>
            </span>
        </div>

        <div v-show="poolsAreaExpanded">

            <!-- 弹幕池 -->
            <div class="dy-sub-section">
                <div class="dy-pool-header" style="margin-bottom:6px;">
                    <span class="dy-pool-title" style="font-size:13px;font-weight:600;">📋 弹幕池（实时）</span>
                    <span style="display:flex; gap:4px;">
                        <button class="dy-latest-btn" @click="goToLatest('danmu')"
                                style="font-size:12px;padding:0 10px;line-height:24px;border:none;border-radius:4px;color:#fff;cursor:pointer;">最新</button>
                        <button class="dy-toggle-btn" @click="danmuListExpanded = !danmuListExpanded"
                                style="font-size:12px;padding:0 10px;line-height:24px;">
                            {{ danmuListExpanded ? '收起列表 ▲' : '展开列表 ▼' }}
                        </button>
                    </span>
                </div>
                <div v-show="danmuListExpanded">
                    <div style="font-size:12px;color:rgba(255,255,255,0.3);margin-bottom:4px;">
                        共 {{ danmuList.length }} 条（显示上限 {{ s.poolSizeLimit }}）
                        <span v-if="danmuList.length === 0" style="color:#aaa;">（暂无弹幕）</span>
                    </div>
                    <div class="dy-danmu-pool" ref="danmuPoolContainer" v-if="danmuList.length > 0">
                        <template v-for="item in danmuList" :key="item.id">
                            <div v-if="item.isDivider" class="dy-divider">{{ item.text }}</div>
                            <div v-else class="dy-danmu-item" @click="locateDanmu(item.id)">
                                <span class="dy-danmu-text" :title="item.text">{{ item.text }}</span>
                                <span class="dy-danmu-time">⏱ {{ item.fullTimeStr }}</span>
                            </div>
                        </template>
                    </div>
                    <div style="font-size:11px;color:rgba(255,255,255,0.2);margin-top:4px;text-align:right;">点击弹幕定位 · 分割线表示视频切换</div>
                </div>
            </div>

            <!-- 屏蔽池 -->
            <div class="dy-sub-section" style="border-left-color:rgba(255,107,107,0.5);">
                <div class="dy-pool-header" style="margin-bottom:6px;">
                    <span class="dy-pool-title" style="font-size:13px;font-weight:600;">🛡️ 屏蔽池（{{ blockedList.length }} 条）</span>
                    <span style="display:flex; gap:4px;">
                        <button class="dy-latest-btn" @click="goToLatest('blocked')"
                                style="font-size:12px;padding:0 10px;line-height:24px;border:none;border-radius:4px;color:#fff;cursor:pointer;">最新</button>
                        <button class="dy-toggle-btn" @click="blockedPoolExpanded = !blockedPoolExpanded"
                                style="font-size:12px;padding:0 10px;line-height:24px;">
                            {{ blockedPoolExpanded ? '收起列表 ▲' : '展开列表 ▼' }}
                        </button>
                    </span>
                </div>
                <div style="font-size:12px;color:rgba(255,255,255,0.3);margin-bottom:4px;" v-if="blockedList.length === 0">（暂无屏蔽记录）</div>
                <div v-if="blockedPoolExpanded" style="margin-top:4px;">
                    <div class="dy-blocked-pool" ref="blockedPoolContainer" v-if="blockedList.length > 0">
                        <template v-for="item in blockedList" :key="item.id">
                            <div v-if="item.isDivider" class="dy-divider">{{ item.text }}</div>
                            <div v-else class="dy-danmu-item dy-blocked-item" @click="locateDanmu(item.id)">
                                <span class="dy-danmu-text" :title="item.text">{{ item.text }}</span>
                                <span class="dy-danmu-time">⏱ {{ item.fullTimeStr }}</span>
                            </div>
                        </template>
                    </div>
                </div>
            </div>

            <!-- ★ 弹幕屏蔽设置折叠按钮（放在弹幕池这一栏最底下） -->
            <div style="border-top:1px solid rgba(255,255,255,0.08);padding-top:8px;margin-top:4px;text-align:left;">
                <button class="dy-toggle-btn" @click="showDanmuBlockedSettings = !showDanmuBlockedSettings"
                        style="font-size:12px;padding:0 12px;line-height:24px;background:var(--dy-ui-btn);">
                    {{ showDanmuBlockedSettings ? '▲ 收起弹幕屏蔽设置' : '⚙️ 弹幕屏蔽设置 ▼' }}
                </button>
            </div>

            <!-- ★ 弹幕关键词屏蔽内容（内嵌在弹幕池栏内，不再单独一栏） -->
            <div v-show="showDanmuBlockedSettings"
                 style="margin-top:8px;padding-top:8px;border-top:1px dashed rgba(255,255,255,0.08);">
                <div class="section-title" style="font-size:13px;font-weight:600;color:rgba(255,255,255,0.7);margin-bottom:6px;">💬 弹幕关键词屏蔽</div>

                <div class="dy-menu-row">
                    <label><input type="checkbox" v-model="s.blockedDanmu_Switch" @change="onDanmuToggle()" />
                        <span class="dy-tip" data-tip="开启后，命中屏蔽词的弹幕会被隐藏并记录到屏蔽池">启用弹幕屏蔽</span>
                    </label>
                    <span class="dy-status-badge" :class="s.blockedDanmu_Switch ? 'dy-status-on' : 'dy-status-off'">
                        {{ s.blockedDanmu_Switch ? '✅ 已开启' : '❌ 已关闭' }}
                    </span>
                </div>
                <div class="dy-menu-row">
                    <input type="text" placeholder="输入屏蔽词，逗号分隔" v-model="temp.danmuInput" />
                    <button @click="addItem('blockedDanmu_Array', temp.danmuInput)">添加</button>
                    <button @click="showRegexTest = !showRegexTest" style="background:#9C27B0;">
                        {{ showRegexTest ? '关闭测试' : '🧪 正则测试' }}
                    </button>
                </div>
                <div class="dy-menu-row" style="align-items:center;">
                    <span style="font-size:12px;color:rgba(255,255,255,0.5);">新增词的默认模式：</span>
                    <label style="margin-left:4px;"><input type="checkbox" v-model="s.blockedDanmu_UseRegular" @change="immediateSave()" />
                        <span class="dy-tip" data-tip="勾选后，新添加的屏蔽词默认按正则表达式处理。每个词仍可在下方标签上单独切换模式">默认正则</span>
                    </label>
                    <span style="font-size:11px;color:rgba(255,255,255,0.35);margin-left:6px;">（点击标签上的 Aa / .* 可单独切换）</span>
                </div>
                <div class="dy-tag-list">
                    <span class="dy-tag" v-for="(v,i) in s.blockedDanmu_Array" :key="i">
                        <span class="dy-tag-mode" :class="v.useRegex ? 'regex' : 'keyword'"
                              @click="toggleItemMode('blockedDanmu_Array', i)"
                              :title="v.useRegex ? '当前：正则模式（点击切换为关键词）' : '当前：关键词模式（点击切换为正则）'">{{ v.useRegex ? '.*' : 'Aa' }}</span>
                        {{ displayText(v,i) }}
                        <button @click="removeItem('blockedDanmu_Array', i)">×</button>
                    </span>
                </div>

                <div v-if="showRegexTest" style="background:rgba(156,39,176,0.15);border-radius:4px;padding:8px 10px;margin-top:6px;">
                    <div style="font-size:12px;color:rgba(255,255,255,0.6);margin-bottom:6px;">
                        🧪 正则 / 关键词测试
                        <span class="dy-tip" data-tip="输入一段文本，实时检测会被哪些屏蔽词命中；正则语法错误也会提示。每个词按自身模式判定" style="margin-left:6px;">?</span>
                    </div>
                    <div class="dy-menu-row">
                        <input type="text" placeholder="输入测试文本，例如：这是广告内容" v-model="regexTestText" @input="runRegexTest" style="flex:3;" />
                    </div>
                    <div style="font-size:12px;line-height:1.8;background:rgba(0,0,0,0.3);padding:6px 10px;border-radius:4px;max-height:120px;overflow-y:auto;">
                        <div v-if="!regexTestText" style="color:rgba(255,255,255,0.3);">输入文本后在此显示命中结果…</div>
                        <div v-else-if="regexTestResult.length === 0" style="color:#4CAF50;">✅ 未命中任何屏蔽词</div>
                        <div v-else>
                            <div v-for="(r,i) in regexTestResult" :key="i"
                                 :style="{ color: r.valid ? '#FF6B6B' : '#FF9800' }">
                                <span :style="{ color: r.useRegex ? '#CE93D8' : '#81C784', fontFamily:'monospace' }">[{{ r.useRegex ? '正则' : '关键词' }}]</span>
                                {{ r.valid ? '🚫 命中：' : '⚠️ 语法错误：' }} <code>{{ r.pattern }}</code>
                                <span style="color:rgba(255,255,255,0.5);"> — {{ r.reason }}</span>
                            </div>
                        </div>
                    </div>
                </div>

                <div style="font-size:12px;color:rgba(255,255,255,0.3);margin-top:4px;">
                    ⚡ 直接扫描所有弹幕元素，不受容器重建影响
                    <span v-if="s.blockedDanmu_Switch && s.blockedDanmu_Array.length === 0" style="color:#FF6B6B;display:block;margin-top:2px;">⚠️ 已开启但未添加屏蔽词</span>
                </div>
            </div>
        </div>
    </div>

    <!-- ========== 🚫 跳过直播 / 正则屏蔽视频（默认折叠）========== -->
    <div class="dy-menu-section" style="border-left:3px solid #FF6B6B;">
        <div class="section-title dy-pool-header">
            <span class="dy-pool-title">🚫 跳过直播 / 正则屏蔽视频</span>
            <button class="dy-toggle-btn" @click="skipVideoExpanded = !skipVideoExpanded"
                    style="font-size:12px;padding:0 10px;line-height:22px;">
                {{ skipVideoExpanded ? '收起 ▲' : '展开 ▼' }}
            </button>
        </div>

        <div v-show="skipVideoExpanded">
            <div class="dy-menu-row">
                <label><input type="checkbox" v-model="s.skipLive_Switch" @change="onSkipLiveToggle()" />
                    <span class="dy-tip" data-tip="检测到推荐流中的直播卡片时，自动静音暂停并切到下一个">跳过直播卡片</span>
                </label>
                <span class="dy-status-badge" :class="s.skipLive_Switch ? 'dy-status-on' : 'dy-status-off'">
                    {{ s.skipLive_Switch ? '✅ 已开启' : '❌ 已关闭' }}
                </span>
            </div>
        <div class="dy-menu-row">
            <label><input type="checkbox" v-model="s.skipVideoRegex_Switch" @change="onSkipVideoRegexToggle()" />
                <span class="dy-tip" data-tip="匹配视频的标题、作者昵称、视频信息区域文本，命中则静音暂停并切到下一个">正则屏蔽视频</span>
            </label>
            <span class="dy-status-badge" :class="s.skipVideoRegex_Switch ? 'dy-status-on' : 'dy-status-off'">
                {{ s.skipVideoRegex_Switch ? '✅ 已开启' : '❌ 已关闭' }}
            </span>
        </div>
        <div class="dy-menu-row">
            <input type="text" placeholder="输入屏蔽规则，逗号分隔" v-model="temp.skipVideoInput" />
            <button @click="addItem('skipVideoRegex_Array', temp.skipVideoInput)">添加</button>
        </div>
            <div class="dy-tag-list">
                <span class="dy-tag" v-for="(v,i) in s.skipVideoRegex_Array" :key="i">
                    <span class="dy-tag-mode" :class="v.useRegex ? 'regex' : 'keyword'"
                          @click="toggleItemMode('skipVideoRegex_Array', i)"
                          :title="v.useRegex ? '当前：正则模式（点击切换为关键词）' : '当前：关键词模式（点击切换为正则）'">{{ v.useRegex ? '.*' : 'Aa' }}</span>
                    {{ displayText(v,i) }}
                    <button @click="removeItem('skipVideoRegex_Array', i)">×</button>
                </span>
            </div>
            <div class="dy-menu-row" style="margin-top:6px;">
                <label style="width:130px;"><span class="dy-tip" data-tip="兜底轮询间隔。事件驱动为主，这个只是兜底，越小越灵敏但越耗CPU">检测间隔</span></label>
                <input type="number" v-model.number="s.skipVideoPolling" min="0.2" max="5.0" step="0.1" style="width:80px;" @change="restartSkipTimer()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">秒</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="同一张卡片（视频）被检测一次后，多久内不重复检测。太短会重复打遮罩，太长会漏检">同卡片冷却</span></label>
                <input type="number" v-model.number="s.skipVideoCooldown" min="500" max="10000" step="500" style="width:80px;" @change="immediateSave()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">毫秒</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="切换指令发出后，中心卡片多久没变就重试一次切换">重试间隔</span></label>
                <input type="number" v-model.number="s.skipVideoRetryInterval" min="500" max="5000" step="100" style="width:80px;" @change="immediateSave()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">毫秒</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="重试超过这个时间就放弃，避免无限重试。超过后遮罩淡出">重试截止</span></label>
                <input type="number" v-model.number="s.skipVideoRetryTimeout" min="3000" max="20000" step="500" style="width:80px;" @change="immediateSave()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">毫秒</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="点击切换按钮后多少毫秒内卡片没变，就改用键盘 ArrowDown 兜底">键盘兜底延迟</span></label>
                <input type="number" v-model.number="s.skipVideoBtnFallbackDelay" min="100" max="2000" step="50" style="width:80px;" @change="immediateSave()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">毫秒</span>
            </div>
            <div class="dy-menu-row" style="align-items:center;gap:4px 14px;margin-top:4px;">
                <label><input type="checkbox" v-model="s.skipVideoRegex_UseRegular" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="勾选后，新添加的规则默认按正则表达式处理。每条规则仍可在下方标签上单独切换模式">新增规则默认正则</span>
                </label>
                <label><input type="checkbox" v-model="s.skipVideoMask_Switch" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="切换瞬间加一层黑色遮罩，避免闪现画面">切换遮罩</span>
                </label>
            </div>
            <div style="font-size:11px;color:rgba(255,255,255,0.35);margin-top:2px;">
                （点击标签上的 Aa / .* 可单独切换每条规则的模式）
            </div>
            <div style="font-size:12px;color:rgba(255,255,255,0.3);margin-top:4px;">
                匹配范围：视频标题 / 作者昵称 / 视频信息区域
                <span v-if="s.skipVideoRegex_Switch && s.skipVideoRegex_Array.length === 0" style="color:#FF6B6B;display:block;margin-top:2px;">⚠️ 已开启但未添加屏蔽规则</span>
            </div>
        </div>
    </div>

    <!-- ========== ⚙️ 功能开关 ========== -->
    <div class="dy-menu-section">
        <div class="section-title">⚙️ 功能开关（实时生效）</div>
        <div class="dy-menu-row">
            <label><input type="checkbox" v-model="s.enableQualitySwitch" @change="onQualityToggle()" />
                <span class="dy-tip" data-tip="自动切换到可用的最高画质（原画/蓝光/超清等）">智能画质切换</span>
            </label>
            <label><input type="checkbox" v-model="s.enablePayHide" @change="onPayHideToggle()" />
                <span class="dy-tip" data-tip="隐藏直播间的礼物面板 / 付费面板">隐藏礼物面板</span>
            </label>
            <label><input type="checkbox" v-model="s.enableMirror" @change="onMirrorToggle()" />
                <span class="dy-tip" data-tip="将视频画面水平翻转（镜像）显示">视频镜像</span>
            </label>
        </div>
        <div class="dy-menu-row">
            <label><input type="checkbox" v-model="s.enableGiftFilter" @change="onGiftFilterToggle()" />
                <span class="dy-tip" data-tip="过滤直播间中的礼物赠送类聊天消息">礼物消息过滤</span>
            </label>
            <label><input type="checkbox" v-model="s.enableDOMClean" @change="immediateSave()" />
                <span class="dy-tip" data-tip="定期清理已离开视野很远的视频卡片，降低内存占用（直播页不生效）">DOM清理</span>
            </label>
            <label><input type="checkbox" v-model="s.hideNonVideoElements_Switch" @change="immediateSave()" />
                <span class="dy-tip" data-tip="隐藏广告、推广卡片、直播/游戏推荐等非视频元素">隐藏非视频元素</span>
            </label>
        </div>
        <div class="dy-menu-row">
            <label style="width:110px;flex:0 0 110px;"><span class="dy-tip" data-tip="手动设置视频播放速度（0.1~3.0 倍）">⚡ 自定义倍速</span></label>
            <input type="number" v-model.number="s.customPlaybackRate" min="0.1" max="3.0" step="0.1" style="width:70px;flex:0 0 70px;" />
            <button @click="applyCustomRate()">应用</button>
        </div>
    </div>

    <!-- ========== 🛡️ 全局防暂停 ========== -->
    <div class="dy-menu-section">
        <div class="section-title">🛡️ 全局防暂停</div>
        <div class="dy-menu-row" style="align-items:center;">
            <label><input type="checkbox" v-model="s.enableKeepAlive" @change="onKeepAliveToggle()" />
                <span class="dy-tip" data-tip="总开关：关闭后所有防暂停子模块均失效，但下方子选项的配置会保留">全局防暂停</span>
            </label>
            <span class="dy-status-badge" :class="s.enableKeepAlive ? 'dy-status-on' : 'dy-status-off'">
                {{ s.enableKeepAlive ? '✅ 运行中' : '❌ 已关闭' }}
            </span>
            <button class="dy-toggle-btn"
                    @click="pauseGuardExpanded = !pauseGuardExpanded"
                    style="font-size:12px;padding:0 10px;line-height:22px;margin-left:auto;">
                {{ pauseGuardExpanded ? '收起 ▲' : '子选项 ▼' }}
            </button>
        </div>

        <div v-if="pauseGuardExpanded"
             class="dy-advanced-content"
             :style="{ opacity: s.enableKeepAlive ? 1 : 0.45 }">
            <div style="font-size:12px;color:rgba(255,255,255,0.4);margin-bottom:6px;">
                防暂停子模块
                <span v-if="!s.enableKeepAlive" style="color:#FF9800;">（总开关关闭中，这些设置暂不生效）</span>
            </div>
            <div class="dy-menu-row" style="flex-wrap:wrap;gap:4px 12px;margin-bottom:6px;">
                <label><input type="checkbox" v-model="s.pauseGuard_visibilitySpoof" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="把 document.hidden / visibilityState 伪装成“前台可见”，骗过播放器的可见性检测">可见性伪装</span>
                </label>
                <label><input type="checkbox" v-model="s.pauseGuard_eventBlocking" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="拦截 document/window 上的 visibilitychange 事件，让页面收不到切后台通知">事件拦截</span>
                </label>
                <label><input type="checkbox" v-model="s.pauseGuard_rafReplacement" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="把 requestAnimationFrame 换成 setTimeout，避免后台 rAF 被浏览器限速导致播放器卡死。副作用：后台略耗CPU">rAF 替换</span>
                </label>
                <label><input type="checkbox" v-model="s.pauseGuard_mouseSimulation" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="后台时定期派发假的 mousemove 事件，防止“长时间无操作”弹窗">鼠标模拟</span>
                </label>
                <label><input type="checkbox" v-model="s.pauseGuard_popupClick" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="自动检测并点击“继续播放/继续观看/恢复播放”按钮">弹窗点击</span>
                </label>
                <label><input type="checkbox" v-model="s.pauseGuard_backgroundResume" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="后台时若视频被暂停，自动静音并强制播放；回前台恢复音量">后台静音恢复</span>
                </label>
            </div>

            <div style="border-top:1px solid rgba(255,255,255,0.08);padding-top:6px;margin-top:4px;">
                <div class="dy-menu-row">
                    <span style="font-size:12px;color:rgba(255,255,255,0.5);">⏱️ 时间参数</span>
                    <button class="dy-toggle-btn" @click="pauseGuardTimeExpanded = !pauseGuardTimeExpanded"
                            style="font-size:11px;padding:0 8px;line-height:18px;margin-left:auto;">
                        {{ pauseGuardTimeExpanded ? '收起 ▲' : '展开 ▼' }}
                    </button>
                </div>
                <div v-if="pauseGuardTimeExpanded" style="margin-top:4px;">
                    <div class="dy-menu-row">
                        <label style="width:150px;"><span class="dy-tip" data-tip="每隔多少秒派发一次假的鼠标移动事件">鼠标模拟间隔</span></label>
                        <input type="number" v-model.number="s.pauseGuard_mouseSimInterval" min="10" max="300" step="5" style="width:70px;" @change="restartPauseGuardTimers()" />
                        <span style="font-size:12px;color:rgba(255,255,255,0.4);">秒</span>
                    </div>
                    <div class="dy-menu-row">
                        <label style="width:150px;"><span class="dy-tip" data-tip="后台时每隔多少秒检查一次被暂停的视频并尝试恢复">后台恢复检查</span></label>
                        <input type="number" v-model.number="s.pauseGuard_backgroundResumeInterval" min="5" max="120" step="5" style="width:70px;" @change="restartPauseGuardTimers()" />
                        <span style="font-size:12px;color:rgba(255,255,255,0.4);">秒</span>
                    </div>
                    <div class="dy-menu-row">
                        <label style="width:150px;"><span class="dy-tip" data-tip="检测前台↔后台切换的轮询频率，越小越灵敏但越耗CPU">前后台状态检测</span></label>
                        <input type="number" v-model.number="s.pauseGuard_visibilityCheckInterval" min="1" max="15" step="0.5" style="width:70px;" @change="restartPauseGuardTimers()" />
                        <span style="font-size:12px;color:rgba(255,255,255,0.4);">秒</span>
                    </div>
                    <div class="dy-menu-row">
                        <label style="width:150px;"><span class="dy-tip" data-tip="扫描页面上是否存在暂停弹窗的频率">弹窗点击扫描间隔</span></label>
                        <input type="number" v-model.number="s.pauseGuard_popupClickInterval" min="0.5" max="10" step="0.5" style="width:70px;" @change="restartPauseGuardTimers()" />
                        <span style="font-size:12px;color:rgba(255,255,255,0.4);">秒</span>
                    </div>
                    <div class="dy-menu-row">
                        <label style="width:150px;"><span class="dy-tip" data-tip="同一个按钮多久内不会重复点击（防连点）">弹窗点击去重</span></label>
                        <input type="number" v-model.number="s.pauseGuard_popupClickDedupe" min="0.5" max="5" step="0.1" style="width:70px;" @change="restartPauseGuardTimers()" />
                        <span style="font-size:12px;color:rgba(255,255,255,0.4);">秒</span>
                    </div>
                    <div class="dy-menu-row">
                        <label style="width:150px;"><span class="dy-tip" data-tip="DOM 变化后多久检查一次暂停弹窗（越小越灵敏，越大越省CPU）">DOM 变化防抖</span></label>
                        <input type="number" v-model.number="s.pauseGuard_domMutationDebounce" min="100" max="2000" step="50" style="width:70px;" @change="restartPauseGuardTimers()" />
                        <span style="font-size:12px;color:rgba(255,255,255,0.4);">毫秒</span>
                    </div>
                </div>
            </div>
        </div>
    </div>

    <!-- ========== ⏱️ 基础时间参数（默认折叠）========== -->
    <div class="dy-menu-section">
        <div class="section-title dy-pool-header">
            <span class="dy-pool-title">⏱️ 基础时间参数</span>
            <button class="dy-toggle-btn" @click="basicTimeExpanded = !basicTimeExpanded"
                    style="font-size:12px;padding:0 10px;line-height:22px;">
                {{ basicTimeExpanded ? '收起 ▲' : '展开 ▼' }}
            </button>
        </div>
        <div v-show="basicTimeExpanded">
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="用户点击/按键后，多少秒内不自动操作（避免打断你手动选择）">🛡️ 交互锁延迟</span></label>
                <input type="number" v-model.number="s.interactionLockDelay" min="0.5" max="10.0" step="0.5" style="width:80px;" @change="onLockDelayChange()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">秒</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="每隔多少秒扫描一次画质并尝试切换">🎬 画质切换</span></label>
                <input type="number" v-model.number="s.pollingQuality" min="0.5" max="10.0" step="0.5" style="width:80px;" @change="restartQualityTimer()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">秒</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="每隔多少秒扫描一次新弹幕。越小越实时但越耗 CPU">💬 弹幕过滤</span></label>
                <input type="number" v-model.number="s.pollingDanmu" min="0.2" max="5.0" step="0.1" style="width:80px;" @change="restartDanmuTimer()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">秒</span>
                <span style="color:rgba(255,255,255,0.2);font-size:11px;margin-left:4px;">⚠️ 越频繁越耗CPU</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="每隔多少秒尝试清理一次已滚出视野的 DOM 卡片">🧹 DOM清理</span></label>
                <input type="number" v-model.number="s.pollingClean" min="5.0" max="60.0" step="1.0" style="width:80px;" @change="restartCleanTimer()" />
                <span style="color:rgba(255,255,255,0.4);font-size:12px;">秒</span>
            </div>
        </div>
    </div>

    <!-- ========== 💌 消息悬浮窗 ========== -->
    <div class="dy-menu-section">
        <div class="section-title">💌 消息悬浮窗</div>
        <div class="dy-menu-row">
            <label><input type="checkbox" v-model="s.enableMsgFloat" @change="immediateSave()" />
                <span class="dy-tip" data-tip="开启后，鼠标移到顶部「消息」按钮上会浮出联系人列表">启用悬浮窗</span>
            </label>
            <label><input type="checkbox" v-model="s.enableMsgFloatHover" @change="immediateSave()" />
                <span class="dy-tip" data-tip="关闭后需点击「消息」按钮才会浮出；开启时鼠标悬停即可显示">悬停触发</span>
            </label>
            <button @click="resetContacts()" style="background:#FF9800;border:none;color:#fff;padding:0 12px;border-radius:4px;cursor:pointer;">重置联系人</button>
        </div>
    </div>

    <!-- ========== 🔧 通用 ========== -->
    <div class="dy-menu-section">
        <div class="section-title">🔧 通用</div>
        <div class="dy-menu-row">
            <label><input type="checkbox" v-model="s.consoleOutputLog_Switch" @change="immediateSave()" />
                <span class="dy-tip" data-tip="在浏览器控制台输出运行日志（一般用户无需开启）">控制台日志</span>
            </label>
            <label><input type="checkbox" v-model="s.debugMode" @change="onDebugToggle()" />
                <span class="dy-tip" data-tip="全功能调试模式：输出每个模块的详细执行日志、定时器状态、事件时间线。适合排查问题">🔬 全功能调试</span>
            </label>
            <label><input type="checkbox" v-model="s.hideBlockedWordsInMenu_Switch" @change="immediateSave()" />
                <span class="dy-tip" data-tip="在菜单里用「词1、词2」代替真实屏蔽词，避免被截图泄露">隐藏菜单屏蔽词</span>
            </label>
        </div>
        <div class="dy-menu-row" style="margin-top:8px;padding-top:8px;border-top:1px solid rgba(255,255,255,0.08);">
            <button @click="exportConfig()" style="flex:1;min-width:80px;">📤 导出配置</button>
            <button @click="importConfig()" style="flex:1;min-width:80px;">📥 导入配置</button>
        </div>
        <div class="dy-menu-row" style="margin-top:4px;">
            <button @click="runSelfCheckFromMenu()" style="flex:1;min-width:80px;background:#4CAF50;">🔍 自检</button>
            <button class="dy-danger-btn" @click="resetDefaults()" style="flex:1;min-width:80px;">↺ 恢复默认</button>
        </div>
    </div>

    <!-- ========== 🔬 高级设置（折叠区）========== -->
    <div class="dy-menu-section" style="border-left:3px solid rgba(255,152,0,0.6);">
        <div class="section-title dy-pool-header">
            <span class="dy-pool-title">🔬 高级设置
                <span class="dy-tip" data-tip="这里都是不常用的调优参数。误改可能导致功能异常，建议保持默认" style="margin-left:6px;">?</span>
            </span>
            <button class="dy-toggle-btn" @click="advancedExpanded = !advancedExpanded"
                    style="font-size:12px;padding:0 10px;line-height:22px;background:#FF9800;">
                {{ advancedExpanded ? '收起 ▲' : '展开 ▼' }}
            </button>
        </div>

        <div v-if="advancedExpanded" class="dy-advanced-content">

            <!-- 键盘快捷键 -->
            <div class="dy-menu-row">
                <label><input type="checkbox" v-model="s.enableKeyboardShortcuts" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="启用键盘快捷键开关（关闭后下面的键位设置不生效）">⌨️ 键盘快捷键</span>
                </label>
            </div>
            <div class="dy-menu-row" :style="{ opacity: s.enableKeyboardShortcuts ? 1 : 0.4 }">
                <label style="width:120px;flex:0 0 120px;">切换礼物面板</label>
                <input type="text" v-model="s.keyTogglePayHide" maxlength="8"
                       style="width:60px;flex:0 0 60px;text-align:center;"
                       @keydown="captureKey($event, 'keyTogglePayHide')"
                       @focus="$event.target.select()"
                       :disabled="!s.enableKeyboardShortcuts" />
            </div>
            <div class="dy-menu-row" :style="{ opacity: s.enableKeyboardShortcuts ? 1 : 0.4 }">
                <label style="width:120px;flex:0 0 120px;">切换礼物过滤</label>
                <input type="text" v-model="s.keyToggleGiftFilter" maxlength="8"
                       style="width:60px;flex:0 0 60px;text-align:center;"
                       @keydown="captureKey($event, 'keyToggleGiftFilter')"
                       @focus="$event.target.select()"
                       :disabled="!s.enableKeyboardShortcuts" />
            </div>
            <div class="dy-menu-row" :style="{ opacity: s.enableKeyboardShortcuts ? 1 : 0.4 }">
                <label style="width:120px;flex:0 0 120px;">切换视频镜像</label>
                <input type="text" v-model="s.keyToggleMirror" maxlength="8"
                       style="width:60px;flex:0 0 60px;text-align:center;"
                       @keydown="captureKey($event, 'keyToggleMirror')"
                       @focus="$event.target.select()"
                       :disabled="!s.enableKeyboardShortcuts" />
                <span style="color:rgba(255,255,255,0.3);font-size:11px;margin-left:8px;">点击输入框后按任意键设定</span>
            </div>

            <!-- 导航栏按钮 -->
            <div class="dy-menu-row" style="border-top:1px solid rgba(255,255,255,0.06);padding-top:6px;margin-top:6px;">
                <label><input type="checkbox" v-model="s.enableNavButton" @change="onNavButtonToggle()" />
                    <span class="dy-tip" data-tip="在抖音导航栏注入「脚本菜单」按钮，方便随时打开设置面板。关闭后需从油猴菜单打开">导航栏「脚本菜单」按钮</span>
                </label>
            </div>

            <!-- 池子 -->
            <div class="dy-menu-row" style="border-top:1px solid rgba(255,255,255,0.06);padding-top:6px;margin-top:6px;">
                <span style="font-size:12px;color:rgba(255,255,255,0.5);width:100%;">📊 池子</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="菜单里最多显示多少条弹幕/屏蔽记录（内存里最多保留 500 条）">池子显示上限</span></label>
                <input type="number" v-model.number="s.poolSizeLimit" min="10" max="500" step="5" style="width:70px;" @change="onPoolSizeChange()" />
                <span style="font-size:12px;color:rgba(255,255,255,0.4);">条</span>
            </div>

            <!-- DOM 清理参数 -->
            <div class="dy-menu-row" style="border-top:1px solid rgba(255,255,255,0.06);padding-top:6px;margin-top:6px;">
                <span style="font-size:12px;color:rgba(255,255,255,0.5);width:100%;">🧹 DOM 清理参数</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="页面上的视频卡片少于这个数量时，跳过 DOM 清理">最少卡片阈值</span></label>
                <input type="number" v-model.number="s.domClean_minCardThreshold" min="3" max="50" step="1" style="width:70px;" @change="immediateSave()" />
                <span style="font-size:12px;color:rgba(255,255,255,0.4);">张</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="以视窗中心为基准，保留前后各 N 张卡片">保留中心附近</span></label>
                <input type="number" v-model.number="s.domClean_keepAround" min="1" max="10" step="1" style="width:70px;" @change="immediateSave()" />
                <span style="font-size:12px;color:rgba(255,255,255,0.4);">±张</span>
            </div>
            <div class="dy-menu-row">
                <label style="width:130px;"><span class="dy-tip" data-tip="每次轮询触发时，只有此概率会真的执行清理（降低CPU占用）">触发概率</span></label>
                <input type="number" v-model.number="s.domClean_triggerProbability" min="5" max="100" step="5" style="width:70px;" @change="immediateSave()" />
                <span style="font-size:12px;color:rgba(255,255,255,0.4);">%</span>
            </div>

            <!-- 开屏封面 -->
            <div class="dy-menu-row" style="border-top:1px solid rgba(255,255,255,0.06);padding-top:6px;margin-top:6px;">
                <label><input type="checkbox" v-model="s.enableSplashScreen" @change="immediateSave()" />
                    <span class="dy-tip" data-tip="开启后在每次会话首次进入页面时显示一个开屏动画（下次刷新生效）">🎬 开屏封面</span>
                </label>
            </div>
            <div class="dy-menu-row" :style="{ opacity: s.enableSplashScreen ? 1 : 0.4 }">
                <label style="width:130px;"><span class="dy-tip" data-tip="封面停留的时间">显示时长</span></label>
                <input type="number" v-model.number="s.splashDuration" min="300" max="5000" step="100" style="width:70px;" @change="immediateSave()" :disabled="!s.enableSplashScreen" />
                <span style="font-size:12px;color:rgba(255,255,255,0.4);">毫秒</span>
            </div>
            <div class="dy-menu-row" :style="{ opacity: s.enableSplashScreen ? 1 : 0.4 }">
                <label style="width:130px;"><span class="dy-tip" data-tip="淡出动画时长">淡出时长</span></label>
                <input type="number" v-model.number="s.splashFadeoutMs" min="100" max="3000" step="100" style="width:70px;" @change="immediateSave()" :disabled="!s.enableSplashScreen" />
                <span style="font-size:12px;color:rgba(255,255,255,0.4);">毫秒</span>
            </div>
            <div class="dy-menu-row" :style="{ opacity: s.enableSplashScreen ? 1 : 0.4 }">
                <label style="width:130px;"><span class="dy-tip" data-tip="封面背景色（CSS 颜色值）">背景色</span></label>
                <input type="text" v-model="s.splashBgColor" style="width:100px;" @change="immediateSave()" :disabled="!s.enableSplashScreen" />
            </div>
            <div class="dy-menu-row" :style="{ opacity: s.enableSplashScreen ? 1 : 0.4 }">
                <label style="width:130px;"><span class="dy-tip" data-tip="封面下方显示的副标题">副标题</span></label>
                <input type="text" v-model="s.splashSlogan" style="width:180px;" @change="immediateSave()" :disabled="!s.enableSplashScreen" />
            </div>

            <!-- 数据管理 -->
            <div class="dy-menu-row" style="border-top:1px solid rgba(255,255,255,0.06);padding-top:6px;margin-top:6px;">
                <span style="font-size:12px;color:rgba(255,255,255,0.5);width:100%;">💾 数据管理</span>
            </div>
            <div class="dy-menu-row">
                <button @click="clearDanmuPools()" style="background:#d32f2f;">清空弹幕/屏蔽池</button>
                <button @click="clearAllStorage()" style="background:#d32f2f;">清空全部存储</button>
            </div>

        </div>
    </div>

    <div id="dyMenuPrompt" :style="{ opacity: promptOpacity }">{{ promptText }}</div>
</div>
`;

// =========================================================
//                         Vue 菜单
// =========================================================
let menuApp = null;
let danmuSyncTimer = null;

function createMenu() {
    if (document.getElementById("dyMenuUi")) {
        document.getElementById("dyMenuUi").style.display = '';
        if (!danmuSyncTimer) startDanmuPoolSync();
        return;
    }
    const container = document.createElement("div");
    container.innerHTML = menuHTML;
    document.body.appendChild(container);
    unsafeWindow.Vue = Vue;
    const { createApp, reactive, toRaw, ref, onMounted, onUnmounted, nextTick } = Vue;

    menuApp = createApp({
        setup() {
            const s = reactive({});
            const temp = reactive({ danmuInput: '', skipVideoInput: '' });
            const promptText = ref('');
            const promptOpacity = ref(0);
            const danmuList = ref([]);
            const blockedList = ref([]);
            const blockedPoolExpanded = ref(false);
            let promptTimer = null;
            const danmuPoolContainer = ref(null);
            const blockedPoolContainer = ref(null);
            const following = ref(false);
            const pauseGuardExpanded = ref(false);
            const pauseGuardTimeExpanded = ref(false);
            const advancedExpanded = ref(false);
            const showRegexTest = ref(false);

            /* ★ 新增：折叠状态 */
            const poolsAreaExpanded = ref(true);          // 弹幕池+屏蔽池合并折叠（默认展开）
            const danmuListExpanded = ref(true);          // 弹幕池内列表折叠（默认展开）
            const showDanmuBlockedSettings = ref(true);   // 弹幕关键词屏蔽设置（默认展开）
            const skipVideoExpanded = ref(false);         // 跳过直播/正则屏蔽视频（默认折叠）
            const basicTimeExpanded = ref(false);         // 基础时间参数（默认折叠）
            const regexTestText = ref('');
            const regexTestResult = ref([]);

            function showPrompt(msg) { promptText.value = msg; promptOpacity.value = 1; if (promptTimer) clearTimeout(promptTimer); promptTimer = setTimeout(() => { promptOpacity.value = 0; }, 1500); }

            function immediateSave() {
                const raw = toRaw(s);
                for (const key in raw) settings[key] = raw[key];
                GM_setValue('DY_Settings', settings);
                PauseGuard.setEnabled(settings.enableKeepAlive !== false);
            }

            function applyCustomRate() {
                const rate = s.customPlaybackRate;
                if (rate < 0.1 || rate > 3.0) { showPrompt('倍速范围 0.1~3.0'); return; }
                immediateSave();
                const videos = document.querySelectorAll('video');
                if (videos.length === 0) { showPrompt('未找到视频元素'); return; }
                let applied = 0;
                for (const video of videos) { video.playbackRate = rate; applied++; }
                showPrompt(`倍速已设为 ${rate.toFixed(1)}x（已应用于 ${applied} 个视频）`);
                log('🎯 手动应用倍速:', rate, '到', applied, '个视频');
            }

            function onPoolSizeChange() { immediateSave(); syncPools(); showPrompt(`池子上限已更新为 ${s.poolSizeLimit} 条`); }

            function goToLatest(type) {
                following.value = true;
                const container = type === 'danmu' ? danmuPoolContainer.value : blockedPoolContainer.value;
                if (container) { container.scrollTop = container.scrollHeight; showPrompt(`📌 已开启跟随，滚动到最新${type === 'danmu' ? '弹幕' : '屏蔽记录'}`); } else { showPrompt('⚠️ 容器未加载'); }
                log('跟随已开启');
            }

            function scrollIfFollowing() {
                if (!following.value) return;
                nextTick(() => {
                    if (danmuPoolContainer.value) danmuPoolContainer.value.scrollTop = danmuPoolContainer.value.scrollHeight;
                    if (blockedPoolContainer.value && blockedPoolExpanded.value) blockedPoolContainer.value.scrollTop = blockedPoolContainer.value.scrollHeight;
                });
            }

            function setupWheelListener() {
                nextTick(() => {
                    if (danmuPoolContainer.value) { danmuPoolContainer.value.removeEventListener('wheel', onWheel); danmuPoolContainer.value.addEventListener('wheel', onWheel); }
                    if (blockedPoolContainer.value) { blockedPoolContainer.value.removeEventListener('wheel', onWheel); blockedPoolContainer.value.addEventListener('wheel', onWheel); }
                });
            }

            function onWheel() {
                if (following.value) { following.value = false; showPrompt('🛑 手动滚动，跟随已停止'); log('跟随已停止（用户滚动）'); }
            }

            function syncPools() {
                const limit = Math.max(10, s.poolSizeLimit || 50);
                danmuList.value = danmuPool.slice(-limit);
                blockedList.value = blockedPool.slice(-limit);
                setupWheelListener();
                scrollIfFollowing();
            }

            function onDanmuToggle() {
                const status = s.blockedDanmu_Switch ? '✅ 已开启' : '❌ 已关闭';
                showPrompt(`弹幕屏蔽 ${status}`);
                immediateSave();
                if (s.blockedDanmu_Switch) {
                    // ★ 开启时清空池子，让所有在屏弹幕重新走一遍屏蔽判断
                    danmuPool = [];
                    blockedPool = [];
                    setTimeout(filterDanmu, 200);
                } else {
                    // 关闭时恢复所有已隐藏的弹幕
                    document.querySelectorAll('.dy-danmu-blocked').forEach(el => {
                        el.style.display = '';
                        el.classList.remove('dy-danmu-blocked');
                    });
                    blockedPool = [];
                    filterDanmu();
                }
                runRegexTest();
            }
            function onQualityToggle() { immediateSave(); showPrompt(s.enableQualitySwitch ? '画质切换已开启' : '画质切换已关闭'); }
            function onPayHideToggle() { togglePayHide(); showPrompt(settings.enablePayHide ? '✅ 礼物面板已隐藏' : '❌ 礼物面板已显示'); }
            function onMirrorToggle() { toggleMirror(); showPrompt(settings.enableMirror ? '✅ 镜像已开启' : '❌ 镜像已关闭'); }
            function onGiftFilterToggle() { toggleGiftFilter(); showPrompt(settings.enableGiftFilter ? '✅ 礼物消息过滤已开启' : '❌ 礼物消息过滤已关闭'); }
            function onKeepAliveToggle() {
                immediateSave();
                setupKeepAlive();
                if (!s.enableKeepAlive) pauseGuardExpanded.value = false;
                showPrompt(s.enableKeepAlive ? '🛡️ 全局防暂停已开启' : '🛡️ 全局防暂停已关闭');
            }
            function onLockDelayChange() { immediateSave(); showPrompt(`交互锁延迟已更新为 ${s.interactionLockDelay} 秒`); }
            function restartQualityTimer() { immediateSave(); if (timerQuality) { clearInterval(timerQuality); timerQuality = null; } startQualityTimer(); showPrompt(`画质切换间隔已更新为 ${s.pollingQuality} 秒`); }
            function restartDanmuTimer() { immediateSave(); if (timerDanmu) { clearInterval(timerDanmu); timerDanmu = null; } startDanmuTimer(); showPrompt(`弹幕过滤间隔已更新为 ${s.pollingDanmu} 秒`); }
            function restartCleanTimer() { immediateSave(); if (timerClean) { clearInterval(timerClean); timerClean = null; } startCleanTimer(); showPrompt(`DOM清理间隔已更新为 ${s.pollingClean} 秒`); }
            function restartSkipTimer() { immediateSave(); if (timerSkip) { clearInterval(timerSkip); timerSkip = null; } startSkipTimer(); showPrompt(`跳过检测间隔已更新为 ${s.skipVideoPolling} 秒`); }
            function onSkipLiveToggle() { immediateSave(); showPrompt(s.skipLive_Switch ? '🚫 跳过直播已开启' : '❌ 跳过直播已关闭'); }
            function onSkipVideoRegexToggle() { immediateSave(); showPrompt(s.skipVideoRegex_Switch ? '🚫 正则屏蔽视频已开启' : '❌ 正则屏蔽视频已关闭'); }
            function manualRefresh() { resetPools(); showPrompt('🔄 弹幕池和屏蔽池已重置'); }

            function locateDanmu(id) {
                if (!id) { showPrompt('⚠️ 弹幕ID不存在'); return; }
                const target = document.querySelector(`[data-danmu-id="${id}"]`);
                if (!target) { showPrompt('⚠️ 弹幕已消失'); return; }
                target.scrollIntoView({ behavior: 'smooth', block: 'center' });
                target.classList.add('dy-highlight-danmu');
                setTimeout(() => target.classList.remove('dy-highlight-danmu'), 2000);
                showPrompt(`🎯 已定位弹幕: ${target.textContent.trim().slice(0, 30)}`);
            }

            function deepCopy(src, dst) {
                for (const key in src) {
                    if (typeof src[key] === 'object' && src[key] !== null) {
                        dst[key] = Array.isArray(src[key]) ? [] : {};
                        deepCopy(src[key], dst[key]);
                    } else { dst[key] = src[key]; }
                }
            }

            function refresh() {
                let stored = GM_getValue("DY_Settings", {});
                for (const key in DEFAULT_SETTINGS) { if (!(key in stored)) stored[key] = DEFAULT_SETTINGS[key]; }
                for (const key in stored) settings[key] = stored[key];
                deepCopy(settings, s);
                syncPools();
                PauseGuard.setEnabled(settings.enableKeepAlive !== false);
                pauseGuardExpanded.value = false;
                pauseGuardTimeExpanded.value = false;
                advancedExpanded.value = false;
                /* ★ 折叠状态复位 */
                poolsAreaExpanded.value = true;
                danmuListExpanded.value = true;
                showDanmuBlockedSettings.value = true;
                skipVideoExpanded.value = false;
                basicTimeExpanded.value = false;
                showPrompt('已读取配置');
            }

            function save() {
                const raw = toRaw(s);
                deepCopy(raw, settings);
                GM_setValue('DY_Settings', settings);
                PauseGuard.setEnabled(settings.enableKeepAlive !== false);
                if (settings.blockedDanmu_Switch && settings.blockedDanmu_Array.length === 0) { showPrompt('⚠️ 弹幕屏蔽已开启但未添加屏蔽词！'); }
                else if (settings.blockedDanmu_Switch) { showPrompt('✅ 配置已保存，弹幕屏蔽已开启'); }
                else { showPrompt('💾 配置已保存'); }
                restartAllTimers();
                setTimeout(filterDanmu, 200);
                syncPools();
            }

            function closeMenu() {
                const el = document.getElementById('dyMenuUi');
                if (el) el.style.display = 'none';
                if (danmuSyncTimer) { clearInterval(danmuSyncTimer); danmuSyncTimer = null; }
            }

            function resetDefaults() {
                if (!confirm('⚠️ 确定要恢复所有设置到默认值吗？\n（弹幕屏蔽将关闭，屏蔽词列表清空）')) return;
                for (const key in DEFAULT_SETTINGS) settings[key] = DEFAULT_SETTINGS[key];
                settings.blockedDanmu_Array = [];
                settings.skipVideoRegex_Array = [];
                GM_setValue('DY_Settings', settings);
                deepCopy(settings, s);
                poolsAreaExpanded.value = true;
                danmuListExpanded.value = true;
                showDanmuBlockedSettings.value = true;
                skipVideoExpanded.value = false;
                basicTimeExpanded.value = false;
                danmuPool = []; blockedPool = []; lastVideoContainerId = ''; following.value = false;
                syncPools();
                restartAllTimers();
                applyPayHide(); applyMirror(); setupKeepAlive();
                PauseGuard.setEnabled(settings.enableKeepAlive !== false);
                showPrompt('✅ 已恢复默认设置');
                setTimeout(() => location.reload(), 1500);
            }

            function addItem(key, input) {
                if (!input || !input.trim()) return;
                if (!Array.isArray(s[key])) s[key] = [];
                const defaultRegex = (key === 'blockedDanmu_Array')
                    ? !!s.blockedDanmu_UseRegular
                    : !!s.skipVideoRegex_UseRegular;
                const items = input.split(',').map(v => v.trim()).filter(v => v)
                    .map(text => ({ text, useRegex: defaultRegex }));
                s[key].push(...items);
                if (key === 'blockedDanmu_Array') temp.danmuInput = '';
                if (key === 'skipVideoRegex_Array') temp.skipVideoInput = '';
                immediateSave();
                showPrompt(`已添加 ${items.length} 项（默认${defaultRegex ? '正则' : '关键词'}模式）`);
                if (key === 'blockedDanmu_Array') {
                    if (s.blockedDanmu_Switch) {
                        // ★ 清空池子，让下一轮重新扫描所有在屏弹幕
                        danmuPool = [];
                        blockedPool = [];
                        setTimeout(filterDanmu, 200);
                    }
                    runRegexTest();
                }
            }

            function removeItem(key, index) {
                if (Array.isArray(s[key])) s[key].splice(index, 1);
                immediateSave();
                if (key === 'blockedDanmu_Array') {
                    if (s.blockedDanmu_Switch) {
                        danmuPool = [];
                        blockedPool = [];
                        setTimeout(filterDanmu, 200);
                    }
                    runRegexTest();
                }
            }

            function toggleItemMode(key, index) {
                const arr = s[key];
                if (!Array.isArray(arr) || !arr[index]) return;
                const item = arr[index];
                // 兼容旧数据（字符串）
                if (typeof item === 'string') {
                    arr[index] = { text: item, useRegex: true };
                } else {
                    arr[index] = { text: item.text, useRegex: !item.useRegex };
                }
                immediateSave();
                const newMode = arr[index].useRegex ? '正则' : '关键词';
                showPrompt(`已切换为 ${newMode} 模式`);
                if (key === 'blockedDanmu_Array') {
                    if (s.blockedDanmu_Switch) {
                        danmuPool = [];
                        blockedPool = [];
                        setTimeout(filterDanmu, 200);
                    }
                    runRegexTest();
                }
            }

            function displayText(value, index) {
                if (s.hideBlockedWordsInMenu_Switch) return `词${index + 1}`;
                return typeof value === 'string' ? value : (value && value.text) || '';
            }

            function runRegexTest() {
                const text = regexTestText.value || '';
                const arr = s.blockedDanmu_Array || [];
                const fallbackRegex = s.blockedDanmu_UseRegular;
                const results = [];
                if (!text || arr.length === 0) { regexTestResult.value = results; return; }
                for (const item of arr) {
                    const kw = typeof item === 'string' ? item : item.text;
                    const useRe = typeof item === 'string' ? !!fallbackRegex : item.useRegex !== false;
                    if (!kw) continue;
                    if (useRe) {
                        try {
                            const re = new RegExp(kw, 'i');
                            if (re.test(text)) {
                                const m = text.match(re);
                                results.push({ pattern: kw, valid: true, useRegex: true, reason: m ? `匹配到 "${m[0]}"` : '匹配成功' });
                            }
                        } catch (e) {
                            results.push({ pattern: kw, valid: false, useRegex: true, reason: e.message });
                        }
                    } else {
                        if (text.includes(kw)) {
                            results.push({ pattern: kw, valid: true, useRegex: false, reason: '文本包含该关键词' });
                        }
                    }
                }
                regexTestResult.value = results;
            }

            function captureKey(e, keyName) {
                e.preventDefault();
                e.stopPropagation();
                let key = e.key;
                if (key === 'Shift' || key === 'Control' || key === 'Alt' || key === 'Meta') return;
                if (key === ' ') key = 'Space';
                if (key === 'Escape') {
                    e.target.blur();
                    return;
                }
                s[keyName] = key;
                immediateSave();
                showPrompt(`快捷键已设为：${key}`);
                e.target.blur();
            }

            function onDebugToggle() {
                immediateSave();
                showPrompt(s.debugMode ? '🔬 全功能调试模式已开启（详见控制台）' : '调试模式已关闭');
                if (s.debugMode) {
                    cc('magenta', '=== 🔬 全功能调试模式已启用 ===');
                    cc('magenta', '当前配置:', JSON.parse(JSON.stringify(toRaw(s))));
                    cc('magenta', 'PauseGuard 状态:', {
                        enabled: PauseGuard.isEnabled(),
                        eligibleVideos: PauseGuard.eligibleCount,
                        realHidden: PauseGuard.realHidden(),
                    });
                    cc('magenta', '定时器:', { timerQuality: !!timerQuality, timerDanmu: !!timerDanmu, timerClean: !!timerClean, timerSkip: !!timerSkip });
                }
            }

            function restartPauseGuardTimers() {
                immediateSave();
                if (PauseGuard && typeof PauseGuard.restartTimers === 'function') {
                    PauseGuard.restartTimers();
                    showPrompt('✅ 防暂停定时器已重启');
                }
            }

            function onNavButtonToggle() {
                immediateSave();
                if (s.enableNavButton) {
                    injectScriptMenuButton();
                    showPrompt('导航栏按钮已启用');
                } else {
                    const btn = document.querySelector('.tab-scriptmenu');
                    if (btn) {
                        const wrapper = btn.closest('.kCzNsmN5');
                        if (wrapper) wrapper.remove();
                    }
                    showPrompt('导航栏按钮已移除');
                }
            }

            function clearDanmuPools() {
                if (!confirm('确定要清空弹幕池和屏蔽池吗？')) return;
                danmuPool = [];
                blockedPool = [];
                lastVideoContainerId = '';
                syncPools();
                showPrompt('✅ 池子已清空');
            }

            function clearAllStorage() {
                if (!confirm('⚠️ 确定要清空所有存储吗？（包括联系人缓存、所有设置）\n此操作不可撤销！')) return;
                GM_setValue('DY_Settings', {});
                GM_setValue('dy_contacts_cache', '');
                GM_setValue('dy_user_id', '');
                location.reload();
            }

            function exportConfig() {
                const raw = toRaw(s);
                const payload = {
                    _meta: {
                        version: SCRIPT_VERSION,
                        exportTime: new Date().toISOString(),
                        userAgent: navigator.userAgent,
                    },
                    settings: raw,
                };
                const json = JSON.stringify(payload, null, 2);
                const blob = new Blob([json], { type: 'application/json' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url;
                a.download = `抖音配置_${new Date().toISOString().slice(0,10)}.json`;
                a.click();
                URL.revokeObjectURL(url);
                showPrompt('✅ 导出成功');
            }

            function importConfig() {
                const input = document.createElement('input');
                input.type = 'file';
                input.accept = 'application/json';
                input.onchange = async (e) => {
                    const file = e.target.files[0];
                    if (!file) return;
                    try {
                        const text = await file.text();
                        const parsed = JSON.parse(text);
                        const data = parsed.settings || parsed;
                        for (const key in DEFAULT_SETTINGS) {
                            if (!(key in data)) data[key] = DEFAULT_SETTINGS[key];
                        }
                        // 兼容旧版导入的字符串数组
                        ['blockedDanmu_Array', 'skipVideoRegex_Array'].forEach(k => {
                            if (Array.isArray(data[k])) {
                                const fallback = k === 'blockedDanmu_Array' ? data.blockedDanmu_UseRegular : data.skipVideoRegex_UseRegular;
                                data[k] = data[k].map(item => {
                                    if (typeof item === 'string') return { text: item, useRegex: !!fallback };
                                    if (item && typeof item === 'object' && typeof item.text === 'string') return item;
                                    return null;
                                }).filter(Boolean);
                            }
                        });
                        deepCopy(data, s);
                        // ★ 自动保存
                        const raw = toRaw(s);
                        deepCopy(raw, settings);
                        GM_setValue('DY_Settings', settings);
                        PauseGuard.setEnabled(settings.enableKeepAlive !== false);
                        // 应用视觉/功能变化
                        applyPayHide();
                        applyMirror();
                        setupKeepAlive();
                        restartAllTimers();
                        setTimeout(filterDanmu, 200);
                        showPrompt(`✅ 导入成功（来源：${parsed._meta ? parsed._meta.version : '旧版'}），已自动保存`);
                    } catch (err) {
                        showPrompt('❌ 导入失败: ' + err.message);
                    }
                };
                input.click();
            }

            function resetContacts() {
                if (!confirm('确定要重置所有联系人缓存吗？（刷新后重新加载）')) return;
                contactMap.clear();
                GM_setValue('dy_contacts_cache', '');
                GM_setValue('dy_user_id', '');
                updateFloatWindow();
                showPrompt('✅ 联系人已重置');
            }

            function runSelfCheckFromMenu() {
                showSelfCheckPanel();
            }

            onMounted(() => {
                syncPools();
                if (!danmuSyncTimer) startDanmuPoolSync();
            });
            onUnmounted(() => {
                if (danmuSyncTimer) { clearInterval(danmuSyncTimer); danmuSyncTimer = null; }
                if (danmuPoolContainer.value) danmuPoolContainer.value.removeEventListener('wheel', onWheel);
                if (blockedPoolContainer.value) blockedPoolContainer.value.removeEventListener('wheel', onWheel);
            });

            refresh();
            return {
                s, temp, promptText, promptOpacity,
                danmuList, blockedList, blockedPoolExpanded,
                danmuPoolContainer, blockedPoolContainer,
                following, pauseGuardExpanded, pauseGuardTimeExpanded, advancedExpanded,
                /* ★ 新增折叠状态 */
                poolsAreaExpanded, danmuListExpanded, showDanmuBlockedSettings,
                skipVideoExpanded, basicTimeExpanded,
                showRegexTest, regexTestText, regexTestResult,
                onDanmuToggle, onQualityToggle, onPayHideToggle, onMirrorToggle, onGiftFilterToggle,
                onKeepAliveToggle, onLockDelayChange, onPoolSizeChange,
                onSkipLiveToggle, onSkipVideoRegexToggle,
                restartQualityTimer, restartDanmuTimer, restartCleanTimer, restartSkipTimer,
                restartPauseGuardTimers,
                immediateSave, manualRefresh, goToLatest, locateDanmu, syncPools, resetDefaults,
                refresh, save, closeMenu, addItem, removeItem, displayText, toggleItemMode,
                exportConfig, importConfig,
                scrollIfFollowing,
                applyCustomRate,
                resetContacts,
                runSelfCheckFromMenu,
                runRegexTest, captureKey,
                onDebugToggle, onNavButtonToggle, clearDanmuPools, clearAllStorage,
            };
        }
    });
    menuApp.mount('#dyMenuUi');

    // ★ DPI / 小视口兜底：菜单超出边界时拉回来
    requestAnimationFrame(() => {
        const el = document.getElementById('dyMenuUi');
        if (!el) return;
        const rect = el.getBoundingClientRect();
        if (rect.right > window.innerWidth) el.style.right = '10px';
        if (rect.left < 0) { el.style.left = '10px'; el.style.right = 'auto'; }
        if (rect.bottom > window.innerHeight) {
            el.style.maxHeight = Math.max(200, window.innerHeight - rect.top - 10) + 'px';
        }
    });

    startDanmuPoolSync();
}
GM_registerMenuCommand('🎯 抖音优化', createMenu);

function startDanmuPoolSync() {
    if (danmuSyncTimer) { clearInterval(danmuSyncTimer); danmuSyncTimer = null; }
    const intervalMs = Math.max(200, (settings.pollingDanmu || 0.5) * 1000);
    danmuSyncTimer = setInterval(() => {
        const menuEl = document.getElementById('dyMenuUi');
        if (menuApp && menuEl && menuEl.style.display !== 'none') {
            const vm = menuApp._instance;
            if (vm && vm.proxy && vm.proxy.syncPools) { vm.proxy.syncPools(); }
        }
    }, intervalMs);
}

// =========================================================
//               跳过直播 / 正则屏蔽视频（核心）
// =========================================================
const skipVideoHandled = new WeakMap();  // card → { fp, time }

// ★ 补回缺失函数（上一轮漏加，导致整个跳过功能抛 ReferenceError）
function getSkipCurrentFeedItem() {
    const el = document.elementFromPoint(window.innerWidth / 2, window.innerHeight / 2);
    if (!el) return null;
    return el.closest('[data-e2e="feed-item"]');
}

function getCardFingerprint(card) {
    const video = card.querySelector('video');
    const src = video ? (video.currentSrc || video.src || '') : '';
    if (src) return src;
    return getSkipVideoCardText(card).slice(0, 60);
}

function isSkipLiveCard(card) {
    if (!card) return false;
    // ★ 必须同时命中特定组合，避免普通视频卡片被误判
    const hasLiveAttr = card.querySelector('[data-e2e="feed-live"]');
    const hasLiveSlider = card.querySelector('[data-e2e="live-slider"]');
    const hasLiveLink = card.querySelector('.LiveLinkA');
    if (!hasLiveAttr && !hasLiveSlider && !hasLiveLink) return false;

    // 有上述任一标记，再校验文本
    const text = card.innerText || '';
    // 只有真正显示"直播中"的卡片才算
    return text.includes('直播中') || text.includes('正在直播');
}

// 给一个确定的 card 做检测（事件驱动入口用，不需要屏幕中心判断）
function quickCheckCard(card) {
    if (!card) return;
    if (!settings.skipLive_Switch && !settings.skipVideoRegex_Switch) return;
    if (activeSwitchWatcher) return;

    // ★ 卡片必须真的在屏幕上（覆盖 elementFromPoint 拿不到的边界情况）
    const cardRect = card.getBoundingClientRect();
    if (cardRect.width < 10 || cardRect.height < 10) return;
    if (cardRect.bottom <= 0 || cardRect.top >= window.innerHeight) return;

    const fp = getCardFingerprint(card);
    const now = Date.now();
    const prev = skipVideoHandled.get(card);
    const cooldown = Math.max(500, settings.skipVideoCooldown || 3000);
    if (prev && prev.fp === fp && now - prev.time < cooldown) return;

    let reason = null;
    if (settings.skipLive_Switch && isSkipLiveCard(card)) {
        reason = '🚫 已跳过直播';
    } else if (settings.skipVideoRegex_Switch && !isSkipLiveCard(card) && isVideoBlockedByRegex(card)) {
        const matched = getMatchedVideoRule(card);
        reason = matched ? `🚫 规则命中：${matched}` : '🚫 已屏蔽';
    }
    if (!reason) return;

    log('🚫 命中：', reason);
    skipVideoHandled.set(card, { fp, time: now });
    skipCurrentCard(card, reason);
}

// 给所有 video 挂 play 事件（幂等，只挂一次）
function hookAllVideos() {
    document.querySelectorAll('video').forEach(v => {
        if (v._dyHooked) return;
        v._dyHooked = true;
        const onPlay = () => {
            // ★ 三重过滤：必须真的在播、真的可见、真的在屏幕中央
            if (v.paused) return;
            const r = v.getBoundingClientRect();
            if (r.width < 10 || r.height < 10) return;
            const cx = r.left + r.width / 2;
            const cy = r.top + r.height / 2;
            if (Math.abs(cx - window.innerWidth / 2) > window.innerWidth * 0.4) return;
            if (Math.abs(cy - window.innerHeight / 2) > window.innerHeight * 0.4) return;

            const card = v.closest('[data-e2e="feed-item"]');
            if (!card) return;
            try { quickCheckCard(card); } catch (e) { }
        };
        v.addEventListener('play', onPlay, true);
        v.addEventListener('loadedmetadata', onPlay, true);
    });
}

function getSkipVideoCardText(card) {
    if (!card) return '';
    const parts = [];
    const desc = card.querySelector('[data-e2e="video-desc"]');
    if (desc) parts.push(desc.innerText);
    const nickname = card.querySelector('[data-e2e="feed-video-nickname"]');
    if (nickname) parts.push(nickname.innerText);
    const info = card.querySelector('[data-e2e="video-info"]');
    if (info) parts.push(info.innerText);
    return parts.join('\n');
}

function isVideoBlockedByRegex(card) {
    if (!settings.skipVideoRegex_Switch) return false;
    const arr = settings.skipVideoRegex_Array || [];
    if (arr.length === 0) return false;
    const text = getSkipVideoCardText(card);
    if (!text) return false;
    const fallbackRegex = settings.skipVideoRegex_UseRegular !== false;
    for (const item of arr) {
        if (ruleTestItem(item, text, fallbackRegex)) return true;
    }
    return false;
}

function getMatchedVideoRule(card) {
    if (!settings.skipVideoRegex_Switch) return null;
    const arr = settings.skipVideoRegex_Array || [];
    if (arr.length === 0) return null;
    const text = getSkipVideoCardText(card);
    if (!text) return null;
    const fallbackRegex = settings.skipVideoRegex_UseRegular !== false;
    for (const item of arr) {
        if (ruleTestItem(item, text, fallbackRegex)) return ruleGetText(item);
    }
    return null;
}

// ★ 取当前屏幕可见面积最大的 video 的 rect（遮罩位置用）
function getCurrentVideoRect() {
    const vids = document.querySelectorAll('video');
    let best = null, bestArea = 0;
    for (const v of vids) {
        try {
            const r = v.getBoundingClientRect();
            const visW = Math.max(0, Math.min(r.right, window.innerWidth) - Math.max(r.left, 0));
            const visH = Math.max(0, Math.min(r.bottom, window.innerHeight) - Math.max(r.top, 0));
            const a = visW * visH;
            if (a > bestArea) { bestArea = a; best = r; }
        } catch (e) { }
    }
    return best;
}

// ============ 遮罩状态（跟随视频 + 生命周期绑定） ============
let activeSkipMask = null;
let activeSwitchWatcher = null;
let skipBlockedVideos = new WeakSet();

function blockVideoPlayback(v) {
    try {
        skipBlockedVideos.add(v);
        v.pause();
        if (!v._dyBlockHandler) {
            v._dyBlockHandler = function () {
                if (skipBlockedVideos.has(v)) {
                    try { v.pause(); } catch (e) { }
                }
            };
            v.addEventListener('play', v._dyBlockHandler);
        }
    } catch (e) { }
}

function unblockAllVideos() {
    skipBlockedVideos = new WeakSet();
}

function showSkipMask(reason) {
    // 先清掉旧遮罩和它的定时器
    if (activeSkipMask && activeSkipMask.parentNode) {
        if (activeSkipMask._followTimer) clearInterval(activeSkipMask._followTimer);
        activeSkipMask.remove();
    }

    const mask = document.createElement('div');
    mask.className = 'dy-skip-mask';
    mask.style.cssText = `
        position: fixed;
        z-index: 2147483000;
        pointer-events: none;
        backdrop-filter: blur(22px) saturate(0.6);
        -webkit-backdrop-filter: blur(22px) saturate(0.6);
        background: rgba(0,0,0,0.42);
        display: flex;
        align-items: center;
        justify-content: center;
        color: #fff;
        font-size: 22px;
        font-weight: 600;
        letter-spacing: 4px;
        font-family: 'PingFang SC','Microsoft YaHei',sans-serif;
        text-shadow: 0 2px 16px rgba(0,0,0,0.95), 0 0 4px rgba(0,0,0,0.9);
        opacity: 1;
        transition: opacity 220ms ease;
    `;
    mask.textContent = reason || '🚫 已屏蔽';

    // 初始对齐当前可见最大 video
    const r0 = getCurrentVideoRect();
    if (r0) {
        mask.style.left = r0.left + 'px';
        mask.style.top = r0.top + 'px';
        mask.style.width = r0.width + 'px';
        mask.style.height = r0.height + 'px';
    }

    document.body.appendChild(mask);
    void mask.offsetHeight;

    // ★ 每 20ms 跟随当前可见最大 video 的位置，切换动画里也不会半截
    mask._followTimer = setInterval(() => {
        if (activeSkipMask !== mask) { clearInterval(mask._followTimer); return; }
        const rr = getCurrentVideoRect();
        if (rr) {
            mask.style.left = rr.left + 'px';
            mask.style.top = rr.top + 'px';
            mask.style.width = rr.width + 'px';
            mask.style.height = rr.height + 'px';
        }
    }, 30);

    activeSkipMask = mask;
    return mask;
}

function fadeOutAndRemoveMask() {
    if (!activeSkipMask) return;
    const m = activeSkipMask;
    activeSkipMask = null;
    if (m._followTimer) clearInterval(m._followTimer);
    m.style.opacity = '0';
    setTimeout(() => { if (m.parentNode) m.remove(); }, 240);
}

// 观察跳过后的连锁切换：连续命中 → 保持遮罩；落到正常视频 → 淡出
function startSkipWatch(initialCard) {
    if (activeSwitchWatcher) { activeSwitchWatcher(); activeSwitchWatcher = null; }

    let lastCard = initialCard;
    let lastFp = getCardFingerprint(initialCard);
    const start = Date.now();
    let waitingNormal = false;
    let lastRetryAt = Date.now();
    const retryInterval = Math.max(500, settings.skipVideoRetryInterval || 1500);
    const retryTimeout = Math.max(2000, settings.skipVideoRetryTimeout || 8000);

    const timer = setInterval(() => {
        const now = Date.now();
        const card = getSkipCurrentFeedItem();

        if (!card) {
            if (now - start > 10000) {
                clearInterval(timer);
                activeSwitchWatcher = null;
                unblockAllVideos();
                fadeOutAndRemoveMask();
            }
            return;
        }

        const fp = getCardFingerprint(card);
        const cardChanged = (card !== lastCard) || (fp !== lastFp);

        if (cardChanged) {
            lastCard = card;
            lastFp = fp;
            lastRetryAt = now;

            const isLive = settings.skipLive_Switch && isSkipLiveCard(card);
            const isBlocked = settings.skipVideoRegex_Switch && !isSkipLiveCard(card) && isVideoBlockedByRegex(card);

            if (isLive || isBlocked) {
                const reason = isLive ? '🚫 已跳过直播'
                    : `🚫 规则命中：${getMatchedVideoRule(card) || ''}`;

                card.querySelectorAll('video').forEach(blockVideoPlayback);
                document.querySelectorAll('video').forEach(v => {
                    try { if (!v.paused) blockVideoPlayback(v); } catch (e) { }
                });

                if (activeSkipMask && activeSkipMask.parentNode) {
                    activeSkipMask.textContent = reason;
                }

                skipVideoHandled.set(card, { fp, time: now });
                goToNextFeed();
                return;
            } else {
                unblockAllVideos();
                waitingNormal = true;
                return;
            }
        }

        if (waitingNormal) {
            const v = card.querySelector('video');
            if (v && !v.paused && v.readyState >= 2) {
                clearInterval(timer);
                activeSwitchWatcher = null;
                fadeOutAndRemoveMask();
                return;
            }
        } else {
            // ★ 卡片没变（切换失败或还没加载完）→ 每 1.5 秒重试一次，最多到 8 秒
            if (now - lastRetryAt > retryInterval && now - start < retryTimeout) {
                lastRetryAt = now;
                dlog('跳过', '切换未生效，重试');
                goToNextFeed();
            }
        }

        if (now - start > 10000) {
            clearInterval(timer);
            activeSwitchWatcher = null;
            unblockAllVideos();
            fadeOutAndRemoveMask();
        }
    }, 40);

    activeSwitchWatcher = () => { clearInterval(timer); };
}

function skipCurrentCard(card, reason) {
    if (!card) return;

    // 1. 立即暂停当前卡片 + 页面上所有正在播放的 video（只 pause，不动 muted/volume）
    card.querySelectorAll('video').forEach(blockVideoPlayback);
    document.querySelectorAll('video').forEach(v => {
        try { if (!v.paused) blockVideoPlayback(v); } catch (e) { }
    });

    // 2. 已有遮罩就复用（只改文案），否则新建 —— 从根上杜绝"消失又出现"
    if (activeSkipMask && activeSkipMask.parentNode) {
        activeSkipMask.textContent = reason;
    } else {
        showSkipMask(reason);
    }

    // 3. 触发切换
    goToNextFeed();

    // 4. 开始观察连锁切换
    startSkipWatch(card);
}

function dispatchKeyboardNext() {
    dlog('跳过', '模拟 ArrowDown');
    const ev = new KeyboardEvent('keydown', {
        key: 'ArrowDown', code: 'ArrowDown', keyCode: 40, which: 40,
        bubbles: true, cancelable: true,
    });
    [document, document.body, document.activeElement].forEach(el => {
        if (el && el.dispatchEvent) {
            try { el.dispatchEvent(ev); } catch (e) { }
        }
    });
}

function goToNextFeed() {
    const prevCard = getSkipCurrentFeedItem();
    const nextBtn = document.querySelector('.xgplayer-playswitch-next:not(.disabled)')
        || document.querySelector('[data-e2e="video-switch-next-arrow"]:not(.disabled)');

    if (nextBtn) {
        dlog('跳过', '点击下一个按钮');
        try {
            nextBtn.click();
            ['mousedown', 'mouseup', 'pointerdown', 'pointerup'].forEach(type => {
                try {
                    nextBtn.dispatchEvent(new MouseEvent(type, {
                        bubbles: true, cancelable: true, view: window
                    }));
                } catch (e) { }
            });
        } catch (e) { }

        const fallbackDelay = Math.max(100, settings.skipVideoBtnFallbackDelay || 300);
        setTimeout(() => {
            if (getSkipCurrentFeedItem() === prevCard) {
                dlog('跳过', '按钮点击无效，改用键盘');
                dispatchKeyboardNext();
            }
        }, fallbackDelay);
        return;
    }

    dispatchKeyboardNext();
}

// ============ checkSkipFeed（替换原函数） ============
function checkSkipFeed() {
    if (!settings.skipLive_Switch && !settings.skipVideoRegex_Switch) return;
    // ★ 正在观察切换中，直接返回，避免重复触发导致遮罩重建
    if (activeSwitchWatcher) return;

    const card = getSkipCurrentFeedItem();
    if (!card) return;

    const fp = getCardFingerprint(card);
    const now = Date.now();
    const prev = skipVideoHandled.get(card);
    // 同卡片同指纹冷却（指纹变了立即允许重检）
    const cooldown = Math.max(500, settings.skipVideoCooldown || 3000);
    if (prev && prev.fp === fp && now - prev.time < cooldown) return;

    if (settings.skipLive_Switch && isSkipLiveCard(card)) {
        log('🚫 跳过直播卡片');
        dlog('跳过', '检测到直播卡片');
        skipVideoHandled.set(card, { fp, time: now });
        skipCurrentCard(card, '🚫 已跳过直播');
        return;
    }

    if (settings.skipVideoRegex_Switch && !isSkipLiveCard(card) && isVideoBlockedByRegex(card)) {
        const matched = getMatchedVideoRule(card);
        log('🚫 规则命中，跳过视频：', matched || '');
        dlog('跳过', '规则命中');
        skipVideoHandled.set(card, { fp, time: now });
        skipCurrentCard(card, matched ? `🚫 规则命中：${matched}` : '🚫 已屏蔽');
        return;
    }
}

// =========================================================
//                         核心功能
// =========================================================
function hideNonVideoElements() {
    if (!settings.hideNonVideoElements_Switch) return;
    const selectors = ['.ad-container', '[data-e2e="ad-card"]', '.ad-feed', '.promotion-card', '.recommend-ad', '.live-recommend', '.game-recommend'];
    for (const sel of selectors) {
        document.querySelectorAll(sel).forEach(el => { if (!el.classList.contains('dy-hidden')) { el.classList.add('dy-hidden'); el.style.display = 'none'; } });
    }
}

function cleanOldDOM() {
    if (!settings.enableDOMClean || location.href.includes('live.douyin.com')) return;

    const prob = (settings.domClean_triggerProbability ?? 30) / 100;
    if (Math.random() > prob) return;

    const minThreshold = settings.domClean_minCardThreshold ?? 10;
    const keepAround = settings.domClean_keepAround ?? 3;

    const cards = document.querySelectorAll('[data-e2e="video-card"], .video-card, .feed-item');
    if (cards.length <= minThreshold) return;

    let closestIdx = 0, closestDist = Infinity;
    for (let i = 0; i < cards.length; i++) {
        const r = cards[i].getBoundingClientRect();
        const d = Math.abs(r.top + r.height / 2 - window.innerHeight / 2);
        if (d < closestDist) { closestDist = d; closestIdx = i; }
    }
    const keepSet = new Set();
    for (let i = Math.max(0, closestIdx - keepAround); i < Math.min(cards.length, closestIdx + keepAround + 1); i++) {
        keepSet.add(cards[i]);
    }
    document.querySelectorAll('video:not([paused])').forEach(v => {
        const p = v.closest('[data-e2e="video-card"], .video-card, .feed-item');
        if (p) keepSet.add(p);
    });
    let removed = 0;
    for (const card of cards) {
        if (!keepSet.has(card)) {
            const r = card.getBoundingClientRect();
            if (r.top > window.innerHeight * 1.5 || r.bottom < -window.innerHeight * 0.5) {
                card.remove();
                removed++;
            }
        }
    }
    if (removed > 0) { log('🧹 清理DOM:', removed, '个卡片'); dlog('DOM', '清理', removed, '个卡片'); }
}

// =========================================================
//                       抖音优化功能
// =========================================================
let interactionLock = 0;
function setupInteractionLock() {
    document.addEventListener('click', () => { const delayMs = Math.max(500, (settings.interactionLockDelay || 3) * 1000); interactionLock = Date.now() + delayMs; }, true);
    document.addEventListener('keydown', () => { const delayMs = Math.max(500, (settings.interactionLockDelay || 3) * 1000); interactionLock = Date.now() + delayMs; }, true);
}

function switchToHighestQuality() {
    if (!settings.enableQualitySwitch || Date.now() < interactionLock) return;
    const active = document.activeElement;
    if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.getAttribute('contenteditable') === 'true')) return;
    const isLive = location.href.includes('live.douyin.com/');
    if (isLive) {
        const btn = document.querySelector('[data-e2e="quality"]');
        if (!btn) return;
        const current = btn.textContent.trim();
        const container = document.querySelector('[data-e2e="quality-selector"]');
        if (!container) { btn.click(); return; }
        const options = container.querySelectorAll('.tmNdnn5Q');
        if (options.length === 0) { btn.click(); return; }
        let bestItem = null, bestScore = -1, bestText = '';
        options.forEach(el => {
            let textEl = el.querySelector('.KJ5LucVT');
            if (!textEl) textEl = el.querySelector('div');
            if (!textEl) textEl = el;
            const text = textEl.textContent.trim();
            if (!text || text.includes('登录即享')) return;
            let score = 0;
            if (text === '自动(原画)') score = 9998;
            else if (text.includes('原画') || text.includes('画质')) score = 9999;
            else if (text.includes('蓝光')) score = 3000;
            else if (text.includes('超清')) score = 2000;
            else if (text.includes('高清')) score = 1000;
            else if (text.includes('标清')) score = 500;
            else if (text.includes('流畅')) score = 400;
            else { const nums = text.match(/\d+/g); if (nums) score = parseInt(nums.join('')) || 0; }
            if (score > bestScore) { bestScore = score; bestItem = el; bestText = text; }
        });
        if (bestItem && bestText && !current.includes(bestText) && !(Date.now() < interactionLock)) {
            const clickTarget = bestItem.querySelector('.siW34Cq4') || bestItem;
            cc('white', '🎯 切换画质:', current, '→', bestText);
            dlog('画质', '直播页切换', current, '→', bestText);
            clickTarget.click();
        }
    } else {
        const btn = document.querySelector('.xgplayer-playclarity-setting .btn');
        if (!btn) return;
        const current = btn.textContent.trim();
        const options = document.querySelectorAll('.xgplayer-playclarity-setting .virtual > div');
        if (options.length === 0) return;
        let bestItem = null, bestScore = -1, bestText = '';
        options.forEach(el => {
            const text = el.textContent.trim();
            if (!text || text.includes('登录即享')) return;
            let score = 0;
            const nums = text.match(/\d+/g);
            if (nums) score = parseInt(nums.join('')) || 0;
            if (text.includes('4K')) score = 4000;
            else if (text.includes('1080')) score = 1080;
            else if (text.includes('720')) score = 720;
            else if (text.includes('超清')) score = 2000;
            else if (text.includes('高清')) score = 1000;
            else if (text.includes('流畅')) score = 500;
            if (score > bestScore) { bestScore = score; bestItem = el; bestText = text; }
        });
        if (bestItem && bestText && !current.includes(bestText) && !(Date.now() < interactionLock)) {
            cc('white', '🎯 切换画质:', current, '→', bestText);
            dlog('画质', '视频页切换', current, '→', bestText);
            bestItem.click();
        }
    }
}

function filterGiftMessages() {
    if (!settings.enableGiftFilter) return;
    const items = document.querySelectorAll('.webcast-chatroom___item.webcast-chatroom___item_new:not([dy-filtered])');
    for (const el of items) {
        el.setAttribute('dy-filtered', '1');
        const giftWrapper = el.querySelector('.jViERTHR');
        if (giftWrapper) {
            const text = giftWrapper.textContent;
            if (text.includes('送出了')) { el.style.display = 'none'; log('过滤礼物消息:', text.trim().slice(0, 30)); continue; }
        }
        const fullText = el.textContent;
        const keywords = ['送出', '送给', '为主播加了', '赠送', '打赏', '礼物', '红包', '福袋'];
        if (keywords.some(k => fullText.includes(k))) { el.style.display = 'none'; log('过滤礼物消息(关键词):', fullText.trim().slice(0, 30)); }
    }
}

let payHideStyle = null;
function applyPayHide() {
    if (settings.enablePayHide) {
        if (!payHideStyle) {
            payHideStyle = document.createElement('style');
            payHideStyle.textContent = `div.aqK_4_5U, #BottomLayout, .gift-panel, .pay-panel, [data-e2e="gift-panel"], [data-e2e="pay-panel"] { display: none !important; }`;
            document.head.appendChild(payHideStyle);
        }
    } else {
        if (payHideStyle) { payHideStyle.remove(); payHideStyle = null; }
    }
    if (menuApp && menuApp._instance && menuApp._instance.proxy) {
        const proxy = menuApp._instance.proxy;
        if (proxy.s) { proxy.s.enablePayHide = settings.enablePayHide; }
    }
}
function togglePayHide() { settings.enablePayHide = !settings.enablePayHide; GM_setValue('DY_Settings', settings); applyPayHide(); cc('blue', '💡 礼物面板:', settings.enablePayHide ? '隐藏' : '显示'); }

let mirrorStyle = null;
function applyMirror() {
    if (settings.enableMirror) {
        if (!mirrorStyle) {
            mirrorStyle = document.createElement('style');
            mirrorStyle.textContent = `video { transform: rotateY(180deg) !important; }`;
            document.head.appendChild(mirrorStyle);
        }
    } else {
        if (mirrorStyle) { mirrorStyle.remove(); mirrorStyle = null; }
    }
    if (menuApp && menuApp._instance && menuApp._instance.proxy) {
        const proxy = menuApp._instance.proxy;
        if (proxy.s) { proxy.s.enableMirror = settings.enableMirror; }
    }
}
function toggleMirror() { settings.enableMirror = !settings.enableMirror; GM_setValue('DY_Settings', settings); applyMirror(); cc('blue', '💡 镜像:', settings.enableMirror ? '开启' : '关闭'); }

function toggleGiftFilter() {
    settings.enableGiftFilter = !settings.enableGiftFilter;
    GM_setValue('DY_Settings', settings);
    if (menuApp && menuApp._instance && menuApp._instance.proxy) {
        const proxy = menuApp._instance.proxy;
        if (proxy.s) { proxy.s.enableGiftFilter = settings.enableGiftFilter; }
    }
    const items = document.querySelectorAll('.webcast-chatroom___item.webcast-chatroom___item_new');
    for (const el of items) { el.style.display = ''; el.removeAttribute('dy-filtered'); }
    if (settings.enableGiftFilter) filterGiftMessages();
    cc('blue', '💡 礼物消息过滤:', settings.enableGiftFilter ? '开启' : '关闭');
}

// =========================================================
//      保活（音频保活 + 防暂停模块同步）
// =========================================================
let audioContext = null;
function setupKeepAlive() {
    PauseGuard.setEnabled(settings.enableKeepAlive !== false);

    if (!settings.enableKeepAlive) {
        if (audioContext) { audioContext.close(); audioContext = null; }
        return;
    }
    if (!audioContext) {
        try {
            audioContext = new (window.AudioContext || window.webkitAudioContext)();
            const buf = audioContext.createBuffer(1, 128, audioContext.sampleRate);
            const d = buf.getChannelData(0);
            for (let i = 0; i < 128; i++) d[i] = 0;
            function play() {
                if (!audioContext || audioContext.state === 'closed') return;
                const s = audioContext.createBufferSource();
                s.buffer = buf;
                const g = audioContext.createGain();
                g.gain.value = 0.001;
                s.connect(g);
                g.connect(audioContext.destination);
                s.start();
                s.onended = () => { if (audioContext && audioContext.state !== 'closed') setTimeout(play, 100); };
            }
            const start = () => {
                if (audioContext && audioContext.state === 'suspended') audioContext.resume();
                play();
                document.removeEventListener('click', start);
                document.removeEventListener('keydown', start);
            };
            document.addEventListener('click', start);
            document.addEventListener('keydown', start);
            if (audioContext.state === 'running') play();
        } catch (e) { log('保活音频启动失败:', e); }
    }
}

// =========================================================
//                       弹幕核心
// =========================================================
function isDanmuBlocked(text) {
    if (!settings.blockedDanmu_Switch || !settings.blockedDanmu_Array || settings.blockedDanmu_Array.length === 0) return false;
    const fallbackRegex = settings.blockedDanmu_UseRegular !== false;
    for (const item of settings.blockedDanmu_Array) {
        if (ruleTestItem(item, text, fallbackRegex)) return true;
    }
    return false;
}

function filterDanmu() {
    const danmuElements = document.querySelectorAll('[data-danmu-id]');
    if (danmuElements.length === 0) return;
    dlog('弹幕', '扫描', danmuElements.length, '条');
    for (const el of danmuElements) {
        const id = el.dataset.danmuId;
        if (!id) continue;
        const container = getDanmuContainer(el);
        const containerId = getContainerId(container);
        if (containerId && containerId !== lastVideoContainerId) {
            if (lastVideoContainerId !== '') { insertDivider(); }
            lastVideoContainerId = containerId;
            log('🔀 切换到新视频容器:', containerId);
        }
        const fullTimeStr = getContainerFullTimeStr(container);
        const videoTime = getContainerCurrentTime(container);
        if (danmuPool.some(item => item.id === id)) continue;
        const textEl = el.querySelector('.danMuText span, .danMuText');
        let text = textEl ? textEl.textContent.trim() : el.textContent.trim().slice(0, 50);
        if (!text) text = '(空)';
        danmuPool.push({ id, text, videoTime, fullTimeStr });
        if (danmuPool.length > MAX_POOL_STORAGE) danmuPool = danmuPool.slice(-MAX_POOL_STORAGE);
        if (settings.blockedDanmu_Switch && isDanmuBlocked(text)) {
            el.style.display = 'none';
            el.classList.add('dy-danmu-blocked');
            log('屏蔽弹幕:', text);
            if (!blockedPool.some(p => p.id === id)) {
                blockedPool.push({ id, text, videoTime, fullTimeStr });
                if (blockedPool.length > MAX_POOL_STORAGE) blockedPool = blockedPool.slice(-MAX_POOL_STORAGE);
            }
        }
    }
    if (menuApp) {
        const vm = menuApp._instance;
        if (vm && vm.proxy && vm.proxy.syncPools) vm.proxy.syncPools();
    }
}

// =========================================================
//                     键盘快捷键
// =========================================================
function keydown(event) {
    if (!settings.enableKeyboardShortcuts) return;
    const key = event.key;
    const target = event.target;
    if (target.tagName === 'INPUT' || target.tagName === 'TEXTAREA' || target.isContentEditable) return;

    const kPayHide = settings.keyTogglePayHide || '=';
    const kGift = settings.keyToggleGiftFilter || '*';
    const kMirror = settings.keyToggleMirror || '/';

    if (key === kPayHide) { event.preventDefault(); togglePayHide(); return; }
    if (key === kGift) { event.preventDefault(); toggleGiftFilter(); return; }
    if (key === kMirror) { event.preventDefault(); toggleMirror(); return; }
}

// =========================================================
//                           定时器
// =========================================================
let timerQuality = null, timerDanmu = null, timerClean = null, timerSkip = null;

function startQualityTimer() {
    if (timerQuality) clearInterval(timerQuality);
    const seconds = Math.max(0.5, settings.pollingQuality || 1.5);
    timerQuality = setInterval(switchToHighestQuality, seconds * 1000);
    log('画质轮询已启动（间隔', seconds, '秒）');
}
function startDanmuTimer() {
    if (timerDanmu) clearInterval(timerDanmu);
    const seconds = Math.max(0.2, settings.pollingDanmu || 0.5);
    timerDanmu = setInterval(filterDanmu, seconds * 1000);
    log('弹幕轮询已启动（间隔', seconds, '秒）');
    startDanmuPoolSync();
}
function startCleanTimer() {
    if (timerClean) clearInterval(timerClean);
    const seconds = Math.max(5.0, settings.pollingClean || 10.0);
    timerClean = setInterval(cleanOldDOM, seconds * 1000);
    log('DOM清理轮询已启动（间隔', seconds, '秒）');
}
function startSkipTimer() {
    if (timerSkip) clearInterval(timerSkip);
    const seconds = Math.max(0.3, settings.skipVideoPolling || 0.5);
    timerSkip = setInterval(checkSkipFeed, seconds * 1000);
    hookAllVideos();   // ★ 立即给现有 video 挂事件
    log('跳过检测已启动（事件驱动 + 兜底轮询', seconds, '秒）');
}
function restartAllTimers() {
    if (timerQuality) { clearInterval(timerQuality); timerQuality = null; }
    if (timerDanmu) { clearInterval(timerDanmu); timerDanmu = null; }
    if (timerClean) { clearInterval(timerClean); timerClean = null; }
    if (timerSkip) { clearInterval(timerSkip); timerSkip = null; }
    if (danmuSyncTimer) { clearInterval(danmuSyncTimer); danmuSyncTimer = null; }
    setTimeout(() => { startQualityTimer(); startDanmuTimer(); startCleanTimer(); startSkipTimer(); }, 100);
}

// =========================================================
//              导航栏「脚本菜单」按钮
// =========================================================
function injectScriptMenuButton() {
    if (!settings.enableNavButton) return;
    if (document.querySelector('.tab-scriptmenu')) return;
    const microgameTab = document.querySelector('.tab-microgame');
    if (!microgameTab) return;
    const parentDiv = microgameTab.closest('.kCzNsmN5');
    if (!parentDiv) return;
    const newNav = document.createElement('div');
    newNav.className = 'kCzNsmN5';
    const innerDiv = document.createElement('div');
    const tabDiv = document.createElement('div');
    tabDiv.className = 'tab-scriptmenu ufc6pXB6 tKzDmMqN undefined tQC_YFt3';
    tabDiv.style.cursor = 'pointer';
    const link = document.createElement('a');
    link.className = 'RZuwF26I AbEFhGHq';
    link.href = 'javascript:void(0)';
    link.onclick = function (e) {
        e.preventDefault();
        const menuEl = document.getElementById('dyMenuUi');
        if (menuEl) {
            if (menuEl.style.display === 'none') {
                menuEl.style.display = '';
                if (!danmuSyncTimer) startDanmuPoolSync();
            } else {
                menuEl.style.display = 'none';
                if (danmuSyncTimer) { clearInterval(danmuSyncTimer); danmuSyncTimer = null; }
            }
        } else {
            createMenu();
        }
    };
    const iconDiv = document.createElement('div');
    iconDiv.className = 'b0EMo3Nf';
    iconDiv.style.cssText = 'display:flex; align-items:center; justify-content:center; margin-right:6px;';
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('width', '18');
    svg.setAttribute('height', '18');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('fill', 'none');
    svg.style.display = 'block';
    svg.innerHTML = `<path d="M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.488.488 0 0 0-.59.22L2.74 8.87a.49.49 0 0 0 .12.61l2.03 1.58c-.05.3-.07.62-.07.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.49.49 0 0 0-.12-.61l-2.03-1.58zM12 15.6A3.6 3.6 0 1 1 15.6 12 3.6 3.6 0 0 1 12 15.6z" fill="#fff" fill-opacity="1"/>`;
    iconDiv.appendChild(svg);
    const textSpan = document.createElement('span');
    textSpan.className = 'wiu7QUYe';
    textSpan.textContent = '脚本菜单';
    textSpan.style.fontFamily = '"PingFang SC","Helvetica Neue","Microsoft YaHei",sans-serif';
    const contentDiv = document.createElement('div');
    contentDiv.className = 'j4lZbHMI';
    contentDiv.appendChild(textSpan);
    const extraDiv = document.createElement('div');
    extraDiv.className = 'uAPOqZ9J';
    link.appendChild(iconDiv);
    link.appendChild(contentDiv);
    link.appendChild(extraDiv);
    tabDiv.appendChild(link);
    innerDiv.appendChild(tabDiv);
    newNav.appendChild(innerDiv);
    parentDiv.parentNode.insertBefore(newNav, parentDiv.nextSibling);
    log('✅ 导航栏「脚本菜单」按钮已注入');
}

// =========================================================
//                    获取当前用户ID
// =========================================================
function getCurrentUserId() {
    const cookies = document.cookie.split('; ');
    for (const cookie of cookies) {
        const [key, value] = cookie.split('=');
        if (key === 'uid' || key === 'user_id') return value;
    }
    if (window.USER && window.USER.uid) return window.USER.uid;
    console.warn('⚠️ 无法获取用户ID，账号切换检测将禁用');
    return 'fixed_user_id';
}

// =========================================================
//                     消息悬浮窗
// =========================================================
let msgFloatWindow = null;
let msgFloatContent = null;
let msgFloatVisible = false;
let msgButton = null;
let contactMap = new Map();
let syncTimer = null;
let currentUserId = null;
let isFullLoadComplete = false;

function saveContactsToStorage() {
    const data = Array.from(contactMap.values()).map(c => ({
        name: c.name,
        avatar: c.avatar,
        preview: c.preview,
        time: c.time,
        unread: c.unread,
    }));
    GM_setValue('dy_contacts_cache', JSON.stringify(data));
    if (currentUserId) GM_setValue('dy_user_id', currentUserId);
}

function loadContactsFromStorage() {
    const raw = GM_getValue('dy_contacts_cache');
    const savedUserId = GM_getValue('dy_user_id', '');
    if (currentUserId && savedUserId && currentUserId !== savedUserId && currentUserId !== 'fixed_user_id') {
        console.log('🔄 检测到账号切换，自动重置联系人');
        contactMap.clear();
        GM_setValue('dy_contacts_cache', '');
        updateFloatWindow();
        return;
    }
    if (!raw) return;
    try {
        const data = JSON.parse(raw);
        data.forEach(item => {
            contactMap.set(item.name, { ...item, element: null });
        });
        if (contactMap.size > 0) updateFloatWindow();
    } catch (e) { }
}

function initMessagePreview() {
    currentUserId = getCurrentUserId();
    if (!currentUserId) currentUserId = 'default';

    const btnSel = '[data-e2e="something-button"]';
    function findMsgButton() {
        const all = document.querySelectorAll(btnSel);
        for (const b of all) {
            const label = b.querySelector('.phl13lpd');
            if (label && label.textContent.trim() === '消息') return b;
        }
        return null;
    }
    msgButton = findMsgButton();
    if (!msgButton) return;

    loadContactsFromStorage();
    createFloatWindow();

    const panel = document.getElementById('im-entry-vmok-popup-portal');
    if (panel) {
        isFullLoadComplete = false;
        loadAllContacts();
    } else {
        const observer = new MutationObserver(() => {
            const p = document.getElementById('im-entry-vmok-popup-portal');
            if (p) {
                observer.disconnect();
                isFullLoadComplete = false;
                loadAllContacts();
            }
        });
        observer.observe(document.body, { childList: true, subtree: true });
    }

    let listChangeTimer = null;
    const listObserver = new MutationObserver(() => {
        if (listChangeTimer) clearTimeout(listChangeTimer);
        listChangeTimer = setTimeout(() => {
            if (document.getElementById('im-entry-vmok-popup-portal')) {
                if (!isFullLoadComplete) loadAllContacts();
                else updateContactsInfo();
            }
            listChangeTimer = null;
        }, 500);
    });
    const waitForList = setInterval(() => {
        const list = document.querySelector('#im-entry-vmok-popup-portal .componentsLeftPanelboxList');
        if (list) {
            clearInterval(waitForList);
            listObserver.observe(list, { childList: true, subtree: true, attributes: false });
        }
    }, 1000);

    let hideTimer = null;
    function showFloat() {
        if (!settings.enableMsgFloat) return;
        clearTimeout(hideTimer);
        const rect = msgButton.getBoundingClientRect();
        const floatWidth = 400;
        let left = rect.left + 4;
        if (left + floatWidth > window.innerWidth - 10) left = window.innerWidth - floatWidth - 10;
        if (left < 10) left = 10;
        msgFloatWindow.style.left = left + 'px';
        msgFloatWindow.style.top = (rect.bottom + 8) + 'px';
        msgFloatWindow.style.display = 'block';
        msgFloatWindow.style.width = floatWidth + 'px';
        msgFloatVisible = true;
        requestAnimationFrame(() => {
            msgFloatWindow.style.opacity = '1';
            msgFloatWindow.style.pointerEvents = 'auto';
        });
        updateContactsInfo();
        if (syncTimer) clearInterval(syncTimer);
        syncTimer = setInterval(() => {
            if (msgFloatVisible) updateContactsInfo();
            else { clearInterval(syncTimer); syncTimer = null; }
        }, 2000);
    }
    function hideFloat() {
        hideTimer = setTimeout(() => {
            msgFloatWindow.style.opacity = '0';
            msgFloatWindow.style.pointerEvents = 'none';
            msgFloatVisible = false;
            if (syncTimer) { clearInterval(syncTimer); syncTimer = null; }
            setTimeout(() => {
                if (!msgFloatVisible) msgFloatWindow.style.display = 'none';
            }, 200);
        }, 300);
    }

    // 悬停触发（可关闭）
    msgButton.addEventListener('mouseenter', () => {
        if (settings.enableMsgFloatHover) showFloat();
    });
    msgButton.addEventListener('mouseleave', () => {
        if (settings.enableMsgFloatHover) hideFloat();
    });

    // 点击：悬停模式→隐藏；非悬停模式→切换
    msgButton.addEventListener('click', (e) => {
        if (!settings.enableMsgFloatHover) {
            e.stopPropagation();
            if (msgFloatVisible) hideFloat();
            else showFloat();
        } else {
            hideFloat();
        }
    });

    msgFloatWindow.addEventListener('mouseenter', () => clearTimeout(hideTimer));
    msgFloatWindow.addEventListener('mouseleave', () => {
        if (settings.enableMsgFloatHover) hideFloat();
    });

    document.addEventListener('click', (e) => {
        if (msgFloatVisible && !msgFloatWindow.contains(e.target) && e.target !== msgButton && !msgButton.contains(e.target)) {
            hideFloat();
        }
    });

    window.addEventListener('scroll', () => {
        if (msgFloatVisible) {
            const rect = msgButton.getBoundingClientRect();
            const floatWidth = 400;
            let left = rect.left + 4;
            if (left + floatWidth > window.innerWidth - 10) left = window.innerWidth - floatWidth - 10;
            if (left < 10) left = 10;
            msgFloatWindow.style.left = left + 'px';
            msgFloatWindow.style.top = (rect.bottom + 8) + 'px';
        }
    }, true);
}

function createFloatWindow() {
    if (msgFloatWindow) return;
    msgFloatWindow = document.createElement('div');
    msgFloatWindow.id = 'dy-msg-float-window';
    msgFloatWindow.style.cssText = `
        position: fixed;
        width: 400px;
        max-height: 70vh;
        background: #252632;
        color: #e8e8e8;
        border-radius: 12px;
        box-shadow: 0 8px 24px rgba(0,0,0,0.6);
        border: 1px solid rgba(255,255,255,0.08);
        z-index: 9999;
        display: none;
        overflow-y: auto;
        padding: 6px 0;
        opacity: 0;
        transition: opacity 0.15s ease;
        pointer-events: none;
        right: auto;
        bottom: auto;
        font-family: 'PingFang SC', 'Microsoft YaHei', sans-serif;
        scrollbar-width: thin;
        scrollbar-color: rgba(255,255,255,0.2) transparent;
    `;
    const arrow = document.createElement('div');
    arrow.style.cssText = `
        position: absolute;
        top: -8px;
        left: 30px;
        width: 0;
        height: 0;
        border-left: 8px solid transparent;
        border-right: 8px solid transparent;
        border-bottom: 8px solid #252632;
        filter: drop-shadow(0 -2px 4px rgba(0,0,0,0.3));
    `;
    msgFloatWindow.appendChild(arrow);
    const content = document.createElement('div');
    content.id = 'dy-msg-float-content';
    content.style.cssText = 'padding: 0 6px;';
    msgFloatWindow.appendChild(content);
    document.body.appendChild(msgFloatWindow);
    msgFloatContent = content;

    const styleTag = document.createElement('style');
    styleTag.textContent = `
        #dy-msg-float-content .dy-contact-item {
            display: flex;
            align-items: center;
            padding: 8px 12px;
            border-bottom: 1px solid rgba(255,255,255,0.06);
            cursor: pointer;
            transition: background 0.15s;
        }
        #dy-msg-float-content .dy-contact-item:hover {
            background: rgba(255,255,255,0.08);
        }
        #dy-msg-float-content .dy-avatar {
            width: 44px;
            height: 44px;
            border-radius: 50%;
            background: #3a3b4a;
            flex-shrink: 0;
            margin-right: 12px;
            overflow: hidden;
        }
        #dy-msg-float-content .dy-avatar img {
            width: 100%;
            height: 100%;
            object-fit: cover;
        }
        #dy-msg-float-content .dy-info {
            flex: 1;
            min-width: 0;
        }
        #dy-msg-float-content .dy-name {
            font-size: 14px;
            font-weight: 500;
            color: #e8e8e8;
            display: flex;
            align-items: center;
            gap: 6px;
        }
        #dy-msg-float-content .dy-name .dy-badge {
            background: #ff4757;
            color: #fff;
            font-size: 11px;
            padding: 0 6px;
            border-radius: 10px;
            line-height: 18px;
        }
        #dy-msg-float-content .dy-preview {
            font-size: 13px;
            color: rgba(255,255,255,0.55);
            white-space: nowrap;
            overflow: hidden;
            text-overflow: ellipsis;
            margin-top: 2px;
        }
        #dy-msg-float-content .dy-time {
            font-size: 12px;
            color: rgba(255,255,255,0.35);
            flex-shrink: 0;
            margin-left: 8px;
        }
        #dy-msg-float-content .dy-empty {
            padding: 30px 20px;
            text-align: center;
            color: rgba(255,255,255,0.4);
        }
        #dy-msg-float-window::-webkit-scrollbar { width: 5px; }
        #dy-msg-float-window::-webkit-scrollbar-track { background: transparent; }
        #dy-msg-float-window::-webkit-scrollbar-thumb { background: rgba(255,255,255,0.15); border-radius: 4px; }
        #dy-msg-float-window::-webkit-scrollbar-thumb:hover { background: rgba(255,255,255,0.3); }
    `;
    msgFloatWindow.appendChild(styleTag);
}

function loadAllContacts() {
    const panel = document.getElementById('im-entry-vmok-popup-portal');
    if (!panel) {
        if (contactMap.size > 0) updateFloatWindow();
        return;
    }

    const list = panel.querySelector('.componentsLeftPanelboxList');
    if (!list) return;
    const scrollContainer = list.closest('[style*="overflow"]') || list.parentElement;
    if (!scrollContainer) return;

    if (window._dyLoadingContacts) return;
    window._dyLoadingContacts = true;

    const originalScrollTop = scrollContainer.scrollTop;
    let previousCount = 0;
    let stableCount = 0;
    const maxAttempts = 50;
    let attempts = 0;
    const step = 400;

    if (window._dyLoadTimer) {
        clearInterval(window._dyLoadTimer);
        window._dyLoadTimer = null;
    }

    function extractAndUpdate() {
        const items = list.querySelectorAll('.conversationConversationItemwrapper');
        items.forEach(item => {
            const titleEl = item.querySelector('.conversationConversationItemtitle');
            if (!titleEl) return;
            const name = titleEl.textContent.trim();
            if (!name) return;

            const avatarImg = item.querySelector('.semi-avatar-img img');
            let avatarUrl = '';
            if (avatarImg) avatarUrl = avatarImg.src;
            const previewEl = item.querySelector('.ConversationItemHinttextBox');
            const preview = previewEl ? previewEl.textContent.trim() : '';
            const timeEl = item.querySelector('.ConversationItemTagNextToTitletimeStr');
            const time = timeEl ? timeEl.textContent.trim() : '';
            const badge = item.querySelector('.ConversationItemUnReadCountdigitsNumberPop');
            const unread = badge ? badge.textContent.trim() : '';

            if (contactMap.has(name)) {
                const existing = contactMap.get(name);
                existing.avatar = avatarUrl || existing.avatar;
                existing.preview = preview || existing.preview;
                existing.time = time || existing.time;
                existing.unread = unread || existing.unread;
                existing.element = item;
            } else {
                contactMap.set(name, { name, avatar: avatarUrl, preview, time, unread, element: item });
            }
        });
        saveContactsToStorage();
        return contactMap.size;
    }

    function isAtBottom() {
        return scrollContainer.scrollTop + scrollContainer.clientHeight >= scrollContainer.scrollHeight - 20;
    }

    function doScrollLoad() {
        if (attempts >= maxAttempts || !msgFloatVisible) {
            window._dyLoadingContacts = false;
            if (window._dyLoadTimer) { clearInterval(window._dyLoadTimer); window._dyLoadTimer = null; }
            scrollContainer.scrollTop = originalScrollTop;
            extractAndUpdate();
            updateFloatWindow();
            return;
        }
        attempts++;
        const currentCount = extractAndUpdate();

        if (isAtBottom()) {
            scrollContainer.scrollTop = originalScrollTop;
            window._dyLoadingContacts = false;
            if (window._dyLoadTimer) { clearInterval(window._dyLoadTimer); window._dyLoadTimer = null; }
            extractAndUpdate();
            updateFloatWindow();
            return;
        }

        if (currentCount > previousCount) {
            previousCount = currentCount;
            stableCount = 0;
            scrollContainer.scrollTop += step;
            if (window._dyLoadTimer) { clearInterval(window._dyLoadTimer); window._dyLoadTimer = null; }
            window._dyLoadTimer = setTimeout(doScrollLoad, 250);
        } else {
            stableCount++;
            if (stableCount >= 3) {
                scrollContainer.scrollTop = originalScrollTop;
                window._dyLoadingContacts = false;
                if (window._dyLoadTimer) { clearInterval(window._dyLoadTimer); window._dyLoadTimer = null; }
                extractAndUpdate();
                updateFloatWindow();
                return;
            } else {
                scrollContainer.scrollTop += step;
                if (window._dyLoadTimer) { clearInterval(window._dyLoadTimer); window._dyLoadTimer = null; }
                window._dyLoadTimer = setTimeout(doScrollLoad, 250);
            }
        }
    }

    const initialCount = extractAndUpdate();
    if (initialCount === 0) {
        scrollContainer.scrollTop = 0;
        setTimeout(() => {
            window._dyLoadingContacts = false;
            if (window._dyLoadTimer) { clearInterval(window._dyLoadTimer); window._dyLoadTimer = null; }
            extractAndUpdate();
            updateFloatWindow();
        }, 300);
        return;
    }
    previousCount = initialCount;
    window._dyLoadTimer = setTimeout(doScrollLoad, 200);
}

function updateContactsInfo() {
    const panel = document.getElementById('im-entry-vmok-popup-portal');
    if (!panel) return;
    const list = panel.querySelector('.componentsLeftPanelboxList');
    if (!list) return;

    const items = list.querySelectorAll('.conversationConversationItemwrapper');
    let updated = false;
    items.forEach(item => {
        const titleEl = item.querySelector('.conversationConversationItemtitle');
        if (!titleEl) return;
        const name = titleEl.textContent.trim();
        if (!name) return;
        if (contactMap.has(name)) {
            const existing = contactMap.get(name);
            const avatarImg = item.querySelector('.semi-avatar-img img');
            if (avatarImg) existing.avatar = avatarImg.src || existing.avatar;
            const previewEl = item.querySelector('.ConversationItemHinttextBox');
            if (previewEl) existing.preview = previewEl.textContent.trim() || existing.preview;
            const timeEl = item.querySelector('.ConversationItemTagNextToTitletimeStr');
            if (timeEl) existing.time = timeEl.textContent.trim() || existing.time;
            const badge = item.querySelector('.ConversationItemUnReadCountdigitsNumberPop');
            if (badge) existing.unread = badge.textContent.trim() || existing.unread;
            existing.element = item;
            updated = true;
        }
    });
    if (updated) {
        saveContactsToStorage();
        updateFloatWindow();
    }
}

function updateFloatWindow() {
    if (!msgFloatContent) return;
    if (contactMap.size === 0) {
        msgFloatContent.innerHTML = `<div class="dy-empty">暂无消息</div>`;
        return;
    }

    const contacts = Array.from(contactMap.values());
    contacts.sort((a, b) => {
        if (a.time && b.time) return b.time.localeCompare(a.time);
        if (a.time) return -1;
        if (b.time) return 1;
        return a.name.localeCompare(b.name);
    });

    let html = '';
    for (const c of contacts) {
        const unreadBadge = c.unread && c.unread !== '0' ? `<span class="dy-badge">${c.unread}</span>` : '';
        const avatarHtml = c.avatar ? `<img src="${c.avatar}" />` : '';
        html += `
            <div class="dy-contact-item" data-name="${c.name}">
                <div class="dy-avatar">${avatarHtml}</div>
                <div class="dy-info">
                    <div class="dy-name">${c.name} ${unreadBadge}</div>
                    <div class="dy-preview">${c.preview || '无消息'}</div>
                </div>
                <div class="dy-time">${c.time || ''}</div>
            </div>
        `;
    }
    msgFloatContent.innerHTML = html;

    msgFloatContent.querySelectorAll('.dy-contact-item').forEach(el => {
        el.addEventListener('click', function (e) {
            e.stopPropagation();
            const name = this.dataset.name;
            if (name) {
                hideFloatImmediate();
                jumpToConversation(name);
            }
        });
    });
}

function hideFloatImmediate() {
    msgFloatWindow.style.opacity = '0';
    msgFloatWindow.style.pointerEvents = 'none';
    msgFloatVisible = false;
    if (syncTimer) { clearInterval(syncTimer); syncTimer = null; }
    setTimeout(() => {
        if (!msgFloatVisible) msgFloatWindow.style.display = 'none';
    }, 200);
}

function jumpToConversation(name) {
    const panelContainer = document.querySelector('#im-entry-vmok-popup-portal')?.closest('.JTaHbIgX');
    if (panelContainer && getComputedStyle(panelContainer).display !== 'none') {
        clickNativeItem(name);
    } else {
        if (msgButton) {
            msgButton.click();
            let attempts = 0;
            const maxAttempts = 30;
            const interval = setInterval(() => {
                attempts++;
                const p = document.querySelector('#im-entry-vmok-popup-portal')?.closest('.JTaHbIgX');
                if (p && getComputedStyle(p).display !== 'none') {
                    clearInterval(interval);
                    clickNativeItem(name);
                } else if (attempts >= maxAttempts) {
                    clearInterval(interval);
                    console.warn('消息面板未能在规定时间内打开');
                }
            }, 200);
        }
    }
}

function clickNativeItem(name) {
    const nativeItems = document.querySelectorAll('#im-entry-vmok-popup-portal .conversationConversationItemwrapper');
    if (!nativeItems.length) {
        console.warn('未找到消息面板列表，可能尚未加载');
        return;
    }
    let target = null;
    for (const native of nativeItems) {
        const nativeTitle = native.querySelector('.conversationConversationItemtitle');
        if (nativeTitle && nativeTitle.textContent.trim() === name) {
            target = native;
            break;
        }
    }
    if (!target) {
        const lowerName = name.toLowerCase();
        for (const native of nativeItems) {
            const nativeTitle = native.querySelector('.conversationConversationItemtitle');
            if (nativeTitle) {
                const titleText = nativeTitle.textContent.trim();
                if (titleText.toLowerCase().includes(lowerName) || lowerName.includes(titleText.toLowerCase())) {
                    target = native;
                    break;
                }
            }
        }
    }
    if (!target) {
        for (const native of nativeItems) {
            const label = native.getAttribute('aria-label') || native.getAttribute('data-e2e');
            if (label && label.includes(name)) {
                target = native;
                break;
            }
        }
    }
    if (target) {
        target.click();
        ['mousedown', 'mouseup', 'click'].forEach(evt => {
            target.dispatchEvent(new MouseEvent(evt, { bubbles: true }));
        });
        console.log('✅ 已跳转到:', name);
    } else {
        console.warn('未找到联系人:', name);
    }
}

// =========================================================
//        全局 Tooltip（脱离菜单容器，避免被裁切）
// =========================================================
function initGlobalTooltip() {
    if (document.getElementById('dyGlobalTooltip')) return;
    const tip = document.createElement('div');
    tip.id = 'dyGlobalTooltip';
    (document.body || document.documentElement).appendChild(tip);

    let currentTarget = null;

    function position(target) {
        const rect = target.getBoundingClientRect();
        // 强制 reflow，确保 tip 尺寸是最新的
        void tip.offsetHeight;
        const tipRect = tip.getBoundingClientRect();
        const margin = 8;
        const pad = 10;

        // 水平居中 + 边界限制
        let left = rect.left + rect.width / 2 - tipRect.width / 2;
        if (left < pad) left = pad;
        if (left + tipRect.width > window.innerWidth - pad) {
            left = window.innerWidth - tipRect.width - pad;
        }

        // 垂直：优先上方，不够再放下方
        let top = rect.top - tipRect.height - margin;
        if (top < pad) {
            top = rect.bottom + margin;
        }
        // 下方也超屏，就贴底
        if (top + tipRect.height > window.innerHeight - pad) {
            top = window.innerHeight - tipRect.height - pad;
        }

        tip.style.left = left + 'px';
        tip.style.top = top + 'px';
    }

    document.addEventListener('mouseover', (e) => {
        const el = e.target && e.target.closest ? e.target.closest('.dy-tip') : null;
        if (!el || !el.dataset.tip) return;
        currentTarget = el;
        tip.textContent = el.dataset.tip;
        tip.classList.add('dy-tooltip-show');
        position(el);
    }, true);

    document.addEventListener('mousemove', () => {
        if (currentTarget) position(currentTarget);
    }, true);

    document.addEventListener('mouseout', (e) => {
        const el = e.target && e.target.closest ? e.target.closest('.dy-tip') : null;
        if (el === currentTarget) {
            tip.classList.remove('dy-tooltip-show');
            currentTarget = null;
        }
    }, true);

    // 页面或菜单滚动时，保持 tooltip 跟随
    document.addEventListener('scroll', () => {
        if (currentTarget) position(currentTarget);
    }, true);
}

// =========================================================
//                   插件自检
// =========================================================
function runSelfCheck() {
    const groups = [];
    const push = (list, name, status, detail) => list.push({ name, status, detail });
    let okCount = 0, warnCount = 0, failCount = 0;

    // ============ 🌐 环境 ============
    const envGroup = { title: '🌐 环境', items: [] };
    push(envGroup.items, '脚本版本', 'ok', 'v' + SCRIPT_VERSION);
    push(envGroup.items, '当前域名', /douyin\.com/.test(location.hostname) ? 'ok' : 'fail', location.hostname);

    let pageType = '未知页面';
    const href = location.href;
    if (href.includes('live.douyin.com')) pageType = '直播间';
    else if (href.includes('/video/')) pageType = '视频详情页';
    else if (document.querySelector('[data-e2e="feed-item"]')) pageType = '推荐流';
    else if (/^https:\/\/www\.douyin\.com\/?$/.test(href)) pageType = '首页';
    else pageType = '其他页面';
    push(envGroup.items, '页面类型', 'ok', pageType);

    const urlBrief = href.length > 70 ? href.slice(0, 67) + '...' : href;
    push(envGroup.items, 'URL', 'ok', urlBrief);
    groups.push(envGroup);

    // ============ 📦 依赖与权限 ============
    const depGroup = { title: '📦 依赖与权限', items: [] };

    let vueOk = false, vueVer = '';
    try { vueOk = (typeof Vue !== 'undefined'); vueVer = vueOk ? ('v' + (Vue.version || '?')) : ''; } catch (e) { }
    push(depGroup.items, 'Vue.js', vueOk ? 'ok' : 'fail', vueOk ? vueVer + ' · 已加载' : '未加载（CDN 可能被墙）');

    let hasUnsafe = false;
    try { hasUnsafe = typeof unsafeWindow !== 'undefined'; } catch (e) { }
    push(depGroup.items, 'unsafeWindow', hasUnsafe ? 'ok' : 'warn', hasUnsafe ? '已授权' : '未声明（需 @grant）');

    let gmOk = false;
    try { GM_getValue('__dy_selfcheck', ''); gmOk = true; } catch (e) { }
    push(depGroup.items, 'GM 存储 API', gmOk ? 'ok' : 'fail', gmOk ? 'GM_getValue / GM_setValue 可用' : '读写出错');

    let gmHandler = '';
    try { gmHandler = (typeof GM_info !== 'undefined') ? (GM_info.scriptHandler || '未知') : ''; } catch (e) { }
    push(depGroup.items, '脚本管理器', gmHandler ? 'ok' : 'warn', gmHandler ? gmHandler : 'GM_info 不可用');
    groups.push(depGroup);

    // ============ 💾 存储 ============
    const storageGroup = { title: '💾 存储', items: [] };

    let ssOk = false;
    try { sessionStorage.setItem('__dy_t', '1'); sessionStorage.removeItem('__dy_t'); ssOk = true; } catch (e) { }
    push(storageGroup.items, 'sessionStorage', ssOk ? 'ok' : 'warn', ssOk ? '可用（开屏封面可正常显示）' : '不可用（开屏封面将失效）');

    let lsOk = false;
    try { localStorage.setItem('__dy_t', '1'); localStorage.removeItem('__dy_t'); lsOk = true; } catch (e) { }
    push(storageGroup.items, 'localStorage', lsOk ? 'ok' : 'warn', lsOk ? '可用' : '不可用（隐私模式可能受限）');

    const stored = GM_getValue('DY_Settings', {});
    const missing = [];
    for (const k in DEFAULT_SETTINGS) { if (!(k in stored)) missing.push(k); }
    push(storageGroup.items, '配置完整性', missing.length === 0 ? 'ok' : 'warn',
         missing.length === 0
             ? ('全部 ' + Object.keys(DEFAULT_SETTINGS).length + ' 项字段齐全')
             : ('缺 ' + missing.length + ' 项：' + missing.slice(0, 3).join('、') + (missing.length > 3 ? ' 等' : '')));

    push(storageGroup.items, '已存字段数', 'ok', Object.keys(stored).length + ' 项');
    groups.push(storageGroup);

    // ============ ⏱️ 核心定时器 ============
    const timerGroup = { title: '⏱️ 核心定时器', items: [] };
    push(timerGroup.items, '画质轮询', timerQuality ? 'ok' : 'warn',
         timerQuality ? ('运行中 · 间隔 ' + (settings.pollingQuality || 1.5) + 's') : '未启动');
    push(timerGroup.items, '弹幕轮询', timerDanmu ? 'ok' : 'warn',
         timerDanmu ? ('运行中 · 间隔 ' + (settings.pollingDanmu || 0.5) + 's') : '未启动');
    push(timerGroup.items, 'DOM 清理', timerClean ? 'ok' : 'warn',
         timerClean ? ('运行中 · 间隔 ' + (settings.pollingClean || 10) + 's') : '未启动');
    push(timerGroup.items, '跳过检测', timerSkip ? 'ok' : 'warn',
         timerSkip ? ('运行中 · 间隔 ' + (settings.skipVideoPolling || 0.3) + 's') : '未启动');
    groups.push(timerGroup);

    // ============ 🧩 功能模块 ============
    const modGroup = { title: '🧩 功能模块', items: [] };

    if (typeof PauseGuard !== 'undefined') {
        const subKeys = [
            ['pauseGuard_visibilitySpoof', '可见性伪装'],
            ['pauseGuard_eventBlocking', '事件拦截'],
            ['pauseGuard_rafReplacement', 'rAF 替换'],
            ['pauseGuard_mouseSimulation', '鼠标模拟'],
            ['pauseGuard_popupClick', '弹窗点击'],
            ['pauseGuard_backgroundResume', '后台静音恢复'],
        ];
        const onSubs = subKeys.filter(kv => settings[kv[0]] !== false).map(kv => kv[1]);
        const offSubs = subKeys.filter(kv => settings[kv[0]] === false).map(kv => kv[1]);
        const en = PauseGuard.isEnabled();
        let detail;
        if (en) {
            detail = '总开关开启 · 子模块 ' + onSubs.length + '/6 已启用';
            if (offSubs.length > 0) detail += ' · 关闭：' + offSubs.join('、');
        } else {
            detail = '总开关已关闭（子模块配置保留 ' + onSubs.length + '/6）';
        }
        push(modGroup.items, 'PauseGuard', en ? 'ok' : 'warn', detail);
    } else {
        push(modGroup.items, 'PauseGuard', 'fail', '未初始化');
    }

    push(modGroup.items, 'DOM 观察器', window._dyObserver ? 'ok' : 'warn',
         window._dyObserver ? '运行中（监控页面 DOM 变化）' : '未启动');

    const navBtn = document.querySelector('.tab-scriptmenu');
    push(modGroup.items, '导航栏按钮', navBtn ? 'ok' : 'warn',
         navBtn ? '已注入（导航栏可见「脚本菜单」）' : (settings.enableNavButton ? '未注入（可能不在首页）' : '用户已关闭'));

    const msgBtn = document.querySelector('[data-e2e="something-button"]');
    push(modGroup.items, '消息悬浮窗', settings.enableMsgFloat ? (msgBtn ? 'ok' : 'warn') : 'warn',
         !settings.enableMsgFloat ? '用户已关闭'
         : (msgBtn ? '已启用 · 消息按钮存在' : '已启用 · 但未找到消息按钮'));

    push(modGroup.items, '开屏封面', settings.enableSplashScreen ? (ssOk ? 'ok' : 'warn') : 'warn',
         !settings.enableSplashScreen ? '用户已关闭'
         : (ssOk ? '已启用 · sessionStorage 可用' : '已启用 · 但 sessionStorage 不可用'));
    groups.push(modGroup);

    // ============ 🎯 抖音 DOM 适配 ============
    const domGroup = { title: '🎯 抖音 DOM 适配', items: [] };

    const feedItems = document.querySelectorAll('[data-e2e="feed-item"]');
    push(domGroup.items, '卡片 feed-item', feedItems.length > 0 ? 'ok' : 'warn',
         feedItems.length > 0 ? (feedItems.length + ' 个卡片') : '未找到（可能不在推荐流）');

    const videos = document.querySelectorAll('video');
    const playingCount = Array.from(videos).filter(v => !v.paused).length;
    const readyCount = Array.from(videos).filter(v => v.readyState >= 2).length;
    push(domGroup.items, '视频元素 video', videos.length > 0 ? 'ok' : 'warn',
         videos.length > 0 ? (videos.length + ' 个 · ' + playingCount + ' 播放中 · ' + readyCount + ' 就绪') : '未找到');

    const nextBtn = document.querySelector('.xgplayer-playswitch-next')
        || document.querySelector('[data-e2e="video-switch-next-arrow"]');
    push(domGroup.items, '切换按钮', nextBtn ? 'ok' : 'warn',
         nextBtn ? '存在（"下一个"按钮可用）' : '未找到（跳过功能靠键盘 ArrowDown 兜底）');

    const danmu = document.querySelectorAll('[data-danmu-id]');
    push(domGroup.items, '弹幕元素', danmu.length > 0 ? 'ok' : 'warn',
         danmu.length > 0 ? (danmu.length + ' 条在屏弹幕') : '未找到（可能弹幕未开启）');

    const microgame = document.querySelector('.tab-microgame');
    push(domGroup.items, '导航栏 tab-microgame', microgame ? 'ok' : 'warn',
         microgame ? '存在（导航栏按钮的锚点）' : '未找到（导航栏按钮无法注入）');
    groups.push(domGroup);

    // ============ 🔘 功能开关状态 ============
    const toggleGroup = { title: '🔘 功能开关状态', items: [] };
    const switches = [
        ['blockedDanmu_Switch', '弹幕屏蔽'],
        ['skipLive_Switch', '跳过直播'],
        ['skipVideoRegex_Switch', '正则屏蔽视频'],
        ['enableQualitySwitch', '智能画质切换'],
        ['enablePayHide', '隐藏礼物面板'],
        ['enableMirror', '视频镜像'],
        ['enableGiftFilter', '礼物消息过滤'],
        ['enableDOMClean', 'DOM 清理'],
        ['hideNonVideoElements_Switch', '隐藏非视频元素'],
        ['enableKeepAlive', '全局防暂停'],
        ['enableKeyboardShortcuts', '键盘快捷键'],
        ['enableNavButton', '导航栏按钮'],
        ['enableMsgFloat', '消息悬浮窗'],
        ['enableSplashScreen', '开屏封面'],
        ['consoleOutputLog_Switch', '控制台日志'],
        ['debugMode', '全功能调试'],
    ];
    const onList = [], offList = [];
    for (const [key, label] of switches) {
        if (settings[key]) onList.push(label); else offList.push(label);
    }
    push(toggleGroup.items, '已开启（' + onList.length + '/' + switches.length + '）', 'ok', onList.join('、') || '无');
    push(toggleGroup.items, '已关闭（' + offList.length + '）', 'ok', offList.join('、') || '无');
    groups.push(toggleGroup);

    // 统计
    for (const g of groups) {
        for (const it of g.items) {
            if (it.status === 'ok') okCount++;
            else if (it.status === 'warn') warnCount++;
            else if (it.status === 'fail') failCount++;
        }
    }
    return { groups, okCount, warnCount, failCount };
}

function showSelfCheckPanel() {
    const old = document.getElementById('dySelfCheck');
    if (old) old.remove();

    const { groups, okCount, warnCount, failCount } = runSelfCheck();

    const summaryBg = failCount > 0 ? 'rgba(220,50,50,0.2)'
        : warnCount > 0 ? 'rgba(255,152,0,0.2)' : 'rgba(76,175,80,0.2)';
    const summaryColor = failCount > 0 ? '#ff6b6b'
        : warnCount > 0 ? '#ffa500' : '#4CAF50';
    const summaryText = failCount > 0
        ? ('❌ ' + failCount + ' 项失败 · ' + warnCount + ' 项警告 · ' + okCount + ' 项通过')
        : warnCount > 0
            ? ('⚠️ ' + warnCount + ' 项警告 · ' + okCount + ' 项通过')
            : ('✅ 全部 ' + okCount + ' 项检查通过');

    let bodyHtml = '';
    for (const g of groups) {
        bodyHtml += '<div style="margin-bottom:16px;">'
            + '<div style="font-size:13px;font-weight:600;color:#fff;padding:4px 0 6px;border-bottom:1px solid rgba(255,255,255,0.12);margin-bottom:4px;">' + g.title + '</div>';
        for (const r of g.items) {
            const icon = r.status === 'ok' ? '✅' : r.status === 'warn' ? '⚠️' : '❌';
            const color = r.status === 'ok' ? '#4CAF50' : r.status === 'warn' ? '#ffa500' : '#ff6b6b';
            bodyHtml += '<div style="display:flex;gap:8px;padding:4px 0;align-items:flex-start;font-size:12px;">'
                + '<span style="flex-shrink:0;width:16px;">' + icon + '</span>'
                + '<span style="flex:0 0 140px;color:' + color + ';">' + r.name + '</span>'
                + '<span style="flex:1;color:#bbb;word-break:break-all;">' + (r.detail || '') + '</span>'
                + '</div>';
        }
        bodyHtml += '</div>';
    }

    const panel = document.createElement('div');
    panel.id = 'dySelfCheck';
    panel.style.cssText = 'position:fixed;top:50%;left:50%;transform:translate(-50%,-50%);'
        + 'z-index:2147483647;background:#2a2a2a;color:#eee;border-radius:8px;'
        + 'padding:18px 22px;min-width:520px;max-width:92vw;max-height:85vh;overflow-y:auto;'
        + 'box-shadow:0 12px 40px rgba(0,0,0,0.7);border:1px solid rgba(255,255,255,0.1);'
        + "font-family:'PingFang SC','Microsoft YaHei',sans-serif;font-size:13px;line-height:1.6;";

    panel.innerHTML = ''
        + '<div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:12px;">'
        +   '<div style="font-size:16px;font-weight:600;">🔍 脚本自检报告</div>'
        +   '<button id="dySelfCheckClose" style="background:none;border:none;color:#aaa;font-size:24px;cursor:pointer;padding:0 4px;line-height:1;">×</button>'
        + '</div>'
        + '<div style="margin-bottom:14px;padding:8px 12px;border-radius:4px;background:' + summaryBg + ';color:' + summaryColor + ';font-weight:600;">'
        +   summaryText
        + '</div>'
        + bodyHtml
        + '<div style="display:flex;gap:8px;justify-content:flex-end;padding-top:12px;border-top:1px solid rgba(255,255,255,0.1);">'
        +   '<button id="dySelfCheckCopy" style="padding:6px 14px;border:none;border-radius:4px;background:#00aeec;color:#fff;cursor:pointer;font-size:13px;">📋 复制报告</button>'
        +   '<button id="dySelfCheckRerun" style="padding:6px 14px;border:none;border-radius:4px;background:#4CAF50;color:#fff;cursor:pointer;font-size:13px;">🔄 重新检查</button>'
        + '</div>';

    document.body.appendChild(panel);

    document.getElementById('dySelfCheckClose').onclick = () => panel.remove();
    document.getElementById('dySelfCheckRerun').onclick = () => showSelfCheckPanel();
    document.getElementById('dySelfCheckCopy').onclick = async () => {
        let text = '抖音优化 v' + SCRIPT_VERSION + ' 自检报告\n';
        text += '时间: ' + new Date().toLocaleString() + '\n';
        text += 'URL: ' + location.href + '\n';
        text += 'UA: ' + navigator.userAgent + '\n\n';
        for (const g of groups) {
            text += '【' + g.title.replace(/^[^\s]+\s/, '') + '】\n';
            for (const r of g.items) {
                const tag = r.status === 'ok' ? '[OK]  ' : r.status === 'warn' ? '[WARN]' : '[FAIL]';
                text += '  ' + tag + ' ' + r.name + ': ' + (r.detail || '') + '\n';
            }
            text += '\n';
        }
        try {
            await navigator.clipboard.writeText(text);
            const btn = document.getElementById('dySelfCheckCopy');
            btn.textContent = '✅ 已复制';
            setTimeout(() => { btn.textContent = '📋 复制报告'; }, 1500);
        } catch (e) {
            prompt('复制以下内容：', text);
        }
    };

    const onKey = (e) => {
        if (e.key === 'Escape') {
            panel.remove();
            document.removeEventListener('keydown', onKey, true);
        }
    };
    document.addEventListener('keydown', onKey, true);
}

// =========================================================
//                   初始化
// =========================================================
function init() {
    try {
        cc('magenta', `=== 抖音优化 + 全局防暂停 v${SCRIPT_VERSION} 启动 ===`);

        initGlobalTooltip();
        setupInteractionLock();
        applyPayHide();
        applyMirror();
        setupKeepAlive();

        document.addEventListener('keydown', keydown, false);

        let lastUrl = location.href;
        setInterval(() => {
            if (location.href !== lastUrl) {
                lastUrl = location.href;
                cc('blue', 'URL变化，重置池子');
                resetPools();
            }
        }, 2000);

        setTimeout(() => { injectScriptMenuButton(); }, 1000);

        setTimeout(() => { switchToHighestQuality(); filterDanmu(); cleanOldDOM(); checkSkipFeed(); }, 500);
        setTimeout(() => { startQualityTimer(); startDanmuTimer(); startCleanTimer(); startSkipTimer(); }, 1000);

        let observerTimer = null;
        const observer = new MutationObserver(() => {
            if (observerTimer) clearTimeout(observerTimer);
            observerTimer = setTimeout(() => {
                hideNonVideoElements();
                filterGiftMessages();
                hookAllVideos();
                if (settings.enableNavButton) injectScriptMenuButton();
            }, 300);
        });
        observer.observe(document.body, { childList: true, subtree: true });
        window._dyObserver = observer;
        cc('green', '初始化完成');

        setTimeout(initMessagePreview, 1500);

        // ★ 全部成功 → 通知加载提示
        if (window.__dyLoadOk) window.__dyLoadOk();
    } catch (err) {
        console.error('[抖音优化] 初始化失败：', err);
        if (window.__dyLoadFail) window.__dyLoadFail(err && err.message ? err.message : String(err));
    }
}

if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
} else {
    setTimeout(init, 1000);
}

window.addEventListener('beforeunload', () => {
    if (timerQuality) { clearInterval(timerQuality); timerQuality = null; }
    if (timerDanmu) { clearInterval(timerDanmu); timerDanmu = null; }
    if (timerClean) { clearInterval(timerClean); timerClean = null; }
    if (timerSkip) { clearInterval(timerSkip); timerSkip = null; }
    if (audioContext) { audioContext.close(); audioContext = null; }
    if (window._dyObserver) { window._dyObserver.disconnect(); window._dyObserver = null; }
    if (danmuSyncTimer) { clearInterval(danmuSyncTimer); danmuSyncTimer = null; }
    document.removeEventListener('keydown', keydown, false);
});