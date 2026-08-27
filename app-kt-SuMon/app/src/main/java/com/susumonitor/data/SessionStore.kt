package com.susumonitor.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.susumonitor.data.model.CurrentUser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "susumonitor_session")

/**
 * 登录会话持久化（DataStore Preferences）：
 * 保存 JWT 与当前用户快照，登录态恢复与 401 清会话都经由这里。
 */
@Singleton
class SessionStore @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val dataStore = context.dataStore

    private object Keys {
        val TOKEN = stringPreferencesKey("token")
        val USER_ID = stringPreferencesKey("user_id")
        val USERNAME = stringPreferencesKey("username")
        val ROLE = stringPreferencesKey("role")
        val REVIEW_STATUS = stringPreferencesKey("review_status")
        val CREATED_AT = stringPreferencesKey("created_at")
    }

    /** 当前 JWT，无会话时为 null。 */
    val token: Flow<String?> = dataStore.data.map { it[Keys.TOKEN] }

    /** 当前会话快照。 */
    val session: Flow<com.susumonitor.data.model.Session?> = dataStore.data.map { prefs ->
        val token = prefs[Keys.TOKEN] ?: return@map null
        val userId = prefs[Keys.USER_ID] ?: return@map null
        com.susumonitor.data.model.Session(
            token = token,
            user = CurrentUser(
                id = userId.toLongOrNull() ?: 0L,
                username = prefs[Keys.USERNAME].orEmpty(),
                role = prefs[Keys.ROLE].orEmpty(),
                reviewStatus = prefs[Keys.REVIEW_STATUS].orEmpty(),
                createdAt = prefs[Keys.CREATED_AT].orEmpty(),
            ),
        )
    }

    /** 同步读取当前 token（供 OkHttp 拦截器使用）。 */
    suspend fun currentToken(): String? = dataStore.data.first()[Keys.TOKEN]

    /** 保存登录成功后的会话。 */
    suspend fun saveSession(token: String, user: CurrentUser) {
        dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = token
            prefs[Keys.USER_ID] = user.id.toString()
            prefs[Keys.USERNAME] = user.username
            prefs[Keys.ROLE] = user.role
            prefs[Keys.REVIEW_STATUS] = user.reviewStatus
            prefs[Keys.CREATED_AT] = user.createdAt
        }
    }

    /** 清除会话（登出 / 401 会话失效）。 */
    suspend fun clear() {
        dataStore.edit { it.clear() }
    }
}
