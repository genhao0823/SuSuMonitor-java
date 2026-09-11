package com.susumonitor.server.module.ai.controller;

import com.susumonitor.server.common.ApiResponse;
import com.susumonitor.server.common.BusinessException;
import com.susumonitor.server.common.ErrorCode;
import com.susumonitor.server.common.vo.PageResult;
import com.susumonitor.server.module.ai.dto.GenerateHealthReportRequest;
import com.susumonitor.server.module.ai.service.AiHealthReportService;
import com.susumonitor.server.module.ai.vo.AiHealthReportVo;
import com.susumonitor.server.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.Clock;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 提供管理员专用的 AI 定时健康报告端点（F3）：历史分页查询与手动触发生成。
 *
 * <p>随 {@code susumonitor.ai.report.enabled} 开关装配：关闭时本 Controller
 * 不创建，请求按未知路径返回 404，与命令域开关行为一致。</p>
 */
@Tag(name = "ai-health-report", description = "AI scheduled health report review and manual generation")
@RestController
@RequestMapping("/api/ai/health-reports")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "susumonitor.ai.report.enabled", havingValue = "true")
public class AiHealthReportController {

    private final AiHealthReportService reportService;
    private final Clock clock;

    /** 分页返回历史报告，按 report_date 倒序。 */
    @Operation(summary = "List scheduled health reports",
            description = "Admin-only paginated history of generated daily health reports, "
                    + "newest first. 404 path when susumonitor.ai.report.enabled=false.",
            operationId = "listHealthReports", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping
    public ApiResponse<PageResult<AiHealthReportVo>> list(
            @RequestParam("page") Integer page,
            @RequestParam("page_size") Integer pageSize) {
        return ApiResponse.success(reportService.list(page, pageSize));
    }

    /** 返回一份报告的完整视图；不存在时 404。 */
    @Operation(summary = "Get a scheduled health report",
            description = "Admin-only full report view: summary, top concerns, limitations, "
                    + "aggregated facts snapshot and usage. 404 when the report does not exist.",
            operationId = "getHealthReport", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/{id}")
    public ApiResponse<AiHealthReportVo> get(@PathVariable("id") Long id) {
        return ApiResponse.success(reportService.get(id));
    }

    /**
     * 手动触发生成（或重生成）一份报告；report_date 缺省为昨日，晚于当日拒绝。
     * 同一报告日重复触发覆盖旧报告（UPSERT 幂等）。
     */
    @Operation(summary = "Generate a health report on demand",
            description = "Admin-only manual trigger. report_date defaults to yesterday and must not "
                    + "be in the future; regeneration for the same date overwrites via UPSERT. "
                    + "Provider failures degrade to a facts-only report (status=degraded).",
            operationId = "generateHealthReport", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/generate")
    public ApiResponse<AiHealthReportVo> generate(
            @Valid @RequestBody GenerateHealthReportRequest request,
            @AuthenticationPrincipal AuthenticatedUser operator) {
        LocalDate reportDate = request.getReportDate() == null
                ? LocalDate.now(clock).minusDays(1) : request.getReportDate();
        if (reportDate.isAfter(LocalDate.now(clock))) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST_PARAMETER);
        }
        return ApiResponse.success(reportService.generate(reportDate, operator.id()));
    }
}
