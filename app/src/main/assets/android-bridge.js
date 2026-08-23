/* android-bridge.js —— 安卓适配壳（本项目新增，不改动 writing-core.js 识别算法）
 *
 * 职责（与《文字模块接入指南》对接）：
 *  1) 在页面加载后，初始化 WritingCore（mode=char 单字为主；也可按注入词库含词语自动切 word）；
 *  2) 题库 window.WORD_LIST 由安卓通过 JsBridge.injectWordList() 注入（不前端自定语库，需求四.3）；
 *  3) 每次 open 前安卓都会重新注入最新词库（含权重变化后的新列表）；
 *  4) 答题结果通过 AndroidBridge.onResult(json) 回传安卓写 Room；
 *  5) 受"锁死规则"控制：仅当安卓判定答对并允许关闭时，弹窗才消失。
 *
 * 注意：writing-core.js 内部 finish() 已调用 hooks.onPass/onSkip，
 * 这里把 onPass 绑定为"通知安卓答对"，onSkip 绑定为"通知安卓答错/放弃"。
 * 安卓端依据 isCorrect 决定关闭或锁死（需求三.2）。
 */
(function () {
  'use strict';

  // 等待 WritingCore 与安卓桥就绪
  function boot() {
    if (typeof WritingCore === 'undefined') {
      setTimeout(boot, 100); return;
    }

    // 默认词库占位（安卓会很快覆盖为 Room 词库）
    window.WORD_LIST = window.WORD_LIST || [{ hanzi: '天', pinyin: 'tiān' }];
    window.IS_RANDOM_MODE = true;

    // 初始化核心：单字模式为主（若注入词库里出现多字词，可在 open 时由安卓指定 mode；
    // 这里统一用 char，词语模式如需启用，由安卓传参后调用 setMode）
    WritingCore.init({
      mode: 'char',
      onPass: function () {
        // 写对 → 回传安卓（安卓会重置计时并关闭弹窗）
        reportResult(true);
      },
      onSkip: function () {
        // 放弃/失败 → 回传安卓（安卓保持锁死）
        reportResult(false);
      }
    });

    // 暴露给安卓调用：重新注入词库后 open
    window.AndroidBridge_openDictation = function (mode) {
      if (mode === 'word') {
        // 如需词语模式：重新 init 为 word（词库含多字词时）
        WritingCore.init({ mode: 'word', onPass: function(){ reportResult(true); }, onSkip: function(){ reportResult(false); } });
      }
      WritingCore.open();
    };
  }

  // 回传答题结果给安卓（安卓写 Room + 决定锁死/关闭）
  function reportResult(isCorrect) {
    try {
      // 读取 writing-core 在 open() 时写入的当前字词（对接用）
      var hanzi = (window.__currentHanzi) || '';
      var mode = (window.__currentMode) || 'char';
      if (window.AndroidBridge && window.AndroidBridge.onResult) {
        window.AndroidBridge.onResult(JSON.stringify({
          hanzi: hanzi, isCorrect: isCorrect, mode: mode
        }));
      }
    } catch (e) {}
  }

  // 监听网络失败（writing-core 离线兜底时也会触发，这里通知安卓弹提示）
  window.addEventListener('error', function (e) {
    if (window.AndroidBridge && window.AndroidBridge.onNetworkFail) {
      // 仅在资源加载类错误时提示（避免手写异常噪音）
      if (e && e.target && (e.target.src || e.target.href)) {
        window.AndroidBridge.onNetworkFail();
      }
    }
  }, true);

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})();
