@p0 @cost @reporting @usage
Feature: Cost attribution and reporting closed loop
  As a finance-aware platform owner
  I want each request to roll up into model and client level cost views
  So that costs are attributable auditable and optimizable

  Background:
    Given request logs include usageTokens promptTokens completionTokens and costUsd when available
    And the requests and costs page is available to admins

  Scenario: Cost by model aggregates request usage for a day
    Given requests on day "2026-05-26" include model usage for multiple models
    When admin requests GET "/internal/cost/by-model?day=2026-05-26"
    Then the response status should be 200
    And the response should group rows by model
    And each row should contain requests totalTokens and totalCostUsd
    And models should be sorted by model name

  Scenario: Cost by client shows per-model breakdown for a client and period
    Given client "demo-client-key" has requests in the selected time range
    When admin requests GET "/internal/cost/client?client=demo-client-key"
    Then the response status should be 200
    And the response should contain one row per model
    And each row should contain requests totalTokens promptTokens completionTokens and totalCostUsd

  Scenario: Requests and costs console can export request-level cost data
    Given recent request data exists in the admin requests tab
    When admin clicks "导出 CSV"
    Then the exported file should contain columns for timestamp client model provider status latency tokens and cost

  Scenario: Dashboard cost summary is consistent with model and client aggregates
    When admin opens the dashboard and cost views for the same day
    Then total cost shown on summary cards should reconcile with aggregated model or client totals within display precision
