package com.susumonitor.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "susumonitor_settings")

/**
 * 本地设置持久化（DataStore Preferences）：
 * 目前仅承载告警推送开关；通知开关由设置页读写、前台服务发通知前判断。
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dataStore = context.settingsDataStore

    private object Keys {
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
    }

    /** 是否启用告警推送（默认 true）。 */
    val notificationsEnabled: Flow<Boolean> =
        dataStore.data.map { it[Keys.NOTIFICATIONS_ENABLED] ?: true }

    /** 写入通知开关。 */
    suspend fun setNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }
    }

    /** 同步读取当前开关（前台服务发通知前判断）。 */
    suspend fun notificationsEnabledNow(): Boolean = notificationsEnabled.first()
}
