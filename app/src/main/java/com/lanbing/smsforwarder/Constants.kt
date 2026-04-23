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

/**
 * 应用常量
 */
object Constants {
    // 日志
    const val LOG_FILE_NAME = "sms_forwarder_logs.txt"
    const val MAX_LOG_ENTRIES = 200
    const val MAX_LOG_LINE_LENGTH = 2000

    // 去重
    const val DUPLICATE_WINDOW_MS = 5000L

    // 重试
    const val MAX_RETRY_ATTEMPTS = 3
    const val INITIAL_RETRY_BACKOFF_MS = 2000L
    const val MAX_FAILED_MESSAGES = 100
    const val FAILED_MESSAGES_FILE = "failed_messages.json"

    // 线程
    const val THREAD_POOL_SIZE = 4
    const val BROADCAST_TIMEOUT_SECONDS = 45L

    // 通知
    const val NOTIFICATION_UPDATE_THROTTLE_MS = 1000L
    const val NOTIFICATION_CHANNEL_ID = "sms_forwarder_channel"
    const val NOTIFICATION_CHANNEL_NAME = "短信转发服务"
    const val NOTIFICATION_ID = 1423

    // 偏好设置键
    const val PREFS_NAME = "app_config"
    const val PREF_ENABLED = "enabled"
    const val PREF_START_ON_BOOT = "start_on_boot"
    const val PREF_CHANNELS = "channels"
    const val PREF_KEYWORD_CONFIGS = "keyword_configs"
    const val PREF_SHOW_RECEIVER_PHONE = "show_receiver_phone"
    const val PREF_SHOW_SENDER_PHONE = "show_sender_phone"
    const val PREF_HIGHLIGHT_VERIFICATION_CODE = "highlight_verification_code"
    const val PREF_CUSTOM_SIM1_PHONE = "custom_sim1_phone"
    const val PREF_CUSTOM_SIM2_PHONE = "custom_sim2_phone"

    // 电量提醒
    const val PREF_BATTERY_REMINDER_ENABLED = "battery_reminder_enabled"
    const val PREF_LOW_BATTERY_REMINDER_ENABLED = "low_battery_reminder_enabled"
    const val PREF_HIGH_BATTERY_REMINDER_ENABLED = "high_battery_reminder_enabled"
    const val PREF_LOW_BATTERY_THRESHOLD = "low_battery_threshold"
    const val PREF_HIGH_BATTERY_THRESHOLD = "high_battery_threshold"
    const val PREF_LAST_LOW_BATTERY_REMIND_LEVEL = "last_low_battery_remind_level"
    const val PREF_LAST_HIGH_BATTERY_REMIND_LEVEL = "last_high_battery_remind_level"
    const val DEFAULT_LOW_BATTERY_THRESHOLD = 10
    const val DEFAULT_HIGH_BATTERY_THRESHOLD = 90

    // 网络
    const val NETWORK_DEBOUNCE_MS = 2000L
    const val CALL_TIMEOUT_SECONDS = 20L
    const val CONNECT_TIMEOUT_SECONDS = 10L
    const val READ_TIMEOUT_SECONDS = 20L
}
