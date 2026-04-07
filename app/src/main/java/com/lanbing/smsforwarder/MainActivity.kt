/*
 * 短信转发助手
 *
 * 著作权人：华昊科技有限公司
 * 开发者：王士辉
 *
 * Copyright (c) 2026 华昊科技有限公司. All rights reserved.
 * 联系邮箱：huahao@email.cn
 */

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
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat

import org.json.JSONArray
import org.json.JSONObject
import java.util.*

/**
 * MainActivity: 现代简洁风格的短信转发助手主界面
 * - Material Design 3 设计
 * - 支持规则测试功能
 * - 添加电量优化白名单引导
 * - 更现代的UI设计
 */

class MainActivity : ComponentActivity() {

    private lateinit var requestSmsPermissionLauncher: ActivityResultLauncher<String>
    private lateinit var requestNotifPermissionLauncher: ActivityResultLauncher<String>
    private var onPermissionChanged: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

        requestSmsPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Toast.makeText(this, "短信权限已授权", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "请授予短信权限以接收短信", Toast.LENGTH_LONG).show()
            onPermissionChanged?.invoke()
        }

        requestNotifPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) Toast.makeText(this, "通知权限已授权", Toast.LENGTH_SHORT).show()
            else Toast.makeText(this, "请允许通知权限以显示常驻通知", Toast.LENGTH_LONG).show()
            onPermissionChanged?.invoke()
        }

        setContent {
            val isDarkTheme = isSystemInDarkTheme()
            val colorScheme = if (isDarkTheme) darkColorScheme() else lightColorScheme()
            var permissionUpdateTrigger by remember { mutableStateOf(0) }

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

                SideEffect {
                    onPermissionChanged = { permissionUpdateTrigger++ }
                }

                SmsForwarderApp(
                    permissionUpdateTrigger = permissionUpdateTrigger,
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

    override fun onResume() {
        super.onResume()
        // 从设置页面回来时，刷新权限状态
        onPermissionChanged?.invoke()
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

fun isValidWebhookUrl(url: String): Boolean {
    return try {
        val u = java.net.URL(url)
        (u.protocol == "http" || u.protocol == "https") && u.host.isNotBlank()
    } catch (e: java.net.MalformedURLException) {
        false
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmsForwarderApp(
    permissionUpdateTrigger: Int,
    onRequestSmsPermission: () -> Unit,
    onRequestNotificationPermission: () -> Unit,
    onStartService: () -> Unit,
    onStopService: () -> Unit
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)

    var isEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_ENABLED, false)) }
    var startOnBoot by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_START_ON_BOOT, false)) }
    var showReceiverPhone by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_SHOW_RECEIVER_PHONE, true)) }
    var showSenderPhone by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_SHOW_SENDER_PHONE, true)) }
    var highlightVerificationCode by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_HIGHLIGHT_VERIFICATION_CODE, true)) }
    var batteryReminderEnabled by remember { mutableStateOf(prefs.getBoolean(Constants.PREF_BATTERY_REMINDER_ENABLED, false)) }
    var lowBatteryThreshold by remember { mutableStateOf(prefs.getInt(Constants.PREF_LOW_BATTERY_THRESHOLD, Constants.DEFAULT_LOW_BATTERY_THRESHOLD)) }
    var highBatteryThreshold by remember { mutableStateOf(prefs.getInt(Constants.PREF_HIGH_BATTERY_THRESHOLD, Constants.DEFAULT_HIGH_BATTERY_THRESHOLD)) }

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
    var currentTab by remember { mutableStateOf<Int>(0) }
    var showTestDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showBootTipDialog by remember { mutableStateOf(false) }
    
    // 定义5个标签页
    val tabs = listOf(
        "首页" to Icons.Default.Home,
        "关键词" to Icons.Default.Label,
        "通道" to Icons.Default.Cloud,
        "设置" to Icons.Default.Settings,
        "日志" to Icons.Default.History
    )

    // Permission states (use key to trigger recomposition)
    val smsGranted by remember(permissionUpdateTrigger) {
        derivedStateOf {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        }
    }
    val notifGranted by remember(permissionUpdateTrigger) {
        derivedStateOf {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
    }

    // Battery optimization state
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val isIgnoringBatteryOptimizations by remember(permissionUpdateTrigger) {
        derivedStateOf {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isIgnoringBatteryOptimizations(context.packageName)
            } else true
        }
    }

    // Page indicator colors
    val pageColors = listOf(
        Color(0xFF667EEA), // Purple
        Color(0xFF10B981)  // Green
    )

    Scaffold(
        modifier = Modifier.background(
            brush = Brush.verticalGradient(
                colors = listOf(
                    MaterialTheme.colorScheme.surface,
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )
        ),
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(
                                    brush = Brush.linearGradient(
                                        colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2))
                                    )
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Message,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "短信转发助手",
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                if (isEnabled) "服务运行中" else "服务已停止",
                                fontSize = 12.sp,
                                color = if (isEnabled) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showAboutDialog = true }) {
                        Icon(Icons.Outlined.Info, contentDescription = "关于")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.8f)
                )
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 8.dp
            ) {
                tabs.forEachIndexed { index, (label, icon) ->
                    NavigationBarItem(
                        selected = currentTab == index,
                        onClick = { currentTab = index },
                        icon = {
                            val filledIcon = when (index) {
                                0 -> Icons.Filled.Home
                                1 -> Icons.Filled.Label
                                2 -> Icons.Filled.Cloud
                                3 -> Icons.Filled.Settings
                                4 -> Icons.Filled.History
                                else -> Icons.Filled.Home
                            }
                            val outlinedIcon = when (index) {
                                0 -> Icons.Outlined.Home
                                1 -> Icons.Outlined.Label
                                2 -> Icons.Outlined.Cloud
                                3 -> Icons.Outlined.Settings
                                4 -> Icons.Outlined.History
                                else -> Icons.Outlined.Home
                            }
                            Icon(
                                if (currentTab == index) filledIcon else outlinedIcon,
                                contentDescription = label
                            )
                        },
                        label = { Text(label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
        ) {
            AnimatedContent(
                targetState = currentTab,
                label = "tabAnimation"
            ) { tabIndex ->
                when (tabIndex) {
                    0 -> HomeTab(
                        isEnabled = isEnabled,
                        onEnabledChange = { checked ->
                            isEnabled = checked
                            prefs.edit().putBoolean(Constants.PREF_ENABLED, isEnabled).apply()
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
                            prefs.edit().putBoolean(Constants.PREF_START_ON_BOOT, startOnBoot).apply()
                            if (startOnBoot) {
                                LogStore.append(context, "已开启开机启动")
                                showBootTipDialog = true
                            } else {
                                LogStore.append(context, "已关闭开机启动")
                            }
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
                        permissionUpdateTrigger = permissionUpdateTrigger
                    )
                    1 -> KeywordTab(
                        channels = channels,
                        configs = configs,
                        newKeywordInput = newKeywordInput,
                        onNewKeywordInputChange = { newKeywordInput = it },
                        selectedChannelIdForNewCfg = selectedChannelIdForNewCfg,
                        onSelectedChannelIdForNewCfgChange = { selectedChannelIdForNewCfg = it },
                        configChannelDropdownExpanded = configChannelDropdownExpanded,
                        onConfigChannelDropdownExpandedChange = { configChannelDropdownExpanded = it },
                        onAddConfig = {
                            if (channels.isEmpty()) {
                                Toast.makeText(context, "请先添加通道", Toast.LENGTH_SHORT).show()
                                return@KeywordTab
                            }
                            if (selectedChannelIdForNewCfg.isBlank()) {
                                Toast.makeText(context, "请选择通道", Toast.LENGTH_SHORT).show()
                                return@KeywordTab
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
                    2 -> ChannelTab(
                        channels = channels,
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
                                return@ChannelTab
                            }
                            if (!isValidWebhookUrl(newChannelTarget)) {
                                Toast.makeText(context, "Webhook 地址格式无效，请输入有效的 http:// 或 https:// 地址", Toast.LENGTH_SHORT).show()
                                return@ChannelTab
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
                        }
                    )
                    3 -> SettingsTab(
                        showReceiverPhone = showReceiverPhone,
                        onShowReceiverPhoneChange = {
                            showReceiverPhone = it
                            prefs.edit().putBoolean(Constants.PREF_SHOW_RECEIVER_PHONE, showReceiverPhone).apply()
                            if (showReceiverPhone) LogStore.append(context, "已开启显示本机号码") else LogStore.append(context, "已关闭显示本机号码")
                        },
                        showSenderPhone = showSenderPhone,
                        onShowSenderPhoneChange = {
                            showSenderPhone = it
                            prefs.edit().putBoolean(Constants.PREF_SHOW_SENDER_PHONE, showSenderPhone).apply()
                            if (showSenderPhone) LogStore.append(context, "已开启显示发送者号码") else LogStore.append(context, "已关闭显示发送者号码")
                        },
                        highlightVerificationCode = highlightVerificationCode,
                        onHighlightVerificationCodeChange = {
                            highlightVerificationCode = it
                            prefs.edit().putBoolean(Constants.PREF_HIGHLIGHT_VERIFICATION_CODE, highlightVerificationCode).apply()
                            if (highlightVerificationCode) LogStore.append(context, "已开启突出显示验证码") else LogStore.append(context, "已关闭突出显示验证码")
                        },
                        batteryReminderEnabled = batteryReminderEnabled,
                        onBatteryReminderEnabledChange = {
                            batteryReminderEnabled = it
                            prefs.edit().putBoolean(Constants.PREF_BATTERY_REMINDER_ENABLED, batteryReminderEnabled).apply()
                            if (batteryReminderEnabled) LogStore.append(context, "已开启电量提醒") else LogStore.append(context, "已关闭电量提醒")
                        },
                        lowBatteryThreshold = lowBatteryThreshold,
                        onLowBatteryThresholdChange = {
                            lowBatteryThreshold = it
                            prefs.edit().putInt(Constants.PREF_LOW_BATTERY_THRESHOLD, lowBatteryThreshold).apply()
                        },
                        highBatteryThreshold = highBatteryThreshold,
                        onHighBatteryThresholdChange = {
                            highBatteryThreshold = it
                            prefs.edit().putInt(Constants.PREF_HIGH_BATTERY_THRESHOLD, highBatteryThreshold).apply()
                        },
                        onShowTestDialog = { showTestDialog = true },
                        permissionUpdateTrigger = permissionUpdateTrigger
                    )
                    4 -> LogTab(
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
    }

    // Edit Channel Dialog
    if (showChannelDialog && editingChannel != null) {
        ModernAlertDialog(
            onDismissRequest = { showChannelDialog = false; editingChannel = null },
            title = "编辑通道",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editChannelName,
                        onValueChange = { editChannelName = it },
                        label = { Text("通道名称") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
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
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(editTypeExpanded) },
                            shape = RoundedCornerShape(12.dp)
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
                    OutlinedTextField(
                        value = editChannelTarget,
                        onValueChange = { editChannelTarget = it },
                        label = { Text("Webhook 地址") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val ch = editingChannel ?: return@Button
                        val updated = Channel(ch.id, editChannelName.trim(), editChannelType, editChannelTarget.trim())
                        channels = channels.map { if (it.id == ch.id) updated else it }
                        saveChannels(prefs, channels)
                        LogStore.append(context, "编辑通道: ${updated.name}")
                        showChannelDialog = false
                        editingChannel = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showChannelDialog = false; editingChannel = null }) { Text("取消") }
            }
        )
    }

    // Edit Config Dialog
    if (showConfigDialog && editingConfig != null) {
        ModernAlertDialog(
            onDismissRequest = { showConfigDialog = false; editingConfig = null },
            title = "编辑关键词配置",
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = editConfigKeyword,
                        onValueChange = { editConfigKeyword = it },
                        label = { Text("关键词（留空表示全部）") },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
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
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(editCfgExpanded) },
                            shape = RoundedCornerShape(12.dp)
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
                Button(
                    onClick = {
                        val cfg = editingConfig ?: return@Button
                        if (editConfigChannelId.isBlank()) {
                            Toast.makeText(context, "请选择通道", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        val updated = KeywordConfig(cfg.id, editConfigKeyword.trim(), editConfigChannelId)
                        configs = configs.map { if (it.id == cfg.id) updated else it }
                        saveConfigs(prefs, configs)
                        LogStore.append(context, "编辑关键词: ${updated.keyword} -> ${channels.find { it.id == updated.channelId }?.name}")
                        showConfigDialog = false
                        editingConfig = null
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("保存") }
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
    
    // 开机自启动提示对话框
    if (showBootTipDialog) {
        val ctx = LocalContext.current
        ModernAlertDialog(
            onDismissRequest = { showBootTipDialog = false },
            title = "重要提示",
            content = {
                Text(
                    text = "要让开机自启动正常工作，您还需要在系统设置中开启应用的自启动权限。\n\n通常在：设置 → 应用管理 → 短信转发助手 → 权限管理/自启动管理",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            confirmButton = {
                TextButton(onClick = { showBootTipDialog = false }) {
                    Text("我知道了")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showBootTipDialog = false
                    try {
                        val intent = Intent().apply {
                            action = "android.settings.APPLICATION_DETAILS_SETTINGS"
                            data = Uri.fromParts("package", ctx.packageName, null)
                        }
                        ctx.startActivity(intent)
                    } catch (_: Exception) {
                        Toast.makeText(ctx, "请手动打开系统设置", Toast.LENGTH_SHORT).show()
                    }
                }) {
                    Text("去设置")
                }
            }
        )
    }
}

@Composable
fun ModernAlertDialog(
    onDismissRequest: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable () -> Unit
) {
    Dialog(onDismissRequest = onDismissRequest) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(20.dp))
                content()
                Spacer(modifier = Modifier.height(24.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    dismissButton()
                    Spacer(modifier = Modifier.width(8.dp))
                    confirmButton()
                }
            }
        }
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
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFFF59E0B).copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.History,
                            contentDescription = null,
                            tint = Color(0xFFF59E0B)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "转发日志",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${logs.size} 条记录",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Row {
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(
                            onClick = onClear,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFFEE4444).copy(alpha = 0.1f))
                        ) {
                            Icon(Icons.Filled.ClearAll, contentDescription = "清除", tint = Color(0xFFEE4444))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                if (logs.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Outlined.Description,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                "暂无日志",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
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
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column {
            content()
        }
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
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (granted) Color(0xFF10B981).copy(alpha = 0.1f) else Color(0xFFEE4444).copy(alpha = 0.1f))
            .padding(12.dp)
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (granted) Color(0xFF10B981) else Color(0xFFEE4444)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                if (granted) "已授权" else "未授权",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (granted) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "已授权",
                tint = Color(0xFF10B981)
            )
        } else {
            Button(
                onClick = onClick,
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text("去开启")
            }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Rule,
                    contentDescription = null,
                    tint = Color(0xFF10B981),
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
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
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFFEE4444))
            }
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
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val channelColor = when (type) {
                "企业微信" -> Color(0xFF07C160)
                "钉钉" -> Color(0xFF2080F0)
                "飞书" -> Color(0xFF2064E5)
                else -> Color(0xFF667EEA)
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(channelColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Cloud,
                    contentDescription = null,
                    tint = channelColor,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
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
            IconButton(
                onClick = onEdit,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑")
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = Color(0xFFEE4444))
            }
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
    val bgColor = when {
        isSuccess -> Color(0xFF10B981).copy(alpha = 0.05f)
        isError -> Color(0xFFEE4444).copy(alpha = 0.05f)
        else -> Color.Transparent
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(iconTint, shape = CircleShape)
                .padding(top = 6.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                msg,
                style = MaterialTheme.typography.bodyMedium
            )
            if (time.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
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
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                brush = Brush.linearGradient(
                                    colors = listOf(Color(0xFFF59E0B), Color(0xFFD97706))
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.Science,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            "测试转发规则",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "输入短信内容测试匹配",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = testContent,
                    onValueChange = {
                        testContent = it
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
                    minLines = 4,
                    shape = RoundedCornerShape(16.dp)
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    "匹配结果",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(12.dp))

                if (testResults.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    Icons.Outlined.Rule,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    if (testContent.isBlank()) "请输入内容开始测试" else "没有匹配的规则",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        testResults.forEach { (ch, cfg) ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = Color(0xFF10B981).copy(alpha = 0.1f)
                                ),
                                shape = RoundedCornerShape(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.padding(16.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(40.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF10B981).copy(alpha = 0.2f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.CheckCircle,
                                            contentDescription = null,
                                            tint = Color(0xFF10B981),
                                            modifier = Modifier.size(24.dp)
                                        )
                                    }
                                    Spacer(Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "关键词: ${if (cfg.keyword.isBlank()) "全部" else cfg.keyword}",
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            "→ ${ch.name} (${getChannelTypeLabel(ch.type)})",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) { Text("关闭", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val packageManager = context.packageManager
    val packageName = context.packageName
    val versionName = try {
        packageManager.getPackageInfo(packageName, 0).versionName
    } catch (e: Exception) {
        "未知版本"
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.cardElevation(8.dp)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            brush = Brush.linearGradient(
                                colors = listOf(Color(0xFF667EEA), Color(0xFF764BA2))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.Message,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "短信转发助手",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    "版本 $versionName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(20.dp))
                Text(
                    "轻量、稳定、开源的 Android 短信转发应用。支持企业微信、钉钉、飞书和自定义 Webhook 等多种转发渠道，支持关键词过滤、验证码提取、本机号码识别等功能。",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "官方网站：https://smsforwarder.cn/",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.clickable {
                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://smsforwarder.cn/"))
                        context.startActivity(intent)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "备案号：鲁ICP备2026012160号-1",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "© 2026 华昊科技有限公司",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(vertical = 14.dp)
                ) { Text("关闭", fontSize = 16.sp) }
            }
        }
    }
}

@Composable
fun SimCardInfoCard(permissionUpdateTrigger: Int) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE) }
    
    var customSim1Phone by remember { mutableStateOf(prefs.getString(Constants.PREF_CUSTOM_SIM1_PHONE, null)) }
    var customSim2Phone by remember { mutableStateOf(prefs.getString(Constants.PREF_CUSTOM_SIM2_PHONE, null)) }
    
    val hasPhonePermission by remember(permissionUpdateTrigger) {
        derivedStateOf {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        }
    }
    
    val simCards = remember(customSim1Phone, customSim2Phone, permissionUpdateTrigger) { 
        getSimCardInfo(context, customSim1Phone, customSim2Phone) 
    }
    
    var showEditDialog by remember { mutableStateOf<Int?>(null) }
    var editPhoneNumber by remember { mutableStateOf("") }

    ModernCard {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF3B82F6).copy(alpha = 0.1f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Phone,
                        contentDescription = null,
                        tint = Color(0xFF3B82F6)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "SIM 卡信息",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "点击编辑按钮手动输入本机号码",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // 电话权限提示
            if (!hasPhonePermission) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            try {
                                val intent = Intent().apply {
                                    action = "android.settings.APPLICATION_DETAILS_SETTINGS"
                                    data = Uri.fromParts("package", context.packageName, null)
                                }
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "请手动打开系统设置", Toast.LENGTH_SHORT).show()
                            }
                        },
                    color = Color(0xFFFEF2F2).copy(alpha = 0.9f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = Color(0xFFDC2626)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "需要电话权限才能自动获取本机号码",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF991B1B)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "点击前往设置开启权限，或手动输入号码",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB91C1C)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Icon(
                            Icons.Filled.ChevronRight,
                            contentDescription = null,
                            tint = Color(0xFFDC2626)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
            }
            
            // 提示信息
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = Color(0xFFFFF3CD).copy(alpha = 0.8f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "小米/澎湃OS等定制ROM通常无法自动获取本机号码，请手动输入",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF92400E)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (simCards.isEmpty()) {
                Text(
                    "无法读取 SIM 卡信息",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                simCards.forEachIndexed { index, simInfo ->
                    SimCardItem(
                        slot = index + 1,
                        phoneNumber = simInfo.phoneNumber,
                        carrierName = simInfo.carrierName,
                        isCustom = simInfo.isCustom,
                        onEdit = {
                            showEditDialog = index + 1
                            editPhoneNumber = simInfo.phoneNumber ?: ""
                        },
                        onClear = {
                            if (index + 1 == 1) {
                                customSim1Phone = null
                                prefs.edit().remove(Constants.PREF_CUSTOM_SIM1_PHONE).apply()
                            } else {
                                customSim2Phone = null
                                prefs.edit().remove(Constants.PREF_CUSTOM_SIM2_PHONE).apply()
                            }
                        }
                    )
                    if (index < simCards.size - 1) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }

    // 编辑 SIM 号码对话框
    showEditDialog?.let { slot ->
        EditSimPhoneDialog(
            slot = slot,
            currentPhoneNumber = editPhoneNumber,
            onDismiss = { showEditDialog = null },
            onSave = { newPhoneNumber ->
                if (slot == 1) {
                    customSim1Phone = newPhoneNumber.takeIf { it.isNotBlank() }
                    if (newPhoneNumber.isNotBlank()) {
                        prefs.edit().putString(Constants.PREF_CUSTOM_SIM1_PHONE, newPhoneNumber).apply()
                    } else {
                        prefs.edit().remove(Constants.PREF_CUSTOM_SIM1_PHONE).apply()
                    }
                } else {
                    customSim2Phone = newPhoneNumber.takeIf { it.isNotBlank() }
                    if (newPhoneNumber.isNotBlank()) {
                        prefs.edit().putString(Constants.PREF_CUSTOM_SIM2_PHONE, newPhoneNumber).apply()
                    } else {
                        prefs.edit().remove(Constants.PREF_CUSTOM_SIM2_PHONE).apply()
                    }
                }
                showEditDialog = null
            }
        )
    }
}

private data class SimCardInfo(
    val phoneNumber: String?,
    val carrierName: String?,
    val isCustom: Boolean = false
)

private fun getSimCardInfo(context: Context, customSim1Phone: String? = null, customSim2Phone: String? = null): List<SimCardInfo> {
    val simCards = mutableListOf<SimCardInfo>()
    try {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            // 即使没有权限，也显示自定义号码
            if (customSim1Phone != null) {
                simCards.add(SimCardInfo(phoneNumber = customSim1Phone, carrierName = null, isCustom = true))
            }
            if (customSim2Phone != null) {
                simCards.add(SimCardInfo(phoneNumber = customSim2Phone, carrierName = null, isCustom = true))
            }
            return simCards
        }

        val subscriptionManager = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                SubscriptionManager.from(context)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e("SimCardInfo", "获取 SubscriptionManager 失败", e)
            null
        }

        var addedSim1 = false
        var addedSim2 = false
        
        if (subscriptionManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            try {
                val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                activeSubscriptions?.forEachIndexed { index, subInfo ->
                    try {
                        val slotIndex = index + 1
                        val customPhone = if (slotIndex == 1) customSim1Phone else if (slotIndex == 2) customSim2Phone else null
                        val phoneNumber = customPhone ?: subInfo?.number?.takeIf { it.isNotBlank() }
                        simCards.add(
                            SimCardInfo(
                                phoneNumber = phoneNumber,
                                carrierName = subInfo?.carrierName?.toString(),
                                isCustom = customPhone != null
                            )
                        )
                        if (slotIndex == 1) addedSim1 = true
                        if (slotIndex == 2) addedSim2 = true
                    } catch (e: Exception) {
                        Log.e("SimCardInfo", "处理 subscriptionInfo 失败", e)
                    }
                }
            } catch (e: Exception) {
                Log.e("SimCardInfo", "获取 activeSubscriptions 失败", e)
            }
        }

        // 如果 SIM1 没有添加但有自定义号码，添加
        if (!addedSim1 && customSim1Phone != null) {
            simCards.add(SimCardInfo(phoneNumber = customSim1Phone, carrierName = null, isCustom = true))
            addedSim1 = true
        }
        // 如果 SIM2 没有添加但有自定义号码，添加
        if (!addedSim2 && customSim2Phone != null) {
            simCards.add(SimCardInfo(phoneNumber = customSim2Phone, carrierName = null, isCustom = true))
            addedSim2 = true
        }

        // 回退方案：如果没有获取到任何 SIM 信息但有自定义号码
        if (simCards.isEmpty()) {
            if (customSim1Phone != null) {
                simCards.add(SimCardInfo(phoneNumber = customSim1Phone, carrierName = null, isCustom = true))
            }
            if (customSim2Phone != null) {
                simCards.add(SimCardInfo(phoneNumber = customSim2Phone, carrierName = null, isCustom = true))
            }
            
            // 如果没有自定义号码，尝试获取默认号码
            if (simCards.isEmpty()) {
                try {
                    val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
                    if (telephonyManager != null) {
                        val phoneNumber = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            telephonyManager.line1Number
                        } else {
                            @Suppress("DEPRECATION")
                            telephonyManager.line1Number
                        }
                        val carrierName = try {
                            telephonyManager.networkOperatorName
                        } catch (e: Exception) {
                            null
                        }
                        if (!phoneNumber.isNullOrBlank()) {
                            simCards.add(
                                SimCardInfo(
                                    phoneNumber = phoneNumber,
                                    carrierName = carrierName,
                                    isCustom = false
                                )
                            )
                        }
                    }
                } catch (e: Exception) {
                    Log.e("SimCardInfo", "获取默认号码失败", e)
                }
            }
        }
    } catch (e: Exception) {
        e.printStackTrace()
        // 即使出错，也显示自定义号码（避免重复添加）
        val addedSim1InCatch = simCards.any { it.isCustom }
        if (!addedSim1InCatch && customSim1Phone != null) {
            simCards.add(SimCardInfo(phoneNumber = customSim1Phone, carrierName = null, isCustom = true))
        }
        val addedSim2InCatch = simCards.size > 1
        if (!addedSim2InCatch && customSim2Phone != null) {
            simCards.add(SimCardInfo(phoneNumber = customSim2Phone, carrierName = null, isCustom = true))
        }
    }
    return simCards
}

@Composable
fun SimCardItem(
    slot: Int, 
    phoneNumber: String?, 
    carrierName: String?,
    isCustom: Boolean = false,
    onEdit: () -> Unit = {},
    onClear: () -> Unit = {}
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isCustom) Color(0xFF10B981).copy(alpha = 0.2f) else Color(0xFF3B82F6).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "SIM$slot",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isCustom) Color(0xFF10B981) else Color(0xFF3B82F6)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        phoneNumber ?: "无法获取号码",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = if (phoneNumber != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    if (isCustom) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Surface(
                            color = Color(0xFF10B981).copy(alpha = 0.2f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "自定义",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF10B981),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                carrierName?.takeIf { it.isNotBlank() }?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "编辑",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
                if (isCustom) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "清除",
                            tint = Color(0xFFEE4444)
                        )
                    }
                } else if (phoneNumber != null) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF10B981)
                    )
                } else {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFF59E0B)
                    )
                }
            }
        }
    }
}

@Composable
fun EditSimPhoneDialog(
    slot: Int,
    currentPhoneNumber: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var phoneNumber by remember { mutableStateOf(currentPhoneNumber) }
    
    ModernAlertDialog(
        onDismissRequest = onDismiss,
        title = "设置 SIM$slot 号码",
        content = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "如果无法自动获取本机号码，可以手动输入",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = phoneNumber,
                    onValueChange = { phoneNumber = it },
                    label = { Text("本机号码") },
                    placeholder = { Text("例如：13800138000") },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = {
                        Icon(Icons.Outlined.Phone, contentDescription = null)
                    },
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(phoneNumber) },
                shape = RoundedCornerShape(12.dp)
            ) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

// ========================================
// 新的标签页组件
// ========================================

@Composable
fun HomeTab(
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
    permissionUpdateTrigger: Int
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // Service Status Card
        item {
            ModernCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "转发服务",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                val statusColor = if (isEnabled) Color(0xFF10B981) else Color(0xFF9CA3AF)
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .background(statusColor, shape = CircleShape)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (isEnabled) "服务运行中" else "服务已停止",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        val switchScale by animateFloatAsState(
                            targetValue = if (isEnabled) 1.1f else 1f,
                            animationSpec = spring(stiffness = Spring.StiffnessMedium),
                            label = "switchAnimation"
                        )
                        Switch(
                            checked = isEnabled,
                            onCheckedChange = onEnabledChange,
                            modifier = Modifier.scale(switchScale),
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = Color(0xFF667EEA)
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Permission items
                    PermissionItem(
                        icon = Icons.Outlined.Notifications,
                        title = "通知权限",
                        granted = notifGranted,
                        onClick = onRequestNotificationPermission
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PermissionItem(
                        icon = Icons.Outlined.Sms,
                        title = "短信权限",
                        granted = smsGranted,
                        onClick = onRequestSmsPermission
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    PermissionItem(
                        icon = Icons.Outlined.BatteryFull,
                        title = "电池优化白名单",
                        granted = isIgnoringBatteryOptimizations,
                        onClick = onRequestBatteryOptimization
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    HorizontalDivider()

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.PowerSettingsNew,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "开机自启动",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "设备启动后自动运行",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = startOnBoot,
                            onCheckedChange = onStartOnBootChange
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun KeywordTab(
    channels: List<Channel>,
    configs: List<KeywordConfig>,
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            ModernCard {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF10B981).copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Rule,
                                contentDescription = null,
                                tint = Color(0xFF10B981)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "关键词配置",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "设置转发关键词",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = newKeywordInput,
                        onValueChange = onNewKeywordInputChange,
                        label = { Text("输入关键词（留空表示全部）") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Outlined.Search, contentDescription = null)
                        },
                        shape = RoundedCornerShape(12.dp)
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
                            leadingIcon = {
                                Icon(Icons.Outlined.Send, contentDescription = null)
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(configChannelDropdownExpanded) },
                            shape = RoundedCornerShape(12.dp)
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
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("添加配置", fontSize = 16.sp)
                    }

                    // Config list
                    if (configs.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
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
    }
}

@Composable
fun ChannelTab(
    channels: List<Channel>,
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
    onEditChannel: (Channel) -> Unit
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        item {
            ModernCard {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF667EEA).copy(alpha = 0.1f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Cloud,
                                contentDescription = null,
                                tint = Color(0xFF667EEA)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "转发通道管理",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "添加和管理转发通道",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    OutlinedTextField(
                        value = newChannelName,
                        onValueChange = onNewChannelNameChange,
                        label = { Text("通道名称") },
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Outlined.Label, contentDescription = null)
                        },
                        shape = RoundedCornerShape(12.dp)
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
                            leadingIcon = {
                                Icon(Icons.Outlined.Category, contentDescription = null)
                            },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(channelTypeExpanded) },
                            shape = RoundedCornerShape(12.dp)
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
                        modifier = Modifier.fillMaxWidth(),
                        leadingIcon = {
                            Icon(Icons.Outlined.Link, contentDescription = null)
                        },
                        shape = RoundedCornerShape(12.dp)
                    )

                    Button(
                        onClick = onAddChannel,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(Icons.Outlined.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("添加通道", fontSize = 16.sp)
                    }

                    // Channel list
                    if (channels.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
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
    }
}

@Composable
fun SettingsTab(
    showReceiverPhone: Boolean,
    onShowReceiverPhoneChange: (Boolean) -> Unit,
    showSenderPhone: Boolean,
    onShowSenderPhoneChange: (Boolean) -> Unit,
    highlightVerificationCode: Boolean,
    onHighlightVerificationCodeChange: (Boolean) -> Unit,
    batteryReminderEnabled: Boolean,
    onBatteryReminderEnabledChange: (Boolean) -> Unit,
    lowBatteryThreshold: Int,
    onLowBatteryThresholdChange: (Int) -> Unit,
    highBatteryThreshold: Int,
    onHighBatteryThresholdChange: (Int) -> Unit,
    onShowTestDialog: () -> Unit,
    permissionUpdateTrigger: Int
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        // SIM 卡信息卡片
        item {
            SimCardInfoCard(permissionUpdateTrigger)
        }

        // 电量提醒配置
        item {
            ModernCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "电量提醒",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 电量提醒开关
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.BatteryAlert,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "开启电量提醒",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "电量变化时发送通知提醒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = batteryReminderEnabled,
                            onCheckedChange = onBatteryReminderEnabledChange
                        )
                    }

                    if (batteryReminderEnabled) {
                        Spacer(modifier = Modifier.height(16.dp))

                        // 低电量阈值
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "低电量提醒阈值",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "$lowBatteryThreshold%",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Slider(
                                    value = lowBatteryThreshold.toFloat(),
                                    onValueChange = { onLowBatteryThresholdChange(it.toInt()) },
                                    valueRange = 5f..50f,
                                    steps = 8,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                "当电量低于此阈值时提醒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 高电量阈值
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                "高电量提醒阈值",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "$highBatteryThreshold%",
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Slider(
                                    value = highBatteryThreshold.toFloat(),
                                    onValueChange = { onHighBatteryThresholdChange(it.toInt()) },
                                    valueRange = 50f..100f,
                                    steps = 8,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Text(
                                "当电量高于此阈值时提醒",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // 消息格式配置
        item {
            ModernCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "消息格式",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 显示本机号码
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "显示本机号码",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "转发时显示接收短信的本机号码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showReceiverPhone,
                            onCheckedChange = onShowReceiverPhoneChange
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 显示发送者号码
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.Person,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "显示发送者号码",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "转发时显示短信发送者号码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showSenderPhone,
                            onCheckedChange = onShowSenderPhoneChange
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 突出显示验证码
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.Outlined.VpnKey,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "突出显示验证码",
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                "自动识别并突出显示短信验证码",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = highlightVerificationCode,
                            onCheckedChange = onHighlightVerificationCodeChange
                        )
                    }
                }
            }
        }

        // 工具
        item {
            ModernCard(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text(
                        "工具",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onShowTestDialog,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(vertical = 14.dp)
                    ) {
                        Icon(Icons.Outlined.Science, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("测试规则", fontSize = 16.sp)
                    }
                }
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
    val arrStr = prefs.getString(Constants.PREF_CHANNELS, "[]") ?: "[]"
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
    prefs.edit().putString(Constants.PREF_CHANNELS, arr.toString()).apply()
}

private fun loadConfigs(prefs: android.content.SharedPreferences): List<KeywordConfig> {
    val arrStr = prefs.getString(Constants.PREF_KEYWORD_CONFIGS, "[]") ?: "[]"
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
    prefs.edit().putString(Constants.PREF_KEYWORD_CONFIGS, arr.toString()).apply()
}
