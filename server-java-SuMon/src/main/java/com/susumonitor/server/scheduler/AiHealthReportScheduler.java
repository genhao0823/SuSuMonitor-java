package com.susumonitor.server.scheduler;

import com.susumonitor.server.module.ai.service.AiHealthReportService;
import java.time.Clock;
import java.time.LocalDate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时健康报告调度器（F3）：按配置 cron 生成前一自然日的健康报告，
 * 随 {@code susumonitor.ai.report.enabled} 开关装配；生成失败只记日志，
 * 不影响后续调度轮次（同一报告日可由管理员手动触发重生成，UPSERT 幂等）。
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "susumonitor.ai.report.enabled", havingValue = "true")
public class AiHealthReportScheduler {

    private final ObjectProvider<AiHealthReportService> reportService;
    private final Clock clock;

    /** 注入报告服务（同开关装配）与统一时钟。 */
    public AiHealthReportScheduler(ObjectProvider<AiHealthReportService> reportService, Clock clock) {
        this.reportService = reportService;
        this.clock = clock;
    }

    /** 生成昨日健康报告；调度触发以 actorId=null 走降级语义。 */
    @Scheduled(cron = "${susumonitor.ai.report.generate-cron:0 30 7 * * ?}")
    public void generateDailyReport() {
        AiHealthReportService service = reportService.getIfAvailable();
        if (service == null) {
            return;
        }
        LocalDate reportDate = LocalDate.now(clock).minusDays(1);
        try {
            service.generate(reportDate, null);
        } catch (RuntimeException exception) {
            log.error("AI health report schedule failed, reportDate={}", reportDate, exception);
        }
    }
}
