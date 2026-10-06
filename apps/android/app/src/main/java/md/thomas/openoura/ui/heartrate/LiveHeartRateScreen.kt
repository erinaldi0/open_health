package md.thomas.openoura.ui.heartrate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import md.thomas.openoura.ui.Obs
import md.thomas.openoura.ui.ObsTag
import md.thomas.openoura.ui.Rule
import md.thomas.openoura.ui.obsCard

@Composable
fun LiveHeartRateScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveHeartRateViewModel = viewModel()
) {
    val colors = Obs.colors
    val uiState by viewModel.uiState.collectAsState()
    val logListState = rememberLazyListState()

    DisposableEffect(Unit) {
        onDispose {
            viewModel.stopLiveHeartRate()
        }
    }

    // Auto-scroll logs to bottom as new messages come in
    LaunchedEffect(uiState.statusMessage) {
        if (uiState.statusMessage.isNotEmpty()) {
            logListState.animateScrollToItem(index = Int.MAX_VALUE)
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Top Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "‹ back",
                    fontFamily = Obs.mono,
                    fontSize = 13.sp,
                    color = colors.link,
                    modifier = Modifier.clickable(onClick = onBack)
                )
                Text(
                    text = "Live Heart Rate",
                    fontFamily = Obs.serif,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium,
                    color = colors.ink
                )
                ObsTag(if (uiState.isMonitoring) "active" else "idle")
            }
        }

        // Live Metric Display Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .obsCard(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Rule("realtime telemetry")
                    if (uiState.isConnecting) {
                        Text(
                            text = "connecting…",
                            fontFamily = Obs.mono,
                            fontSize = 11.sp,
                            color = colors.muted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    // A weak signal's number is not just noisy but wrong (143 vs a true 72), so hide it.
                    text = uiState.bpm?.takeUnless { uiState.weakSignal }?.toString() ?: "--",
                    fontFamily = Obs.serif,
                    fontSize = 68.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (uiState.isMonitoring && !uiState.weakSignal) colors.good else colors.ink
                )

                Text(
                    text = "BEATS PER MINUTE",
                    fontFamily = Obs.mono,
                    fontSize = 11.sp,
                    color = colors.muted
                )

                if (uiState.isMonitoring) {
                    Text(
                        text = when {
                            uiState.weakSignal -> "weak signal — keep your hand still"
                            uiState.bpm == null -> "measuring…"
                            else -> "10 s average · ${uiState.beatsInWindow} beats"
                        },
                        fontFamily = Obs.mono,
                        fontSize = 11.sp,
                        color = if (uiState.weakSignal) colors.bad else colors.muted
                    )
                }

                uiState.ibiMs?.let { ibi ->
                    Text(
                        text = "last beat $ibi ms",
                        fontFamily = Obs.mono,
                        fontSize = 13.sp,
                        color = colors.ink2
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Action Button styled to match Obs theme
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (uiState.isMonitoring || uiState.isConnecting) colors.paper else colors.ink
                        )
                        .border(1.dp, colors.rule)
                        .clickable {
                            if (uiState.isMonitoring || uiState.isConnecting) {
                                viewModel.stopLiveHeartRate()
                            } else {
                                viewModel.startLiveHeartRate()
                            }
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when {
                            uiState.isConnecting -> "Stop Connecting"
                            uiState.isMonitoring -> "Stop Stream"
                            else -> "Start Live Stream"
                        },
                        fontFamily = Obs.mono,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (uiState.isMonitoring || uiState.isConnecting) colors.ink else colors.paper
                    )
                }
            }
        }

        // Reference reading: ground truth from another device, logged next to the raw frames
        item {
            ReferenceCard(
                references = uiState.references,
                onLog = viewModel::logReference,
            )
        }

        // Debug Logs Card
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .obsCard(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Rule("debug logs")

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .background(colors.paper)
                        .border(1.dp, colors.rule)
                        .padding(10.dp)
                ) {
                    if (uiState.statusMessage.isEmpty()) {
                        Text(
                            text = "No logs yet. Press 'Start Live Stream' to begin GATT discovery.",
                            fontFamily = Obs.mono,
                            fontSize = 11.sp,
                            color = colors.muted
                        )
                    } else {
                        LazyColumn(
                            state = logListState,
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val logLines = uiState.statusMessage.split("\n")
                            items(logLines.size) { index ->
                                Text(
                                    text = logLines[index],
                                    fontFamily = Obs.mono,
                                    fontSize = 11.sp,
                                    color = if (logLines[index].contains("error", ignoreCase = true) || logLines[index].contains("fail", ignoreCase = true)) colors.bad else colors.ink2
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
@Composable
private fun ReferenceCard(
    references: List<ReferenceReading>,
    onLog: (bpm: Int, atMs: Long) -> Unit,
) {
    val colors = Obs.colors
    var text by remember { mutableStateOf("") }
    // Stamped on the first keystroke: the moment the user read the other device.
    var readAtMs by remember { mutableStateOf<Long?>(null) }
    val bpm = text.toIntOrNull()?.takeIf { it in 30..220 }
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    fun submit() {
        val value = bpm ?: return
        onLog(value, readAtMs ?: System.currentTimeMillis())
        text = ""
        readAtMs = null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .obsCard(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Rule("reference reading")
        Text(
            text = "Read your watch, type its bpm, tap log. The time is taken when you start typing.",
            fontFamily = Obs.mono,
            fontSize = 11.sp,
            color = colors.muted
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = { new ->
                    val digits = new.filter { it.isDigit() }.take(3)
                    if (digits.isNotEmpty() && readAtMs == null) readAtMs = System.currentTimeMillis()
                    if (digits.isEmpty()) readAtMs = null
                    text = digits
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                textStyle = TextStyle(fontFamily = Obs.mono, fontSize = 15.sp, color = colors.ink),
                cursorBrush = SolidColor(colors.ink),
                modifier = Modifier
                    .width(70.dp)
                    .background(colors.rule.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
            Text("bpm", fontFamily = Obs.mono, fontSize = 11.sp, color = colors.muted)
            Spacer(Modifier.weight(1f))
            Text(
                text = "log",
                fontFamily = Obs.mono,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = if (bpm != null) colors.paper else colors.muted,
                modifier = Modifier
                    .background(if (bpm != null) colors.ink else colors.rule.copy(alpha = 0.35f))
                    .clickable(enabled = bpm != null) { submit() }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        references.asReversed().forEach { r ->
            Text(
                text = "${timeFmt.format(Date(r.atMs))}  watch ${r.referenceBpm}  ·  app ${r.appBpm ?: "--"}",
                fontFamily = Obs.mono,
                fontSize = 12.sp,
                color = colors.ink2
            )
        }
    }
}
