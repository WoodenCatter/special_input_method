package com.example.input_ds.ui.bci

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.input_ds.bci.DataCollector

@Composable
fun CollectionScreen(collector: DataCollector, onBack: () -> Unit) {
    var status by remember { mutableStateOf("准备开始采集") }
    var progress by remember { mutableStateOf("") }
    var log by remember { mutableStateOf(listOf<String>()) }
    var running by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        collector.onStateChange = { status = it }
        collector.onProgress = { cur, total, cls -> progress = "$cur/$total — $cls" }
        collector.onTrialSaved = { msg -> log = log + msg }
    }

    Column(Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
            Text("数据采集", fontSize = 20.sp, color = Color(0xFF5B8DEF), fontWeight = FontWeight.Bold)
            TextButton(onClick = { collector.stop(); onBack() }) { Text("返回") }
        }
        Spacer(Modifier.height(12.dp))
        Text("范式: 4 类 × 20 试次 = 80 个 trial", fontSize = 12.sp, color = Color(0xFF9E9E9E))
        Text("休息 / 咬牙 / 左看 / 右看", fontSize = 12.sp, color = Color(0xFF9E9E9E))
        Text("每次提示音后执行动作 1.1 秒", fontSize = 12.sp, color = Color(0xFF9E9E9E))
        Spacer(Modifier.height(16.dp))

        if (!running) {
            Button(onClick = { running = true; collector.startCollection() }, modifier = Modifier.fillMaxWidth()) {
                Text("开始采集 (80试次)")
            }
        } else {
            Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A2E))) {
                Column(Modifier.padding(12.dp)) {
                    Text(status, color = Color(0xFFFFD740), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text(progress, color = Color(0xFF4CAF50), fontSize = 14.sp)
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        Text("日志:", fontSize = 12.sp, color = Color(0xFF9E9E9E))
        Column(Modifier.weight(1f)) {
            log.takeLast(20).forEach { msg ->
                Text(msg, fontSize = 11.sp, color = if (msg.startsWith("✅")) Color(0xFF4CAF50) else Color(0xFFFF9800))
            }
        }
    }
}
