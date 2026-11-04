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
import com.astra.autotrader.engine.EngineState
import com.astra.autotrader.engine.TradingForegroundService

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
                    Text(if (running) "يعمل — وضع تجريبي" else "متوقف", color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    Text("التداول الحقيقي معطّل حتى اكتمال فحص بيانات الشارت والتنفيذ.")
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
        item { InfoCard("آخر إشارة", "انتظار بيانات الشموع") }
        item { InfoCard("وضع التشغيل", "تجريبي فقط — لا يتم إرسال صفقات حقيقية") }
    }
}

@Composable
private fun InfoCard(title: String, value: String) {
    Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { Text(title, style = MaterialTheme.typography.labelLarge); Text(value) } }
}

@Composable
private fun PocketBrowser(modifier: Modifier = Modifier) {
    var address by remember { mutableStateOf("https://pocketoption.com") }
    Column(modifier.fillMaxSize()) {
        Text("متصفح Pocket Option", Modifier.padding(12.dp), style = MaterialTheme.typography.titleMedium)
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    webViewClient = WebViewClient()
                    loadUrl(address)
                }
            }
        )
    }
}

@Composable
private fun StrategyScreen(modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("الاستراتيجيات", style = MaterialTheme.typography.headlineSmall) }
        item { InfoCard("تقاطع MACD", "الإعدادات الافتراضية: 12 / 26 / 9 — يحتاج ربط بيانات الشموع.") }
        item { InfoCard("الدعم والمقاومة", "كشف القمم والقيعان والاختراق وإعادة الاختبار — قيد التطوير.") }
        item { InfoCard("استيراد TradingView", "إضافة كود Pine Script وتحليل شروطه عبر OpenRouter — غير موصول بعد.") }
        item { InfoCard("إعدادات OpenRouter", "أضف المفتاح لاحقاً من شاشة الإعدادات الآمنة. لا تضعه داخل ملفات المشروع.") }
    }
}

@Composable
private fun SimplePage(title: String, description: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text(description)
    }
}
