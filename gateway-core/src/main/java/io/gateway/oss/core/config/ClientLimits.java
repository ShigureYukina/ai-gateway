package io.gateway.oss.core.config;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.time.Duration;

public class ClientLimits {

    @Min(1)
    private Integer maxTokens;
    @Min(1)
    private Long dailyTokens;
    @Min(1)
    private Long monthlyTokens;
    @Min(1)
    private Long tokensPerMinute;
    @DecimalMin("0.0")
    private BigDecimal dailyCost;
    @DecimalMin("0.0")
    private BigDecimal monthlyCost;
    @Min(1)
    private Integer requestsPerWindow;
    private Duration window;

    public Integer getMaxTokens() {
        return maxTokens;
    }

    public void setMaxTokens(Integer maxTokens) {
        this.maxTokens = maxTokens;
    }

    public Long getDailyTokens() {
        return dailyTokens;
    }

    public void setDailyTokens(Long dailyTokens) {
        this.dailyTokens = dailyTokens;
    }

    public BigDecimal getDailyCost() {
        return dailyCost;
    }

    public void setDailyCost(BigDecimal dailyCost) {
        this.dailyCost = dailyCost;
    }

    public Long getMonthlyTokens() {
        return monthlyTokens;
    }

    public void setMonthlyTokens(Long monthlyTokens) {
        this.monthlyTokens = monthlyTokens;
    }

    public BigDecimal getMonthlyCost() {
        return monthlyCost;
    }

    public void setMonthlyCost(BigDecimal monthlyCost) {
        this.monthlyCost = monthlyCost;
    }

    public Long getTokensPerMinute() {
        return tokensPerMinute;
    }

    public void setTokensPerMinute(Long tokensPerMinute) {
        this.tokensPerMinute = tokensPerMinute;
    }

    public Integer getRequestsPerWindow() {
        return requestsPerWindow;
    }

    public void setRequestsPerWindow(Integer requestsPerWindow) {
        this.requestsPerWindow = requestsPerWindow;
    }

    public Duration getWindow() {
        return window;
    }

    public void setWindow(Duration window) {
        this.window = window;
    }
}
