package com.lanbing.smsforwarder

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * MainActivity: 现代简洁风格的短信转发助手主界面
 * - Material Design 3 设计
 * - 支持规则测试功能
 * - 添加电量优化白名单引导
 */

class MainActivity : ComponentActivity() {

    private lateinit var requestSmsPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var requestNotifPermissionLauncher: ActivityResultLauncher<String>

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

        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            val colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()

            MaterialTheme(
                colorScheme = colorScheme,
                typography = Typography()
            ) {
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
                    } catch (_: Throwable) {}
                }

                SmsForwarderApp(
                    onRequestSmsPermission = { requestSmsPermissionLauncher.launch(Manifest.permission.RECEIVE_SMS) },
                    onRequestNotificationPermission = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            requestNotifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsForwarderApp(
    onRequestSmsPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("app_config", Context.MODE_PRIVATE)
    val scope = rememberCoroutineScope()

    var isEnabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
    var startOnBoot by remember { mutableStateOf(prefs.getBoolean("start_on_boot", false)) }

    var channels by remember { mutableStateOf(loadChannels(prefs)) }
    var configs by remember { mutableStateOf(loadConfigs(prefs)) }

    // Channel form state
    var newChannelName by remember { mutableStateOf("") }
    var newChannelTarget by remember { mutableStateOf("") }
    var newChannelType by remember { mutableStateOf(ChannelType.WECHAT) }
    var channelTypeExpanded by remember { mutableStateOf(false) }

    // Config form state
    var newKeywordInput by remember { mutableStateOf("") }
    var selectedChannelIdForNewCfg by remember { mutableStateOf(channels.firstOrNull()?.id ?: "") }
    var configChannelDropdownExpanded by remember { mutableStateOf(false) }

    // Editing state
    var editingChannel by remember { mutableStateOf<Channel?>(null) }
    var showChannelDialog by remember { mutableStateOf(false) }
    var editChannelName by remember { mutableStateOf("") }
    var editChannelTarget by remember { mutableStateOf("") }
    var editChannelType by remember { mutableStateOf(ChannelType.WECHAT) }

    var editingConfig by remember { mutableStateOf<KeywordConfig?>(null) }
    var showConfigDialog by remember { mutableStateOf(false) }
    var editConfigKeyword by remember { mutableStateOf("") }
    var editConfigChannelId by remember { mutableStateOf("") }

    // UI state
    var logs by remember { mutableStateOf(LogStore.readAll(context)) }
    var currentTab by remember { mutableIntStateOf(0) }
    var showTestDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    // Permission states
    val smsGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
    val notifGranted = NotificationManagerCompat.from(context).areNotificationsEnabled()

    // Battery optimization state
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } else true

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "短信转发助手",
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    IconButton(onClick = { showTestDialog = true }) {
                        Icon(Icons.Outlined.Science, contentDescription = "测试规则")
                    }
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "关于")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    icon = { Icon(Icons.Filled.Tune, contentDescription = "配置") },
                    label = { Text("配置") }
                )
                NavigationBarItem(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    icon = { Icon(Icons.Filled.History, contentDescription = "日志") },
                    label = { Text("日志") }
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            when (currentTab) {
                0 -> ConfigTab(
                    isEnabled = isEnabled,
                    onEnabledChange = { checked ->
                        isEnabled = checked
                        prefs.edit().putBoolean("enabled", isEnabled).apply()
                        if (checked) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                val hasNotif = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                                if (!hasNotif) onRequestNotificationPermission()
                            }
                            val hasSms = ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
                            if (!hasSms) onRequestSmsPermission()
                            onStartService()
                            LogStore.append(context, "服务已启动（由用户开启）")
                        } else {
                            onStopService()
                            LogStore.append(context, "服务已停止（由用户关闭）")
                        }
                        context.sendBroadcast(Intent(SmsForegroundService.ACTION_UPDATE))
                    },
                    startOnBoot = startOnBoot,
                    onStartOnBootChange = {
                        startOnBoot = it
                        prefs.edit().putBoolean("start_on_boot", startOnBoot).apply()
                        if (startOnBoot) LogStore.append(context, "已开启开机启动") else LogStore.append(context, "已关闭开机启动")
                    },
                    smsGranted = smsGranted,
                    notifGranted = notifGranted,
                    isIgnoringBatteryOptimizations = isIgnoringBatteryOptimizations,
                    onRequestSmsPermission = onRequestSmsPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onRequestBatteryOptimization = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            }
                            context.startActivity(intent)
                        }
                    },
                    channels = channels,
                    configs = configs,
                    newChannelName = newChannelName,
                    onNewChannelNameChange = { newChannelName = it },
                    newChannelTarget = newChannelTarget,
                    onNewChannelTargetChange = { newChannelTarget = it },
                    newChannelType = newChannelType,
                    onNewChannelTypeChange = { newChannelType = it },
                    channelTypeExpanded = channelTypeExpanded,
                    onChannelTypeExpandedChange = { channelTypeExpanded = it },
                    onAddChannel = {
                        if (newChannelName.isBlank() || newChannelTarget.isBlank()) {
                            Toast.makeText(context, "请填写通道名称和 Webhook 地址", Toast.LENGTH_SHORT).show()
                            return@ConfigTab
                        }
                        val newChannel = Channel(UUID.randomUUID().toString(), newChannelName.trim(), newChannelType, newChannelTarget.trim())
                        channels = channels + newChannel
                        saveChannels(prefs, channels)
                        selectedChannelIdForNewCfg = channels.firstOrNull()?.id ?: ""
                        newChannelName = ""
                        newChannelTarget = ""
                        LogStore.append(context, "添加通道: ${newChannel.name} (${newChannel.type})")
                        Toast.makeText(context, "通道已添加", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteChannel = { ch ->
                        channels = channels.filterNot { it.id == ch.id }
                        saveChannels(prefs, channels)
                        configs = configs.filterNot { it.channelId == ch.id }
                        saveConfigs(prefs, configs)
                        LogStore.append(context, "删除通道: ${ch.name}")
                    },
                    onEditChannel = { ch ->
                        editingChannel = ch
                        editChannelName = ch.name
                        editChannelTarget = ch.target
                        editChannelType = ch.type
                        showChannelDialog = true
                    },
                    newKeywordInput = newKeywordInput,
                    onNewKeywordInputChange = { newKeywordInput = it },
                    selectedChannelIdForNewCfg = selectedChannelIdForNewCfg,
                    onSelectedChannelIdForNewCfgChange = { selectedChannelIdForNewCfg = it },
                    configChannelDropdownExpanded = configChannelDropdownExpanded,
                    onConfigChannelDropdownExpandedChange = { configChannelDropdownExpanded = it },
                    onAddConfig = {
                        if (channels.isEmpty()) {
                            Toast.makeText(context, "请先添加通道", Toast.LENGTH_SHORT).show()
                            return@ConfigTab
                        }
                        if (selectedChannelIdForNewCfg.isBlank()) {
                            Toast.makeText(context, "请选择通道", Toast.LENGTH_SHORT).show()
                            return@ConfigTab
                        }
                        val newCfg = KeywordConfig(UUID.randomUUID().toString(), newKeywordInput.trim(), selectedChannelIdForNewCfg)
                        configs = configs + newCfg
                        saveConfigs(prefs, configs)
                        newKeywordInput = ""
                        LogStore.append(context, "添加关键词: ${newCfg.keyword} -> ${channels.find { it.id == newCfg.channelId }?.name}")
                        Toast.makeText(context, "配置已添加", Toast.LENGTH_SHORT).show()
                    },
                    onDeleteConfig = { cfg ->
                        configs = configs.filterNot { it.id == cfg.id }
                        saveConfigs(prefs, configs)
                    },
                    onEditConfig = { cfg ->
                        editingConfig = cfg
                        editConfigKeyword = cfg.keyword
                        editConfigChannelId = cfg.channelId
                        showConfigDialog = true
                    }
                )
                1 -> LogTab(
                    logs = logs,
                    onRefresh = { logs = LogStore.readAll(context) },
                    onClear = {
                        LogStore.clear(context)
                        logs = emptyList()
                        Toast.makeText(context, "日志已清除", Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
    }

    // Edit Channel Dialog
    if (showChannelDialog && editingChannel != null) {
        AlertDialog(
            onDismissRequest = { showChannelDialog = false; editingChannel = null },
            title = { Text("编辑通道") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editChannelName,
                        onValueChange = { editChannelName = it },
                        label = { Text("通道名称") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    var editTypeExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = editTypeExpanded,
                        onExpandedChange = { editTypeExpanded = !editTypeExpanded }
                    ) {
                        OutlinedTextField(
                            value = getChannelTypeLabel(editChannelType),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("通道类型") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(editTypeExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = editTypeExpanded,
                            onDismissRequest = { editTypeExpanded = false }
                        ) {
                            ChannelType.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(getChannelTypeLabel(t)) },
                                    onClick = {
                                        editChannelType = t
                                        editTypeExpanded = false
                                    }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editChannelTarget,
                        onValueChange = { editChannelTarget = it },
                        label = { Text("Webhook 地址") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ch = editingChannel ?: return@TextButton
                    val updated = Channel(ch.id, editChannelName.trim(), editChannelType, editChannelTarget.trim())
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

    // Edit Config Dialog
    if (showConfigDialog && editingConfig != null) {
        AlertDialog(
            onDismissRequest = { showConfigDialog = false; editingConfig = null },
            title = { Text("编辑关键词配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = editConfigKeyword,
                        onValueChange = { editConfigKeyword = it },
                        label = { Text("关键词（留空表示全部）") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    var editCfgExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(
                        expanded = editCfgExpanded,
                        onExpandedChange = { editCfgExpanded = !editCfgExpanded }
                    ) {
                        OutlinedTextField(
                            value = channels.find { it.id == editConfigChannelId }?.name ?: "选择通道",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("转发通道") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(editCfgExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = editCfgExpanded,
                            onDismissRequest = { editCfgExpanded = false }
                        ) {
                            channels.forEach { ch ->
                                DropdownMenuItem(
                                    text = { Text(ch.name) },
                                    onClick = {
                                        editConfigChannelId = ch.id
                                        editCfgExpanded = false
                                    }
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cfg = editingConfig ?: return@TextButton
                    if (editConfigChannelId.isBlank()) {
                        Toast.makeText(context, "请选择通道", Toast.LENGTH_SHORT).show()
                        return@TextButton
                    }
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

    // Test Dialog
    if (showTestDialog) {
        TestRuleDialog(
            channels = channels,
            configs = configs,
            onDismiss = { showTestDialog = false }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }
}

@Composable
fun ConfigTab(
    isEnabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    startOnBoot: Boolean,
    onStartOnBootChange: (Boolean) -> Unit,
    smsGranted: Boolean,
    notifGranted: Boolean,
    isIgnoringBatteryOptimizations: Boolean,
    onRequestSmsPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onRequestBatteryOptimization: () -> Unit,
    channels: List<Channel>,
    configs: List<KeywordConfig>,
    newChannelName: String,
    onNewChannelNameChange: (String) -> Unit,
    newChannelTarget: String,
    onNewChannelTargetChange: (String) -> Unit,
    newChannelType: ChannelType,
    onNewChannelTypeChange: (ChannelType) -> Unit,
    channelTypeExpanded: Boolean,
    onChannelTypeExpandedChange: (Boolean) -> Unit,
    onAddChannel: () -> Unit,
    onDeleteChannel: (Channel) -> Unit,
    onEditChannel: (Channel) -> Unit,
    newKeywordInput: String,
    onNewKeywordInputChange: (String) -> Unit,
    selectedChannelIdForNewCfg: String,
    onSelectedChannelIdForNewCfgChange: (String) -> Unit,
    configChannelDropdownExpanded: Boolean,
    onConfigChannelDropdownExpandedChange: (Boolean) -> Unit,
    onAddConfig: () -> Unit,
    onDeleteConfig: (KeywordConfig) -> Unit,
    onEditConfig: (KeywordConfig) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Service Status Card
        item {
            ModernCard {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "转发服务",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val statusColor = if (isEnabled) Color(0xFF10B981) else Color(0xFF9CA3AF)
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .background(statusColor, shape = RoundedCornerShape(50))
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isEnabled) "服务运行中" else "服务已停止",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = onEnabledChange
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    // Permission items
                    PermissionItem(
                        icon = Icons.Outlined.Notifications,
                        title = "通知权限",
                        granted = notifGranted,
                        onClick = onRequestNotificationPermission
                    )
                    PermissionItem(
                        icon = Icons.Outlined.Sms,
                        title = "短信权限",
                        granted = smsGranted,
                        onClick = onRequestSmsPermission
                    )
                    PermissionItem(
                        icon = Icons.Outlined.BatteryFull,
                        title = "电池优化白名单",
                        granted = isIgnoringBatteryOptimizations,
                        onClick = onRequestBatteryOptimization
                    )

                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("开机自启动")
                        Spacer(Modifier.weight(1f))
                        Switch(checked = startOnBoot, onCheckedChange = onStartOnBootChange)
                    }
                }
            }
        }

        // Keyword Config Card
        item {
            ModernCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "关键词配置",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = newKeywordInput,
                        onValueChange = onNewKeywordInputChange,
                        label = { Text("输入关键词（留空表示全部）") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    ExposedDropdownMenuBox(
                        expanded = configChannelDropdownExpanded,
                        onExpandedChange = onConfigChannelDropdownExpandedChange
                    ) {
                        OutlinedTextField(
                            value = channels.find { it.id == selectedChannelIdForNewCfg }?.name ?: "选择转发通道",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("转发通道") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(configChannelDropdownExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = configChannelDropdownExpanded,
                            onDismissRequest = { onConfigChannelDropdownExpandedChange(false) }
                        ) {
                            channels.forEach { ch ->
                                DropdownMenuItem(
                                    text = { Text(ch.name) },
                                    onClick = {
                                        onSelectedChannelIdForNewCfgChange(ch.id)
                                        onConfigChannelDropdownExpandedChange(false)
                                    }
                                )
                            }
                            if (channels.isEmpty()) {
                                DropdownMenuItem(
                                    text = { Text("请先添加通道") },
                                    onClick = { onConfigChannelDropdownExpandedChange(false) }
                                )
                            }
                        }
                    }

                    Button(
                        onClick = onAddConfig,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("添加配置") }

                    // Config list
                    if (configs.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        configs.forEach { cfg ->
                            val chName = channels.find { it.id == cfg.channelId }?.name ?: "(已删除通道)"
                            ConfigItem(
                                keyword = cfg.keyword,
                                channelName = chName,
                                onEdit = { onEditConfig(cfg) },
                                onDelete = { onDeleteConfig(cfg) }
                            )
                        }
                    }
                }
            }
        }

        // Channel Management Card
        item {
            ModernCard {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "转发通道管理",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )

                    OutlinedTextField(
                        value = newChannelName,
                        onValueChange = onNewChannelNameChange,
                        label = { Text("通道名称") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    ExposedDropdownMenuBox(
                        expanded = channelTypeExpanded,
                        onExpandedChange = onChannelTypeExpandedChange
                    ) {
                        OutlinedTextField(
                            value = getChannelTypeLabel(newChannelType),
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("通道类型") },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(channelTypeExpanded) }
                        )
                        ExposedDropdownMenu(
                            expanded = channelTypeExpanded,
                            onDismissRequest = { onChannelTypeExpandedChange(false) }
                        ) {
                            ChannelType.entries.forEach { t ->
                                DropdownMenuItem(
                                    text = { Text(getChannelTypeLabel(t)) },
                                    onClick = {
                                        onNewChannelTypeChange(t)
                                        onChannelTypeExpandedChange(false)
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = newChannelTarget,
                        onValueChange = onNewChannelTargetChange,
                        label = { Text("Webhook 地址") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Button(
                        onClick = onAddChannel,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("添加通道") }

                    // Channel list
                    if (channels.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        channels.forEach { ch ->
                            ChannelItem(
                                name = ch.name,
                                type = getChannelTypeLabel(ch.type),
                                target = ch.target,
                                onEdit = { onEditChannel(ch) },
                                onDelete = { onDeleteChannel(ch) }
                            )
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
fun LogTab(
    logs: List<String>,
    onRefresh: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ModernCard(
            modifier = Modifier.weight(1f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "转发日志",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.weight(1f))
                    Row {
                        IconButton(onClick = onRefresh) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        IconButton(onClick = onClear) {
                            Icon(Icons.Filled.ClearAll, contentDescription = "清除")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(logs) { line ->
                            LogItem(line = line)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ModernCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .shadow(2.dp, RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        content()
    }
}

@Composable
fun PermissionItem(
    icon: ImageVector,
    title: String,
    granted: Boolean,
    onClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text(title, modifier = Modifier.weight(1f))
        if (granted) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "已授权",
                tint = Color(0xFF10B981)
            )
        } else {
            TextButton(onClick = onClick) { Text("去开启") }
        }
    }
}

@Composable
fun ConfigItem(
    keyword: String,
    channelName: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                if (keyword.isBlank()) "全部消息" else keyword,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                "→ $channelName",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFFEE4444))
        }
    }
}

@Composable
fun ChannelItem(
    name: String,
    type: String,
    target: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                "$type → ${target.take(30)}${if (target.length > 30) "..." else ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "编辑")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFFEE4444))
        }
    }
}

@Composable
fun LogItem(line: String) {
    val tsRegex = """^\[(.*?)\]\s*(.*)$""".toRegex()
    val match = tsRegex.find(line)
    val time = match?.groups?.get(1)?.value ?: ""
    val msg = match?.groups?.get(2)?.value ?: line
    val isSuccess = msg.contains("成功") || msg.contains("已启动")
    val isError = msg.contains("失败") || msg.contains("异常") || msg.contains("错误")
    val iconTint = when {
        isSuccess -> Color(0xFF10B981)
        isError -> Color(0xFFEE4444)
        else -> MaterialTheme.colorScheme.primary
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(iconTint, shape = RoundedCornerShape(50))
                .padding(top = 6.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium
            )
            if (time.isNotEmpty()) {
                Text(
                    time,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun TestRuleDialog(
    channels: List<Channel>,
    configs: List<KeywordConfig>,
    onDismiss: () -> Unit
) {
    var testContent by remember { mutableStateOf("") }
    var testResults by remember { mutableStateOf<List<Pair<Channel, KeywordConfig>>>(emptyList()) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "测试转发规则",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = testContent,
                    onValueChange = {
                        testContent = it
                        // 实时测试
                        testResults = if (it.isNotBlank()) {
                            configs.mapNotNull { cfg ->
                                val kw = cfg.keyword.trim()
                                val match = if (kw.isEmpty()) true else it.contains(kw, ignoreCase = true)
                                if (match) {
                                    channels.find { ch -> ch.id == cfg.channelId }?.let { ch ->
                                        ch to cfg
                                    }
                                } else null
                            }
                        } else emptyList()
                    },
                    label = { Text("输入测试短信内容") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3
                )

                Spacer(Modifier.height(16.dp))

                Text(
                    "匹配结果",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))

                if (testResults.isEmpty()) {
                    Text(
                        if (testContent.isBlank()) "请输入内容开始测试" else "没有匹配的规则",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        testResults.forEach { (ch, cfg) ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.CheckCircle,
                                        contentDescription = null,
                                        tint = Color(0xFF10B981)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            "关键词: ${if (cfg.keyword.isBlank()) "全部" else cfg.keyword}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "→ ${ch.name} (${getChannelTypeLabel(ch.type)})",
                                            style = MaterialTheme.typography.bodySmall
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("关闭") }
            }
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    Icons.Filled.Message,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "短信转发助手",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "版本 2.0",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    "自动将收到的短信根据关键词规则转发到企业微信、钉钉、飞书或自定义 Webhook。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(Modifier.height(24.dp))
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

fun getChannelTypeLabel(type: ChannelType): String = when (type) {
    ChannelType.WECHAT -> "企业微信"
    ChannelType.DINGTALK -> "钉钉"
    ChannelType.FEISHU -> "飞书"
    ChannelType.GENERIC_WEBHOOK -> "Webhook"
}

private fun loadChannels(prefs: android.content.SharedPreferences): List<Channel> {
    val arrStr = prefs.getString("channels", "[]") ?: "[]"
    return try {
        val arr = JSONArray(arrStr)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val typeStr = o.optString("type", "WECHAT")
            val type = try { ChannelType.valueOf(typeStr) } catch (t: Throwable) { ChannelType.WECHAT }
            Channel(o.getString("id"), o.getString("name"), type, o.getString("target"))
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
