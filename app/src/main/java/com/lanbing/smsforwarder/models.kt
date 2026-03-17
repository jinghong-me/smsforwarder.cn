package com.lanbing.smsforwarder

/**
 * Shared model types for channels and keyword rules.
 */
enum class ChannelType { WECHAT, DINGTALK, FEISHU, GENERIC_WEBHOOK }

data class Channel(
    val id: String,
    val name: String,
    val type: ChannelType,
    val target: String           // webhook URL
)

data class KeywordConfig(
    val id: String,
    val keyword: String, // empty string means match-all
    val channelId: String
)