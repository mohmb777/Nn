package com.astra.autotrader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
                    listOf("لوحة التحكم", "المتصفح", "الاستراتيجيات", "السجل").forEach {
                        NavigationBarItem(
                            selected = selected == it,
                            onClick = { selected = it },
                            icon = { Text(when (it) { "المتصفح" -> "🌐"; "الاستراتيجيات" -> "📊"; "السجل" -> "📋"; else -> "⌂" }) },
                            label = { Text(it) }
                        )
                    }
                }
            }
        ) { padding ->
            when (selected) {
                "المتصفح" -> PocketBrowser(Modifier.padding(padding))
                "الاستراتيجيات" -> StrategyScreen(Modifier.padding(padding))
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
            listOf("macd_12_26_9" to "تقاطع MACD", "support_resistance" to "الدعم والمقاومة").forEach { item ->
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
        item { InfoCard("استيراد TradingView", "تحليل كود Pine Script غير موصول بعد. لا يتم تشغيل كود خارجي داخل التطبيق.") }
        item { InfoCard("إعدادات OpenRouter", "غير موصولة بعد. لا تضع مفتاح API في ملفات المشروع أو داخل صفحة الويب.") }
    }
}

@Composable
private fun SimplePage(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description)
    }
}
