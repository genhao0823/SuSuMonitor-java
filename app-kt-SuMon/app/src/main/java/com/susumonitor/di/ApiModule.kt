package com.susumonitor.di

import com.susumonitor.api.AdminApi
import com.susumonitor.api.AlertApi
import com.susumonitor.api.AuthApi
import com.susumonitor.api.MetricsApi
import com.susumonitor.api.ServerApi
import com.susumonitor.api.SystemApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/**
 * Retrofit 接口实例绑定。
 */
@Module
@InstallIn(SingletonComponent::class)
object ApiModule {

    @Provides
    @Singleton
    fun provideAuthApi(retrofit: Retrofit): AuthApi = retrofit.create(AuthApi::class.java)

    @Provides
    @Singleton
    fun provideServerApi(retrofit: Retrofit): ServerApi = retrofit.create(ServerApi::class.java)

    @Provides
    @Singleton
    fun provideMetricsApi(retrofit: Retrofit): MetricsApi = retrofit.create(MetricsApi::class.java)

    @Provides
    @Singleton
    fun provideAlertApi(retrofit: Retrofit): AlertApi = retrofit.create(AlertApi::class.java)

    @Provides
    @Singleton
    fun provideSystemApi(retrofit: Retrofit): SystemApi = retrofit.create(SystemApi::class.java)

    @Provides
    @Singleton
    fun provideAdminApi(retrofit: Retrofit): AdminApi = retrofit.create(AdminApi::class.java)
}
