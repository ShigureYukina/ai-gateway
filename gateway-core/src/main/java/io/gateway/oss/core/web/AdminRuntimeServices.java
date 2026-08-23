package io.gateway.oss.core.web;

import io.gateway.oss.core.contract.AggregateMetricRecorder;
import io.gateway.oss.core.contract.BudgetService;
import io.gateway.oss.core.contract.QuotaService;
import io.gateway.oss.core.contract.TpmService;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 管理端可选运行时服务集合。
 * <p>
 * core 通过契约接口感知 admin 能力，admin 不存在时保持透传语义不变。
 * 这里集中封装可选依赖，避免编排类中散落多组空判断字段。
 * </p>
 */
record AdminRuntimeServices(
        QuotaService quotaService,
        BudgetService budgetService,
        TpmService tpmService,
        AggregateMetricRecorder aggregateMetricRecorder
) {

    static AdminRuntimeServices fromProviders(ObjectProvider<QuotaService> quotaServiceProvider,
                                              ObjectProvider<BudgetService> budgetServiceProvider,
                                              ObjectProvider<TpmService> tpmServiceProvider,
                                              ObjectProvider<AggregateMetricRecorder> aggregateMetricRecorderProvider) {
        return new AdminRuntimeServices(
                quotaServiceProvider.getIfAvailable(),
                budgetServiceProvider.getIfAvailable(),
                tpmServiceProvider.getIfAvailable(),
                aggregateMetricRecorderProvider.getIfAvailable());
    }

    static AdminRuntimeServices none() {
        return new AdminRuntimeServices(null, null, null, null);
    }
}
