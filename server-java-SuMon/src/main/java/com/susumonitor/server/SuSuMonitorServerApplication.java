package com.susumonitor.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;

/**
 * SuSuMonitor Java backend application entry point.
 */
@EnableScheduling
// 扫描项目内的 MyBatis Mapper 接口并注册为 Spring Bean。
// 限定 annotationClass = Mapper.class，避免 module 包下非 Mapper 的接口
// （如 sealed interface AlertTransition）被误扫描为 Mapper。
@MapperScan(basePackages = "com.susumonitor.server.module", annotationClass = Mapper.class)
@ConfigurationPropertiesScan
@SpringBootApplication
public class SuSuMonitorServerApplication {

    /**
     * 启动 Spring Boot 应用。
     *
     * @param args 命令行参数
     */
    public static void main(String[] args) {
        SpringApplication.run(SuSuMonitorServerApplication.class, args);
    }
}
