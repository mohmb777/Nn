package com.astra.autotrader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.astra.autotrader.engine.EngineState
import com.astra.autotrader.engine.TradingForegroundService
import com.astra.autotrader.browser.PocketChartDiagnostics
import com.astra.autotrader.pine.PineScriptConverter
import com.astra.autotrader.pine.PineOhlcCsvParser
import com.astra.autotrader.pine.PineOhlcDataset
import com.astra.autotrader.pine.PineReplayEngine
import com.astra.autotrader.pine.PineStrategyReplayEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { AstraApp() }
    }
}

@Composable
private fun AstraApp() {
    var running by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf("لوحة التحكم") }
    val context = LocalContext.current
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Scaffold(
            topBar = {
                TopAppBarCompat(title = "ASTRA AUTO TRADER", subtitle = "مساعد التداول الذكي")
            },
            bottomBar = {
                NavigationBar {
                    listOf("لوحة التحكم", "المتصفح", "الاستراتيجيات", "Pine Script", "السجل").forEach {
                        NavigationBarItem(
                            selected = selected == it,
                            onClick = { selected = it },
                            icon = { Text(when (it) { "المتصفح" -> "🌐"; "الاستراتيجيات" -> "📊"; "Pine Script" -> "🧩"; "السجل" -> "📋"; else -> "⌂" }) },
                            label = { Text(it) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                "المتصفح" -> PocketBrowser(Modifier.padding(padding))
                "الاستراتيجيات" -> StrategyScreen(Modifier.padding(padding))
                "Pine Script" -> PineConverterScreen(Modifier.padding(padding))
                "السجل" -> SimplePage("سجل الصفقات", "سيظهر هنا سجل الصفقات ونتائج الاختبار.", Modifier.padding(padding))
                else -> Dashboard(
                    running = running,
                    onStart = {
                        running = true
                        EngineState.running = true
                        TradingForegroundService.start(context)
                    },
                    onStop = {
                        running = false
                        EngineState.running = false
                        TradingForegroundService.stop(context)
                    },
                    modifier = Modifier.padding(padding)
                )
            }
        }
    }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopAppBarCompat(title: String, subtitle: String) {
    TopAppBar(title = { Column { Text(title); Text(subtitle, style = MaterialTheme.typography.labelSmall) } })
}

@Composable
private fun Dashboard(running: Boolean, onStart: () -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("حالة محرك التداول", style = MaterialTheme.typography.titleLarge)
                    Text(if (running) "المراقبة المحلية تعمل" else "متوقف", color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("تشغيل خدمة المراقبة لا يعني أن بيانات الشموع أو تنفيذ الصفقات متصلان. لا يرسل التطبيق أوامر إلى Pocket Option.")
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                Button(onClick = onStart, enabled = !running, modifier = Modifier.weight(1f)) { Text("ابدأ") }
                OutlinedButton(onClick = onStop, enabled = running, modifier = Modifier.weight(1f)) { Text("توقف") }
            }
        }
        item { InfoCard("الاستراتيجية النشطة", "لم يتم اختيار استراتيجية") }
        item { InfoCard("زوج التداول", "يتم تحديده من داخل المتصفح") }
        item { InfoCard("الإطار الزمني", "غير محدد") }
        item { InfoCard("آخر إشارة", "لا توجد بيانات شموع موثقة — لا توجد إشارة تداول") }
        item { InfoCard("التنفيذ", "مغلق بأمان حتى التأكد من أن الحساب تجريبي") }
        item { InfoCard("وضع التشغيل", "Demo للاختبار فقط. الحقيقي مقفول برمجياً أثناء التطوير والاختبار.") }
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(value) } }
}

@Composable
private fun PocketBrowser(modifier: Modifier = Modifier) {
    val homeUrl = "https://pocketoption.com"
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageTitle by remember { mutableStateOf("Pocket Option") }
    var chartDiagnostics by remember { mutableStateOf("لم يبدأ فحص بنية الشارت بعد.") }
    Column(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("متصفح Pocket Option", style = MaterialTheme.typography.titleMedium)
            Text(
                "حالة الحساب: غير متحقق منها. افتح Demo يدوياً داخل المنصة؛ لا يمكن إثبات نوع الحساب من شكل الصفحة وحده.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { webView?.goBack() }, enabled = webView?.canGoBack() == true, modifier = Modifier.weight(1f)) { Text("رجوع") }
                OutlinedButton(onClick = { webView?.reload() }, enabled = webView != null, modifier = Modifier.weight(1f)) { Text("تحديث") }
                Button(onClick = { webView?.loadUrl(homeUrl) }, enabled = webView != null, modifier = Modifier.weight(1f)) { Text("الرئيسية") }
            }
            Text(pageTitle, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            OutlinedButton(
                onClick = {
                    webView?.evaluateJavascript(PocketChartDiagnostics.script) { result ->
                        chartDiagnostics = result?.let { if (it.length >= 2) it.substring(1, it.length - 1) else it } ?: "تعذر تنفيذ الفحص"
                    }
                },
                enabled = webView != null,
                modifier = Modifier.fillMaxWidth()
            ) { Text("فحص بنية الشارت") }
            Text(chartDiagnostics, style = MaterialTheme.typography.bodySmall)
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                    settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    settings.safeBrowsingEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            pageTitle = view.title?.takeIf { it.isNotBlank() } ?: url
                        }
                    }
                    webView = this
                    loadUrl(homeUrl)
                }
            },
            update = { view -> if (webView !== view) webView = view }
        )
    }
}

@Composable
private fun StrategyScreen(modifier: Modifier = Modifier) {
    var strategy by remember { mutableStateOf("macd_12_26_9") }
    var symbol by remember { mutableStateOf("") }
    var stake by remember { mutableStateOf("1") }
    var timeframe by remember { mutableStateOf("M1") }
    var saved by remember { mutableStateOf(false) }
    val duration = when (timeframe) {
        "S5" -> "5 ثوانٍ"
        "S10" -> "10 ثوانٍ"
        "S15" -> "15 ثانية"
        "S30" -> "30 ثانية"
        "M5" -> "5 دقائق"
        "M15" -> "15 دقيقة"
        "M30" -> "30 دقيقة"
        "H1" -> "ساعة"
        else -> "دقيقة واحدة"
    }
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("خطة التداول التجريبي", style = MaterialTheme.typography.headlineSmall) }
        item {
            InfoCard("وضع الحساب", "وضع Demo للاختبار. الحقيقي محظور أثناء التطوير؛ لم يتم التحقق آلياً من نوع الحساب ولا يسمح التطبيق بإرسال أوامر حالياً.")
        }
        item {
            Text("الاستراتيجية", style = MaterialTheme.typography.titleMedium)
            listOf("macd_12_26_9" to "تقاطع MACD", "ema_rsi_confluence" to "توافق EMA 9/21 + RSI 14", "support_resistance" to "الدعم والمقاومة").forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = strategy == item.first, onClick = { strategy = item.first; saved = false })
                    Text(item.second)
                }
            }
        }
        item {
            OutlinedTextField(
                value = symbol,
                onValueChange = { symbol = it.uppercase(); saved = false },
                label = { Text("زوج التداول (كما يظهر في المنصة)") },
                placeholder = { Text("مثال: EURUSD OTC") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            OutlinedTextField(
                value = stake,
                onValueChange = { stake = it; saved = false },
                label = { Text("مبلغ الصفقة التجريبية") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Text("الفريم ومدة الصفقة", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                listOf("S5", "S10", "S15", "S30", "M1", "M5", "M15", "M30", "H1").forEach { frame ->
                    FilterChip(
                        selected = timeframe == frame,
                        onClick = { timeframe = frame; saved = false },
                        label = { Text(frame) }
                    )
                }
            }
            Text("مدة الصفقة تلقائياً: $duration")
            Text("مدة الصفقة تساوي الفريم المختار؛ يجب التحقق من دعم المنصة للمدة قبل التنفيذ.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        item {
            Button(
                onClick = {
                    saved = symbol.isNotBlank() && (stake.toDoubleOrNull()?.let { it > 0.0 && it.isFinite() } == true)
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("حفظ الخطة") }
            if (saved) {
                Text("تم حفظ إعدادات الخطة محلياً لهذه الجلسة. هذا لا يعني أن التنفيذ متصل.")
            } else {
                Text("املأ الزوج والمبلغ الصحيحين ثم احفظ الخطة.")
            }
        }
        item { Divider() }
        item { InfoCard("حالة ربط الشموع", "غير متصل — لا توجد قراءة مؤكدة لشموع المنصة حتى الآن.") }
        item { InfoCard("حالة تنفيذ الأوامر", "معطّل بأمان — لم يتم ربط واجهة تنفيذ تجريبية موثقة.") }
        item { InfoCard("محوّل Pine Script", "افتح تبويب Pine Script للصق الكود أو استيراد ملف .pine وتحليل الجزء المدعوم بأمان.") }
        item { InfoCard("إعدادات OpenRouter", "غير موصولة بعد. لا تضع مفتاح API في ملفات المشروع أو داخل صفحة الويب.") }
    }
}

@Composable
private fun PineConverterScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var source by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<PineScriptConverter.Conversion?>(null) }
    var pineFileError by remember { mutableStateOf<String?>(null) }
    var dataset by remember { mutableStateOf<PineOhlcDataset?>(null) }
    var csvError by remember { mutableStateOf<String?>(null) }
    var replayReport by remember { mutableStateOf<com.astra.autotrader.pine.PineReplayReport?>(null) }
    var tickSizeText by remember { mutableStateOf("") }
    var strategyReplayReport by remember { mutableStateOf<com.astra.autotrader.pine.PineStrategyReplayReport?>(null) }

    val pinePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { readTextLimited(context, uri, 100_000) }
                .onSuccess {
                    source = it
                    result = null
                    pineFileError = null
                    replayReport = null; strategyReplayReport = null
                }
                .onFailure { pineFileError = it.message ?: "تعذرت قراءة ملف Pine." }
        }
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { readTextLimited(context, uri, PineOhlcCsvParser.MAX_CSV_CHARS) }
                .onSuccess { csv ->
                    val parsed = PineOhlcCsvParser.parse(csv)
                    dataset = parsed.dataset
                    csvError = parsed.errorArabic
                    replayReport = null; strategyReplayReport = null
                }
                .onFailure {
                    dataset = null
                    csvError = it.message ?: "تعذرت قراءة ملف OHLC CSV."
                    replayReport = null; strategyReplayReport = null
                }
        }
    }

    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("محوّل Pine Script", style = MaterialTheme.typography.headlineSmall) }
        item { Text("الصق Pine Script v5/v6 أو استورد .pine. المحلل لا ينفذ الكود ولا يستخدم eval.") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { pinePicker.launch(arrayOf("text/*", "application/octet-stream")) },
                    modifier = Modifier.weight(1f)
                ) { Text("استيراد .pine") }
                OutlinedButton(
                    onClick = { csvPicker.launch(arrayOf("text/csv", "text/*")) },
                    modifier = Modifier.weight(1f)
                ) { Text("استيراد OHLC CSV") }
            }
        }
        pineFileError?.let { message ->
            item { Text(message, color = MaterialTheme.colorScheme.error) }
        }
        dataset?.let { loaded ->
            item {
                InfoCard(
                    "ملف OHLC المحلي",
                    "${loaded.bars.size} شمعة" +
                        if (loaded.expectedSignals != null) " — يحتوي إشارات مرجعية للمقارنة."
                        else " — لا يحتوي expected_signal؛ لن تُحسب نسبة التطابق."
                )
            }
        }
        csvError?.let { message ->
            item { Text("تعذر استيراد OHLC: $message", color = MaterialTheme.colorScheme.error) }
        }
        item {
            OutlinedTextField(
                value = source,
                onValueChange = {
                    source = it
                    result = null
                    replayReport = null; strategyReplayReport = null
                },
                label = { Text("كود Pine Script") },
                placeholder = { Text("//@version=5\nindicator(\"My indicator\")\n...") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                minLines = 8
            )
        }
        item {
            Button(
                onClick = { result = PineScriptConverter.convert(source); replayReport = null; strategyReplayReport = null },
                enabled = source.isNotBlank(),
                modifier = Modifier.fillMaxWidth()
            ) { Text("تحليل وتحويل") }
        }

        result?.let { converted ->
            item {
                InfoCard(
                    "نوع السكربت",
                    when (converted.kind) {
                        PineScriptConverter.ScriptKind.INDICATOR -> "مؤشر"
                        PineScriptConverter.ScriptKind.STRATEGY -> "استراتيجية"
                        null -> "غير محدد"
                    } + (converted.title?.let { " — $it" } ?: "")
                )
            }
            item {
                InfoCard(
                    "نتيجة التحليل",
                    if (converted.supported) "اجتاز الفحص البنيوي — لا يعني ذلك تطابق الإشارات."
                    else "تعذر التحويل الكامل؛ راجع الأخطاء."
                )
            }
            converted.errors.forEach { message ->
                item { Text("خطأ: $message", color = MaterialTheme.colorScheme.error) }
            }
            converted.warnings.forEach { message ->
                item { Text("تنبيه: $message", color = MaterialTheme.colorScheme.tertiary) }
            }
            converted.declarations.forEach { line ->
                item { Text(line, style = MaterialTheme.typography.bodySmall) }
            }
            converted.signals.forEach { signal ->
                item {
                    InfoCard(
                        signal.name,
                        "السطر ${signal.sourceLine}: ${signal.expression} — الاتجاه: ${signal.direction}. " +
                            if (signal.compiledExpression == null) "غير مترجم." else "تعبير داخلي آمن."
                    )
                }
            }
            converted.entries.forEach { entry ->
                item {
                    InfoCard(
                        "دخول ${entry.id}",
                        "${entry.direction} — الشرط: ${entry.conditionText} — قاعدة داخلية فقط."
                    )
                }
            }
            converted.exits.forEach { exit ->
                item {
                    InfoCard(
                        "خروج ${exit.id}",
                        listOfNotNull(
                            exit.fromEntry?.let { "من الدخول: $it" },
                            exit.stopText?.let { "SL/stop: $it" },
                            exit.limitText?.let { "TP/limit: $it" },
                            exit.lossTicksText?.let { "loss: $it ticks" },
                            exit.profitTicksText?.let { "profit: $it ticks" }
                        ).joinToString(" — ") + " — لا يوجد تنفيذ."
                    )
                }
            }
            item {
                Button(
                    onClick = {
                        val loaded = dataset
                        if (loaded != null) replayReport = PineReplayEngine.run(converted, loaded)
                    },
                    enabled = converted.supported && dataset != null,
                    modifier = Modifier.fillMaxWidth()
                ) { Text("إعادة اختبار الإشارات على ملف OHLC") }
            }
            if (converted.kind == PineScriptConverter.ScriptKind.STRATEGY && converted.entries.isNotEmpty()) {
                item {
                    OutlinedTextField(
                        value = tickSizeText,
                        onValueChange = { tickSizeText = it; strategyReplayReport = null },
                        label = { Text("حجم النقطة Tick size") },
                        supportingText = { Text("مطلوب فقط عند استخدام loss أو profit داخل strategy.exit.") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }
                item {
                    Button(
                        onClick = {
                            val loaded = dataset
                            if (loaded != null) {
                                val tick = tickSizeText.trim().let {
                                    if (it.isEmpty()) null else it.toDoubleOrNull() ?: Double.NaN
                                }
                                strategyReplayReport = PineStrategyReplayEngine.run(converted, loaded, tick)
                            }
                        },
                        enabled = converted.supported && dataset != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("محاكاة الاستراتيجية مع SL/TP") }
                }
                strategyReplayReport?.let { strategyReport ->
                    item {
                        if (!strategyReport.success) {
                            Text(strategyReport.errorArabic ?: "فشلت محاكاة الاستراتيجية.", color = MaterialTheme.colorScheme.error)
                        } else {
                            InfoCard(
                                "نتائج محاكاة الاستراتيجية",
                                "الشموع: ${strategyReport.totalBars} — إشارات الدخول: ${strategyReport.entrySignals} — الصفقات المغلقة: ${strategyReport.trades.size} — صافي النقاط: " +
                                    String.format(java.util.Locale.US, "%.5f", strategyReport.netProfitPoints) +
                                    " — شموع SL/TP الملتبسة: ${strategyReport.ambiguousExitBars}"
                            )
                        }
                    }
                    if (strategyReport.success &&
                        (strategyReport.comparedBars > 0 || strategyReport.unavailableComparisonBars > 0)
                    ) {
                        item {
                            val rate = strategyReport.matchRatePercent?.let {
                                String.format(java.util.Locale.US, "%.2f", it) + "%"
                            } ?: "غير متاح"
                            InfoCard(
                                "مقارنة شرط الدخول مع الإشارات المرجعية",
                                "المقارنة: ${strategyReport.comparedBars} — المتطابق: ${strategyReport.matchedBars} — المختلف: ${strategyReport.mismatchCount} — غير القابل للتقييم: ${strategyReport.unavailableComparisonBars} — نسبة التطابق: $rate. هذه مقارنة لشرط الدخول الخام وليست لمواضع تعبئة الصفقات."
                            )
                        }
                        strategyReport.mismatchSamples.forEach { mismatch ->
                            item {
                                Text(
                                    "اختلاف عند الشمعة ${mismatch.index + 1}: المتوقع ${mismatch.expected}، الفعلي ${mismatch.actual}",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    if (strategyReport.success) {
                        strategyReport.trades.takeLast(20).forEach { trade ->
                            item {
                                InfoCard(
                                    "محاكاة ${trade.direction} — ${trade.exitType}",
                                    "إشارة: ${trade.signalIndex + 1} — دخول: ${trade.entryIndex + 1} بسعر " +
                                        String.format(java.util.Locale.US, "%.5f", trade.entryPrice) +
                                        " — خروج: ${trade.exitIndex + 1} بسعر " +
                                        String.format(java.util.Locale.US, "%.5f", trade.exitPrice) +
                                        " — النتيجة: " +
                                        String.format(java.util.Locale.US, "%.5f", trade.profitPoints) +
                                        (if (trade.ambiguousBar) " — تم اختيار SL أولاً عند لمس المستويين." else "")
                                )
                            }
                        }
                        strategyReport.openPosition?.let { open ->
                            item {
                                InfoCard(
                                    "مركز مفتوح عند نهاية الملف",
                                    "${open.direction} — دخول بسعر " +
                                        String.format(java.util.Locale.US, "%.5f", open.entryPrice) +
                                        (open.stopPrice?.let { " — SL " + String.format(java.util.Locale.US, "%.5f", it) } ?: "") +
                                        (open.limitPrice?.let { " — TP " + String.format(java.util.Locale.US, "%.5f", it) } ?: "")
                                )
                            }
                        }
                        strategyReport.pendingEntrySignalIndex?.let { pendingIndex ->
                            item {
                                Text(
                                    "إشارة دخول عند الشمعة ${pendingIndex + 1} لم تتوفر بعدها شمعة تالية لتعبئة الدخول.",
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                        strategyReport.warnings.forEach { warning ->
                            item { Text("تنبيه المحاكاة: " + warning, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
            replayReport?.let { report ->
                item {
                    if (!report.success) {
                        Text(report.errorArabic ?: "فشل الاختبار.", color = MaterialTheme.colorScheme.error)
                    } else {
                        InfoCard(
                            "نتائج إعادة الاختبار",
                            "الشموع: ${report.totalBars} — CALL: ${report.callCount} — PUT: ${report.putCount} — WAIT: ${report.waitCount}"
                        )
                    }
                }
                if (report.success && report.comparedBars > 0) {
                    item {
                        InfoCard(
                            "مقارنة الإشارات المرجعية",
                            "المقارنة: ${report.comparedBars} — المتطابق: ${report.matchedBars} — المختلف: ${report.mismatchCount} — نسبة التطابق: " +
                                String.format(java.util.Locale.US, "%.2f", report.matchRatePercent ?: 0.0) + "%"
                        )
                    }
                    report.mismatchSamples.forEach { mismatch ->
                        item {
                            Text(
                                "اختلاف عند الشمعة ${mismatch.index + 1}: المتوقع ${mismatch.expected}، الفعلي ${mismatch.actual}",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                } else if (report.success) {
                    item {
                        Text(
                            "لا توجد صفوف expected_signal قابلة للمقارنة؛ قد تكون قيمها NA أو فارغة.",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                if (report.success && report.recentSignals.isNotEmpty()) {
                    item { Text("آخر إشارات غير WAIT", style = MaterialTheme.typography.titleMedium) }
                    report.recentSignals.takeLast(10).forEach { event ->
                        item {
                            Text(
                                "${event.action} — الشمعة ${event.index + 1} — ${event.activeRules.joinToString()}",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                if (report.success && report.exitsPresent) {
                    item {
                        Text(
                            "الاختبار يقارن إشارات الدخول فقط؛ لا يحاكي strategy.exit أو SL/TP.",
                            color = MaterialTheme.colorScheme.tertiary,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
        item {
            InfoCard(
                "حدود التشغيل الآمن",
                "ملف OHLC محلي يختاره المستخدم. لا يجلب ASTRA أسعاراً ولا يتصل بمصدر خارجي. إعادة الاختبار لا تفتح صفقات ولا ترسل أوامر إلى Pocket Option. قراءة الشموع المباشرة ومنفذ Demo غير موصولين."
            )
        }
    }
}

private fun readTextLimited(
    context: android.content.Context,
    uri: android.net.Uri,
    maxChars: Int
): String {
    val input = context.contentResolver.openInputStream(uri) ?: error("تعذرت قراءة الملف المحدد.")
    return input.bufferedReader().use { reader ->
        val output = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val count = reader.read(buffer)
            if (count < 0) break
            if (output.length + count > maxChars) {
                error("حجم الملف يتجاوز الحد المسموح ($maxChars حرف).")
            }
            output.append(buffer, 0, count)
        }
        output.toString()
    }
}

@Composable
private fun SimplePage(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description)
    }
}
