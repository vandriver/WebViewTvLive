package com.vasthread.webviewtv.adapter

import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import com.vasthread.webviewtv.widget.WebpageAdapterWebView
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class YangshipinWebpageAdapter : CommonWebpageAdapter() {

    companion object {
        private const val TAG = "YangshipinAdapter"
        private const val CLICK_DURATION = 50L
        private const val FULLSCREEN_WAIT = 1500L

        // The player has no shortcut key for fullscreen, and a script cannot ask the kernel for it
        // because that needs a real tap. So the button is located here and tapped with real touch
        // events afterwards. Buttons are small and their wrappers are big, so the smallest match
        // wins: that keeps the tap away from the player container, which would only pause.
        private val FIND_FULLSCREEN_BUTTON = """
            (function () {
                if (document.querySelector('.wvt-fullscreen')) { return 'done'; }
                var pattern = /full[\s\-_]?screen|全屏/i;
                var nodes = document.querySelectorAll('div,button,a,span,i,img,svg,p');
                var best = null, bestArea = Number.MAX_VALUE;
                for (var i = 0; i < nodes.length; ++i) {
                    var el = nodes[i], cls = el.className;
                    if (cls && cls.baseVal !== undefined) { cls = cls.baseVal; }
                    var key = (cls || '') + ' ' + (el.id || '') + ' ' + (el.title || '') + ' ' +
                        (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('data-title') || '');
                    if (!pattern.test(key)) { continue; }
                    var rect = el.getBoundingClientRect();
                    if (rect.width < 8 || rect.height < 8) { continue; }
                    if (rect.right <= 0 || rect.bottom <= 0) { continue; }
                    var style = window.getComputedStyle(el);
                    if (style.visibility === 'hidden' || style.display === 'none' || style.opacity === '0') { continue; }
                    var area = rect.width * rect.height;
                    if (area < bestArea) { bestArea = area; best = rect; }
                }
                if (!best) { return ''; }
                return (best.left + best.width / 2) + ',' + (best.top + best.height / 2) + ',' +
                    window.innerWidth + ',' + window.innerHeight;
            })()
        """.trimIndent()

        // Last resort: the video itself is stretched over the whole window. The page keeps its own
        // layout, but nothing of it can be seen or clicked any more, which is what the viewer wants.
        private val PSEUDO_FULLSCREEN = """
            (function () {
                if (window.wvt_ysp_fullscreen) { window.wvt_ysp_fullscreen(); return; }
                var CSS = '.wvt-fullscreen{position:fixed!important;left:0!important;top:0!important;' +
                    'width:100vw!important;height:100vh!important;min-width:0!important;min-height:0!important;' +
                    'max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;' +
                    'border:0!important;object-fit:fill!important;background:#000!important;' +
                    'z-index:2147483647!important;}';
                function biggest() {
                    var nodes = document.querySelectorAll('video,canvas'), best = null, area = 0;
                    for (var i = 0; i < nodes.length; ++i) {
                        var rect = nodes[i].getBoundingClientRect();
                        if (rect.width * rect.height > area) { area = rect.width * rect.height; best = nodes[i]; }
                    }
                    return best;
                }
                function apply() {
                    var el = biggest();
                    if (!el) { return; }
                    if (!document.getElementById('wvt-ysp-style')) {
                        var style = document.createElement('style');
                        style.id = 'wvt-ysp-style';
                        style.textContent = CSS;
                        (document.head || document.documentElement).appendChild(style);
                    }
                    el.classList.add('wvt-fullscreen');
                    // A transform on a parent makes position:fixed relative to that parent instead
                    // of the window, so those are dropped all the way up.
                    for (var p = el.parentElement; p && p !== document.documentElement; p = p.parentElement) {
                        var cs = window.getComputedStyle(p);
                        if (cs.transform !== 'none') { p.style.setProperty('transform', 'none', 'important'); }
                        if (cs.filter !== 'none') { p.style.setProperty('filter', 'none', 'important'); }
                        if (cs.perspective !== 'none') { p.style.setProperty('perspective', 'none', 'important'); }
                        if (cs.willChange !== 'auto') { p.style.setProperty('will-change', 'auto', 'important'); }
                        if (cs.contain && cs.contain.indexOf('paint') >= 0) { p.style.setProperty('contain', 'none', 'important'); }
                        if (cs.overflow !== 'visible') { p.style.setProperty('overflow', 'visible', 'important'); }
                    }
                    document.documentElement.style.setProperty('overflow', 'hidden', 'important');
                }
                window.wvt_ysp_fullscreen = apply;
                apply();
                // The player rebuilds its nodes now and then, which drops the styles.
                if (!window.wvt_ysp_timer) { window.wvt_ysp_timer = setInterval(apply, 1000); }
            })()
        """.trimIndent()
    }

    override fun isAdaptedUrl(url: String) = url.contains("yangshipin.cn")

    override fun isPlayingCheckEnabled() = false

    override suspend fun enterFullscreen(webView: WebpageAdapterWebView) {
        if (webView.isInFullscreen()) return
        if (tapFullscreenButton(webView)) return
        Log.i(TAG, "The fullscreen button did not work, stretching the video instead.")
        webView.evaluateJavascript(PSEUDO_FULLSCREEN, null)
    }

    private suspend fun tapFullscreenButton(webView: WebpageAdapterWebView): Boolean {
        val answer = withTimeoutOrNull(1000L) {
            webView.evaluateJavascriptForResult(FIND_FULLSCREEN_BUTTON)
        }?.trim()?.trim('"') ?: return false
        if (answer == "done") return true
        val values = answer.split(',').mapNotNull { it.trim().toFloatOrNull() }
        if (values.size < 4 || values[2] <= 0F || values[3] <= 0F) return false

        // The page talks in CSS pixels, the view in real ones, so the page is used as the scale.
        val x = values[0] / values[2] * webView.width
        val y = values[1] / values[3] * webView.height
        Log.i(TAG, "Tap the fullscreen button at ($x, $y).")
        tap(webView, x, y)

        repeat((FULLSCREEN_WAIT / 100).toInt()) {
            delay(100)
            if (webView.isInFullscreen()) return true
        }
        return false
    }

    private suspend fun tap(webView: WebpageAdapterWebView, x: Float, y: Float) {
        val downTime = SystemClock.uptimeMillis()
        webView.dispatchTouchEvent(MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0))
        delay(CLICK_DURATION)
        webView.dispatchTouchEvent(MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0))
    }

    private suspend fun WebpageAdapterWebView.evaluateJavascriptForResult(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            post {
                evaluateJavascript(script) { value ->
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }
}
