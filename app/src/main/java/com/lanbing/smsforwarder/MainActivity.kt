package com.lanbing.smsforwarder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * MainActivity (final): includes Material3 theme (light/dark), immersive status bar setup,
 * and the full SmsForwarderApp composable in the same file so CI can compile.
 *
 * Change in this version:
 * - Make the log Card adaptive: use Modifier.weight(1f) on the Card inside the full-screen Column,
 *   and make the inner LazyColumn use Modifier.fillMaxSize(). This makes the log area expand to fill
 *   available space and adapt to different screen sizes instead of a fixed max height.
 *
 * Replace app/src/main/java/com/lanbing/smsforwarder/MainActivity.kt with this file.
 */

class MainActivity : ComponentActivity() {

    private lateinit var requestSmsPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var requestNotifPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var requestSendSmsPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var requestReadPhoneStateLauncher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("app_config", Context.MODE_PRIVATE)

        requestSmsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Toast.makeText(this, "短信权限已授权", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "请授予短信权限以接收短信", Toast.LENGTH_LONG).show()
        }

        requestNotifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "请允许通知权限以显示常驻通知", Toast.LENGTH_LONG).show()
        }

        requestSendSmsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Toast.makeText(this, "发送短信权限已授权", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "请授予发送短信权限以使用短信通道", Toast.LENGTH_LONG).show()
        }

        // READ_PHONE_STATE result: refresh SIM list immediately if granted.
        requestReadPhoneStateLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            prefs.edit().putBoolean("sim_perm_granted", granted).apply()
            if (granted) {
                Toast.makeText(this, "已允许读取卡信息，正在刷新卡信息...", Toast.LENGTH_SHORT).show()
                SimHelper.refresh(this)
            } else {
                Toast.makeText(this, "未授权读取卡信息，无法显示 SIM 选项", Toast.LENGTH_LONG).show()
            }
        }

        // If permission already granted at startup, proactively refresh SIM list so UI can select immediately.
        val initialRead = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        if (initialRead) {
            SimHelper.refresh(this)
        }

        setContent {
            // Theme selection based on system
            val isDarkTheme = isSystemInDarkTheme()
            val lightColors = lightColorScheme(
                primary = Color(0xFF2563EB),
                onPrimary = Color.White,
                background = Color(0xFFF8F6F0),
                surface = Color.White,
                onSurface = Color(0xFF111827),
                surfaceVariant = Color(0xFFF7F8FA)
            )
            val darkColors = darkColorScheme(
                primary = Color(0xFF60A5FA),
                onPrimary = Color.Black,
                background = Color(0xFF0B1220),
                surface = Color(0xFF111827),
                onSurface = Color(0xFFE6EEF8),
                surfaceVariant = Color(0xFF0F1724)
            )

            MaterialTheme(colorScheme = if (isDarkTheme) darkColors else lightColors, typography = Typography()) {
                // Configure immersive system bars and icon color according to theme
                val activity = LocalContext.current as Activity
                SideEffect {
                    try {
                        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
                        activity.window.statusBarColor = AndroidColor.TRANSPARENT
                        val controller = WindowInsetsControllerCompat(activity.window, activity.window.decorView)
                        controller.isAppearanceLightStatusBars = !isDarkTheme
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            controller.isAppearanceLightNavigationBars = !isDarkTheme
                        }
                    } catch (_: Throwable) {
                    }
                }

                SmsForwarderApp(
                    onRequestSmsPermission = { requestSmsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS) },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    },
                    onRequestSendSmsPermission = { requestSendSmsPermissionLauncher.launch(Manifest.permission.SEND_SMS) },
                    onRequestReadPhoneState = {
                        val already = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                        if (already) SimHelper.refresh(this) else requestReadPhoneStateLauncher.launch(Manifest.permission.READ_PHONE_STATE)
                    },
                    onStartService = { startServiceWithNotificationCheck() },
                    onStopService = { onStopService() }
                )
            }
        }
    }

    private fun onStopService() {
        val svc = Intent(this, SmsForegroundService::class.java)
        stopService(svc)
    }

    private fun startServiceWithNotificationCheck() {
        val pkg = packageName
        val notifEnabled = NotificationManagerCompat.from(this).areNotificationsEnabled()
        if (!notifEnabled) {
            Toast.makeText(this, "请允许应用通知（将打开通知设置）", Toast.LENGTH_LONG).show()
            val i = Intent().apply {
                action = Settings.ACTION_APP_NOTIFICATION_SETTINGS
                putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            }
            startActivity(i)
            return
        }

        val smsGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        if (!smsGranted) return

        val svc = Intent(this, SmsForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(svc) else startService(svc)
    }
}

/* ---------- SimHelper ---------- */
object SimHelper {
    private val _simList = MutableStateFlow<List<Pair<Int, String>>>(emptyList())
    val simListState: StateFlow<List<Pair<Int, String>>> = _simList

    fun refresh(context: Context) {
        try {
            val readGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
            if (!readGranted) {
                _simList.update { emptyList() }
                return
            }
            val sm = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val infos: List<SubscriptionInfo>? = sm.activeSubscriptionInfoList
            if (infos == null || infos.isEmpty()) _simList.update { emptyList() }
            else {
                val list = infos.map { info ->
                    val carrier = info.carrierName?.toString() ?: "SIM ${info.simSlotIndex + 1}"
                    val label = "$carrier (subId=${info.subscriptionId})"
                    Pair(info.subscriptionId, label)
                }
                _simList.update { list }
            }
        } catch (t: Throwable) {
            _simList.update { emptyList() }
        }
    }
}

/* ---------- UI: SmsForwarderApp and helpers (kept inline so compile succeeds) ---------- */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsForwarderApp(
    onRequestSmsPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestSendSmsPermission: () -> Unit,
    onRequestReadPhoneState: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)

    var isEnabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var startOnBoot by remember { mutableStateOf(prefs.getBoolean("start_on_boot", false)) }

    var channels by remember { mutableStateOf(loadChannels(prefs)) }
    var configs by remember { mutableStateOf(loadConfigs(prefs)) }

    // Add channel / config fields
    var newChannelName by remember { mutableStateOf("") }
    var newChannelTarget by remember { mutableStateOf("") }
    var newChannelType by remember { mutableStateOf(ChannelType.WECHAT) }
    var newChannelSimSubId by remember { mutableStateOf<Int?>(null) }

    var newKeywordInput by remember { mutableStateOf("") }
    var selectedChannelIdForNewCfg by remember { mutableStateOf(channels.firstOrNull()?.id ?: "") }
    var newChannelDropdownExpanded by remember { mutableStateOf(false) }

    // Collect SIM list from SimHelper
    val simList by SimHelper.simListState.collectAsState()

    // Editing state
    var editingChannel by remember { mutableStateOf<Channel?>(null) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var editChannelName by remember { mutableStateOf("") }
    var editChannelTarget by remember { mutableStateOf("") }
    var editChannelType by remember { mutableStateOf(ChannelType.WECHAT) }
    var editChannelSimSubId by remember { mutableStateOf<Int?>(null) }

    var editingConfig by remember { mutableStateOf<KeywordConfig?>(null) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var editConfigKeyword by remember { mutableStateOf("") }
    var editConfigChannelId by remember { mutableStateOf("") }

    var channelTypeExpanded by remember { mutableStateOf(false) }
    var logs by remember { mutableStateOf(LogStore.readAll(context)) }
    var currentTab by remember { mutableStateOf(0) }

    // Use surfaceVariant as subtle card color; theme aware
    val cardColor = MaterialTheme.colorScheme.surfaceVariant

    Scaffold(
        topBar = {
            TopAppBar(modifier = Modifier.statusBarsPadding(), title = { Text("短信转发助手", fontSize = 20.sp, fontWeight = FontWeight.Bold) })
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(selected = currentTab == 0, onClick = { currentTab = 0 }, icon = { Icon(Icons.Filled.Tune, contentDescription = "配置") }, label = { Text("配置") })
                NavigationBarItem(selected = currentTab == 1, onClick = { currentTab = 1 }, icon = { Icon(Icons.Filled.History, contentDescription = "日志") }, label = { Text("日志") })
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            if (currentTab == 0) {
                LazyColumn(modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    item {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("转发服务", style = MaterialTheme.typography.titleMedium)
                                        Spacer(Modifier.height(6.dp))
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            val statusColor = if (isEnabled) Color(0xFF10B981) else Color(0xFF9CA3AF)
                                            Box(modifier = Modifier.size(8.dp).background(statusColor, shape = MaterialTheme.shapes.small))
                                            Spacer(Modifier.width(8.dp))
                                            Text(if (isEnabled) "服务已开启" else "服务已关闭", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                    }
                                    Switch(checked = isEnabled, onCheckedChange = { checked ->
                                        isEnabled = checked
                                        prefs.edit().putBoolean("enabled", isEnabled).apply()
                                        if (checked) {
                                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                val hasNotif = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                                if (!hasNotif) onRequestNotificationPermission()
                                            }
                                            val hasSms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                                            if (!hasSms) onRequestSmsPermission()
                                            if (channels.any { it.type == ChannelType.SMS }) {
                                                val sendSmsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                                                if (!sendSmsGranted) onRequestSendSmsPermission()
                                                val readPhoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                                                if (!readPhoneGranted) onRequestReadPhoneState()
                                            }
                                            onStartService()
                                            LogStore.append(context, "服务已启动（由用户开启）")
                                        } else {
                                            onStopService()
                                            LogStore.append(context, "服务已停止（由用户关闭）")
                                        }
                                        context.sendBroadcast(Intent(SmsForegroundService.ACTION_UPDATE))
                                    })
                                }

                                Divider(modifier = Modifier.padding(vertical = 12.dp))

                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Notifications, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("通知权限")
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { onRequestNotificationPermission() }) { Text("去开启") }
                                }
                                Divider(modifier = Modifier.padding(vertical = 8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Security, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("短信权限")
                                    Spacer(Modifier.weight(1f))
                                    TextButton(onClick = { onRequestSmsPermission() }) { Text("去开启") }
                                }

                                Spacer(Modifier.height(8.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.PowerSettingsNew, contentDescription = null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("开机自启动")
                                    Spacer(Modifier.weight(1f))
                                    Switch(checked = startOnBoot, onCheckedChange = {
                                        startOnBoot = it
                                        prefs.edit().putBoolean("start_on_boot", startOnBoot).apply()
                                        if (startOnBoot) LogStore.append(context, "已开启开机启动") else LogStore.append(context, "已关闭开机启动")
                                    })
                                }
                            }
                        }
                    }

                    item {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("关键词配置", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(value = newKeywordInput, onValueChange = { newKeywordInput = it }, label = { Text("输入关键词（留空表示全部）") }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                var channelDropdownExpanded by remember { mutableStateOf(false) }
                                ExposedDropdownMenuBox(expanded = channelDropdownExpanded, onExpandedChange = { channelDropdownExpanded = !channelDropdownExpanded }) {
                                    OutlinedTextField(
                                        value = channels.find { it.id == selectedChannelIdForNewCfg }?.name ?: "选择转发通道",
                                        onValueChange = {},
                                        readOnly = true,
                                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelDropdownExpanded) }
                                    )
                                    ExposedDropdownMenu(expanded = channelDropdownExpanded, onDismissRequest = { channelDropdownExpanded = false }) {
                                        channels.forEach { ch ->
                                            DropdownMenuItem(text = { Text(ch.name) }, onClick = {
                                                selectedChannelIdForNewCfg = ch.id
                                                channelDropdownExpanded = false
                                            })
                                        }
                                        if (channels.isEmpty()) {
                                            DropdownMenuItem(text = { Text("请先添加通道") }, onClick = { channelDropdownExpanded = false })
                                        }
                                    }
                                }
                                Button(onClick = {
                                    if (channels.isEmpty()) { Toast.makeText(context, "请先添加通道", Toast.LENGTH_SHORT).show(); return@Button }
                                    if (selectedChannelIdForNewCfg.isBlank()) { Toast.makeText(context, "请选择通道", Toast.LENGTH_SHORT).show(); return@Button }
                                    val newCfg = KeywordConfig(UUID.randomUUID().toString(), newKeywordInput.trim(), selectedChannelIdForNewCfg)
                                    configs = configs + newCfg
                                    saveConfigs(prefs, configs)
                                    newKeywordInput = ""
                                    LogStore.append(context, "添加关键词: ${newCfg.keyword} -> ${channels.find { it.id == newCfg.channelId }?.name}")
                                    Toast.makeText(context, "配置已添加", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.fillMaxWidth()) { Text("添加配置") }

                                Spacer(Modifier.height(8.dp))
                                configs.forEach { cfg ->
                                    val chName = channels.find { it.id == cfg.channelId }?.name ?: "(已删除通道)"
                                    Row(modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(if (cfg.keyword.isBlank()) "全部消息" else cfg.keyword, style = MaterialTheme.typography.titleSmall)
                                            Spacer(Modifier.height(6.dp))
                                            Text("→ $chName", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                        IconButton(onClick = {
                                            editingConfig = cfg
                                            editConfigKeyword = cfg.keyword
                                            editConfigChannelId = cfg.channelId
                                            showConfigDialog = true
                                        }) { Icon(Icons.Filled.Edit, contentDescription = "编辑配置") }
                                        IconButton(onClick = {
                                            configs = configs.filterNot { it.id == cfg.id }
                                            saveConfigs(prefs, configs)
                                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除配置", tint = Color(0xFFEE4444)) }
                                    }
                                }
                            }
                        }
                    }

                    item {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cardColor)) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("转发通道管理", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(value = newChannelName, onValueChange = { newChannelName = it }, label = { Text("通道名称（示例：微信企业群）") }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(8.dp))
                                ExposedDropdownMenuBox(expanded = channelTypeExpanded, onExpandedChange = { channelTypeExpanded = !channelTypeExpanded }) {
                                    val typeLabel = when (newChannelType) {
                                        ChannelType.WECHAT -> "企微"
                                        ChannelType.DINGTALK -> "钉钉"
                                        ChannelType.GENERIC_WEBHOOK -> "Webhook"
                                        ChannelType.SMS -> "手机短信"
                                    }
                                    OutlinedTextField(value = typeLabel, onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = channelTypeExpanded) })
                                    ExposedDropdownMenu(expanded = channelTypeExpanded, onDismissRequest = { channelTypeExpanded = false }) {
                                        listOf(ChannelType.WECHAT, ChannelType.DINGTALK, ChannelType.GENERIC_WEBHOOK, ChannelType.SMS).forEach { t ->
                                            val label = when (t) {
                                                ChannelType.WECHAT -> "企微"
                                                ChannelType.DINGTALK -> "钉钉"
                                                ChannelType.GENERIC_WEBHOOK -> "Webhook"
                                                ChannelType.SMS -> "手机短信"
                                            }
                                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                                newChannelType = t
                                                channelTypeExpanded = false
                                            })
                                        }
                                    }
                                }

                                OutlinedTextField(value = newChannelTarget, onValueChange = { newChannelTarget = it }, label = { Text(if (newChannelType == ChannelType.SMS) "目标手机号（例如 +8613912345678）" else "Webhook 地址") }, modifier = Modifier.fillMaxWidth())

                                if (newChannelType == ChannelType.SMS) {
                                    Spacer(Modifier.height(8.dp))
                                    var simExpanded by remember { mutableStateOf(false) }
                                    val simLabel = newChannelSimSubId?.let { id -> simList.find { it.first == id }?.second } ?: "默认 SIM"
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("发出卡：", modifier = Modifier.padding(end = 12.dp))
                                        if (simList.isEmpty()) {
                                            TextButton(onClick = { onRequestReadPhoneState() }) { Text("获取卡信息") }
                                        } else {
                                            ExposedDropdownMenuBox(expanded = simExpanded, onExpandedChange = {
                                                SimHelper.refresh(context)
                                                simExpanded = !simExpanded
                                            }) {
                                                OutlinedTextField(value = simLabel, onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor().width(220.dp), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(simExpanded) })
                                                ExposedDropdownMenu(expanded = simExpanded, onDismissRequest = { simExpanded = false }) {
                                                    DropdownMenuItem(text = { Text("默认 SIM") }, onClick = {
                                                        newChannelSimSubId = null
                                                        simExpanded = false
                                                    })
                                                    simList.forEach { (subId, label) ->
                                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                                            newChannelSimSubId = subId
                                                            simExpanded = false
                                                        })
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(8.dp))
                                Button(onClick = {
                                    if (newChannelName.isBlank() || newChannelTarget.isBlank()) { Toast.makeText(context, "请填写通道名称和目标", Toast.LENGTH_SHORT).show(); return@Button }
                                    if (newChannelType == ChannelType.SMS) {
                                        val sendSmsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
                                        if (!sendSmsGranted) { Toast.makeText(context, "需要发送短信权限以使用短信通道，将请求权限", Toast.LENGTH_SHORT).show(); onRequestSendSmsPermission() }
                                        val readPhoneGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
                                        if (!readPhoneGranted) onRequestReadPhoneState() else SimHelper.refresh(context)
                                    }
                                    val newChannel = Channel(UUID.randomUUID().toString(), newChannelName.trim(), newChannelType, newChannelTarget.trim(), newChannelSimSubId)
                                    channels = channels + newChannel
                                    saveChannels(prefs, channels)
                                    selectedChannelIdForNewCfg = channels.firstOrNull()?.id ?: ""
                                    newChannelName = ""
                                    newChannelTarget = ""
                                    newChannelSimSubId = null
                                    LogStore.append(context, "添加通道: ${newChannel.name} (${newChannel.type})")
                                    Toast.makeText(context, "通道已添加", Toast.LENGTH_SHORT).show()
                                }, modifier = Modifier.fillMaxWidth()) { Text("添加通道") }

                                Spacer(Modifier.height(12.dp))
                                channels.forEach { ch ->
                                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(ch.name, style = MaterialTheme.typography.titleSmall)
                                            val typeLabel = when (ch.type) {
                                                ChannelType.WECHAT -> "企微"
                                                ChannelType.DINGTALK -> "钉钉"
                                                ChannelType.GENERIC_WEBHOOK -> "Webhook"
                                                ChannelType.SMS -> "短信"
                                            }
                                            val simInfo = if (ch.type == ChannelType.SMS) ch.simSubscriptionId?.let { id -> " (SIM subId=$id)" } ?: "" else ""
                                            Text("$typeLabel → ${ch.target}$simInfo", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                        }
                                        IconButton(onClick = {
                                            editingChannel = ch
                                            editChannelName = ch.name
                                            editChannelTarget = ch.target
                                            editChannelType = ch.type
                                            editChannelSimSubId = ch.simSubscriptionId
                                            showChannelDialog = true
                                        }) { Icon(Icons.Filled.Edit, contentDescription = "编辑通道") }
                                        IconButton(onClick = {
                                            channels = channels.filterNot { it.id == ch.id }
                                            saveChannels(prefs, channels)
                                            configs = configs.filterNot { it.channelId == ch.id }
                                            saveConfigs(prefs, configs)
                                        }) { Icon(Icons.Filled.Delete, contentDescription = "删除通道", tint = Color(0xFFEE4444)) }
                                    }
                                    Divider()
                                }
                            }
                        }
                    }

                    item { Spacer(Modifier.height(32.dp)) }
                }
            } else {
                // Log page: make Card adapt to remaining height by using weight(1f)
                Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
                    Card(modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                        colors = CardDefaults.cardColors(containerColor = cardColor)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                Text("转发日志", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.weight(1f))
                                Row {
                                    IconButton(onClick = { logs = LogStore.readAll(context) }) { Icon(Icons.Filled.Refresh, contentDescription = "刷新") }
                                    IconButton(onClick = { LogStore.clear(context); logs = emptyList(); Toast.makeText(context, "日志已清除", Toast.LENGTH_SHORT).show() }) { Icon(Icons.Filled.ClearAll, contentDescription = "清除") }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            if (logs.isEmpty()) {
                                Text("暂无日志", style = MaterialTheme.typography.bodyMedium)
                            } else {
                                // Fill remaining area of the Card so the list grows/shrinks with screen size
                                LazyColumn(modifier = Modifier.fillMaxSize()) {
                                    items(logs) { line ->
                                        val tsRegex = """^\[(.*?)\]\s*(.*)$""".toRegex()
                                        val match = tsRegex.find(line)
                                        val time = match?.groups?.get(1)?.value ?: ""
                                        val msg = match?.groups?.get(2)?.value ?: line
                                        val isSuccess = msg.contains("成功") || msg.contains("已启动") || msg.contains("转发成功")
                                        val isError = msg.contains("失败") || msg.contains("异常") || msg.contains("转发失败")
                                        val icon = when {
                                            isSuccess -> Icons.Filled.CheckCircle
                                            isError -> Icons.Filled.Error
                                            else -> Icons.Filled.Info
                                        }
                                        val iconTint = when {
                                            isSuccess -> Color(0xFF10B981)
                                            isError -> Color(0xFFEE4444)
                                            else -> Color(0xFF3B82F6)
                                        }
                                        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(icon, contentDescription = null, tint = iconTint)
                                            Spacer(Modifier.width(8.dp))
                                            Column {
                                                Text(msg, style = MaterialTheme.typography.bodyMedium)
                                                Spacer(Modifier.height(4.dp))
                                                Text(time, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                            }
                                        }
                                        Divider()
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (showChannelDialog && editingChannel != null) {
                AlertDialog(onDismissRequest = { showChannelDialog = false; editingChannel = null },
                    title = { Text("编辑通道") },
                    text = {
                        Column {
                            OutlinedTextField(value = editChannelName, onValueChange = { editChannelName = it }, label = { Text("通道名称") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            var editTypeExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = editTypeExpanded, onExpandedChange = { editTypeExpanded = !editTypeExpanded }) {
                                val editTypeLabel = when (editChannelType) {
                                    ChannelType.WECHAT -> "企微"
                                    ChannelType.DINGTALK -> "钉钉"
                                    ChannelType.GENERIC_WEBHOOK -> "Webhook"
                                    ChannelType.SMS -> "短信"
                                }
                                OutlinedTextField(value = editTypeLabel, onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor().fillMaxWidth(), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(editTypeExpanded) })
                                ExposedDropdownMenu(expanded = editTypeExpanded, onDismissRequest = { editTypeExpanded = false }) {
                                    listOf(ChannelType.WECHAT, ChannelType.DINGTALK, ChannelType.GENERIC_WEBHOOK, ChannelType.SMS).forEach { t ->
                                        val label = when (t) {
                                            ChannelType.WECHAT -> "企微"
                                            ChannelType.DINGTALK -> "钉钉"
                                            ChannelType.GENERIC_WEBHOOK -> "Webhook"
                                            ChannelType.SMS -> "短信"
                                        }
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            editChannelType = t
                                            editTypeExpanded = false
                                        })
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = editChannelTarget, onValueChange = { editChannelTarget = it }, label = { Text(if (editChannelType == ChannelType.SMS) "目标手机号" else "Webhook 地址") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            if (editChannelType == ChannelType.SMS) {
                                var editSimExpanded by remember { mutableStateOf(false) }
                                val editSimLabel = editChannelSimSubId?.let { id -> simList.find { it.first == id }?.second } ?: "默认 SIM"
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("发出卡：", modifier = Modifier.padding(end = 12.dp))
                                    if (simList.isEmpty()) {
                                        TextButton(onClick = { onRequestReadPhoneState() }) { Text("获取卡信息") }
                                    } else {
                                        ExposedDropdownMenuBox(expanded = editSimExpanded, onExpandedChange = {
                                            SimHelper.refresh(context)
                                            editSimExpanded = !editSimExpanded
                                        }) {
                                            OutlinedTextField(value = editSimLabel, onValueChange = {}, readOnly = true, modifier = Modifier.menuAnchor().width(220.dp), trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(editSimExpanded) })
                                            ExposedDropdownMenu(expanded = editSimExpanded, onDismissRequest = { editSimExpanded = false }) {
                                                DropdownMenuItem(text = { Text("默认 SIM") }, onClick = {
                                                    editChannelSimSubId = null
                                                    editSimExpanded = false
                                                })
                                                simList.forEach { (subId, label) ->
                                                    DropdownMenuItem(text = { Text(label) }, onClick = {
                                                        editChannelSimSubId = subId
                                                        editSimExpanded = false
                                                    })
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val ch = editingChannel ?: return@TextButton
                            val updated = Channel(ch.id, editChannelName.trim(), editChannelType, editChannelTarget.trim(), editChannelSimSubId)
                            channels = channels.map { if (it.id == ch.id) updated else it }
                            saveChannels(prefs, channels)
                            LogStore.append(context, "编辑通道: ${updated.name}")
                            showChannelDialog = false
                            editingChannel = null
                        }) { Text("保存") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showChannelDialog = false; editingChannel = null }) { Text("取消") }
                    }
                )
            }

            if (showConfigDialog && editingConfig != null) {
                AlertDialog(onDismissRequest = { showConfigDialog = false; editingConfig = null },
                    title = { Text("编辑关键词配置") },
                    text = {
                        Column {
                            OutlinedTextField(value = editConfigKeyword, onValueChange = { editConfigKeyword = it }, label = { Text("关键词（留空表示全部）") }, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            var editCfgExpanded by remember { mutableStateOf(false) }
                            ExposedDropdownMenuBox(expanded = editCfgExpanded, onExpandedChange = { editCfgExpanded = !editCfgExpanded }) {
                                OutlinedTextField(
                                    value = channels.find { it.id == editConfigChannelId }?.name ?: "选择通道",
                                    onValueChange = {},
                                    readOnly = true,
                                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(editCfgExpanded) }
                                )
                                ExposedDropdownMenu(expanded = editCfgExpanded, onDismissRequest = { editCfgExpanded = false }) {
                                    channels.forEach { ch ->
                                        DropdownMenuItem(text = { Text(ch.name) }, onClick = {
                                            editConfigChannelId = ch.id
                                            editCfgExpanded = false
                                        })
                                    }
                                    if (channels.isEmpty()) {
                                        DropdownMenuItem(text = { Text("请先添加通道") }, onClick = { editCfgExpanded = false })
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val cfg = editingConfig ?: return@TextButton
                            if (editConfigChannelId.isBlank()) { Toast.makeText(context, "请选择通道", Toast.LENGTH_SHORT).show(); return@TextButton }
                            val updated = KeywordConfig(cfg.id, editConfigKeyword.trim(), editConfigChannelId)
                            configs = configs.map { if (it.id == cfg.id) updated else it }
                            saveConfigs(prefs, configs)
                            LogStore.append(context, "编辑关键词: ${updated.keyword} -> ${channels.find { it.id == updated.channelId }?.name}")
                            showConfigDialog = false
                            editingConfig = null
                        }) { Text("保存") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showConfigDialog = false; editingConfig = null }) { Text("取消") }
                    }
                )
            }
        }
    }
}

/* ---------- Persistence helpers (channels/configs) ---------- */

private fun loadChannels(prefs: android.content.SharedPreferences): List<Channel> {
    val arrStr = prefs.getString("channels", "[]") ?: "[]"
    return try {
        val arr = JSONArray(arrStr)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val typeStr = o.optString("type", "WECHAT")
            val type = try { ChannelType.valueOf(typeStr) } catch (t: Throwable) { ChannelType.WECHAT }
            val simId = if (o.has("simId") && !o.isNull("simId")) o.optInt("simId", -1).let { if (it == -1) null else it } else null
            Channel(o.getString("id"), o.getString("name"), type, o.getString("target"), simId)
        }
    } catch (t: Throwable) {
        emptyList()
    }
}

private fun saveChannels(prefs: android.content.SharedPreferences, channels: List<Channel>) {
    val arr = JSONArray()
    channels.forEach {
        val o = JSONObject()
        o.put("id", it.id)
        o.put("name", it.name)
        o.put("type", it.type.name)
        o.put("target", it.target)
        if (it.simSubscriptionId != null) o.put("simId", it.simSubscriptionId) else o.put("simId", JSONObject.NULL)
        arr.put(o)
    }
    prefs.edit().putString("channels", arr.toString()).apply()
}

private fun loadConfigs(prefs: android.content.SharedPreferences): List<KeywordConfig> {
    val arrStr = prefs.getString("keyword_configs", "[]") ?: "[]"
    return try {
        val arr = JSONArray(arrStr)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            KeywordConfig(o.getString("id"), o.getString("keyword"), o.getString("channelId"))
        }
    } catch (t: Throwable) {
        emptyList()
    }
}

private fun saveConfigs(prefs: android.content.SharedPreferences, configs: List<KeywordConfig>) {
    val arr = JSONArray()
    configs.forEach {
        val o = JSONObject()
        o.put("id", it.id)
        o.put("keyword", it.keyword)
        o.put("channelId", it.channelId)
        arr.put(o)
    }
    prefs.edit().putString("keyword_configs", arr.toString()).apply()
}