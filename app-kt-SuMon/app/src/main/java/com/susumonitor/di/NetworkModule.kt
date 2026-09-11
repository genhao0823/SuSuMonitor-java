package com.susumonitor.di

import android.content.Context
import android.content.pm.ApplicationInfo
import com.susumonitor.api.interceptor.AuthInterceptor
import com.susumonitor.api.interceptor.TokenProvider
import com.susumonitor.data.AppJson
import com.susumonitor.data.SessionStore
import com.susumonitor.util.Constants
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/**
 * 网络层 DI 模块：OkHttp、Retrofit、JSON 及各 Retrofit 接口。
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    private val jsonMediaType = "application/json".toMediaType()

    @Provides
    @Singleton
    fun provideJson(): Json = AppJson

    @Provides
    @Singleton
    fun provideTokenProvider(sessionStore: SessionStore): TokenProvider =
        TokenProvider { sessionStore.currentToken() }

    @Provides
    @Singleton
    fun provideTokenCipher(): com.susumonitor.data.security.TokenCipher =
        com.susumonitor.data.security.AndroidKeystoreTokenCipher()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        @ApplicationContext context: Context,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // 读超时=字节间隔上限：AI 诊断/问答在服务端推理期间不吐字节，可到分钟级；
            // 普通接口快速返回不受影响，故统一放宽而非单为 AI 拆分通道
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .addInterceptor(authInterceptor)

        // 仅 debug 构建启用日志（避免依赖 BuildConfig 生成开关）
        val isDebug = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (isDebug) {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }
            builder.addInterceptor(logging)
        }
        return builder.build()
    }

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            .baseUrl("${Constants.BASE_URL}/api/")
            .client(client)
            .addConverterFactory(json.asConverterFactory(jsonMediaType))
            .build()
}
