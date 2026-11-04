package com.astra.autotrader.browser

/**
 * فحص بصري/بنيوي محدود لصفحة Pocket Option المفتوحة داخل WebView.
 * لا يقرأ ملفات تعريف الارتباط أو رموز الجلسات أو رسائل الشبكة،
 * ولا يدّعي استخراج OHLC من عناصر Canvas.
 */
object PocketChartDiagnostics {
    val script: String = """
        (function() {
          var host = (location.hostname || '').toLowerCase();
          if (!(host === 'pocketoption.com' || host.endsWith('.pocketoption.com'))) {
            return 'رفض الفحص: الصفحة الحالية ليست ضمن نطاق Pocket Option';
          }
          var canvases = Array.prototype.slice.call(document.querySelectorAll('canvas'));
          var visibleCanvases = canvases.filter(function(c) {
            var r = c.getBoundingClientRect();
            var s = window.getComputedStyle(c);
            return r.width > 20 && r.height > 20 && s.display !== 'none' && s.visibility !== 'hidden';
          });
          var svgs = document.querySelectorAll('svg').length;
          var chartNodes = document.querySelectorAll(
            '[class*="chart" i], [id*="chart" i], [class*="tradingview" i]'
          ).length;
          return 'فحص الصفحة فقط — Canvas: ' + canvases.length +
            '، Canvas ظاهر: ' + visibleCanvases.length +
            '، SVG: ' + svgs + '، عناصر بأسماء شارت: ' + chartNodes +
            '. لا توجد قراءة OHLC مؤكدة.';
        })();
    """.trimIndent()
}
