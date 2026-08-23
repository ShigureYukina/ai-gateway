@p0 @observability @trace @request-log
Feature: Request observability closed loop
  As a platform operator
  I want each model invocation to be observable from request entry to admin review
  So that I can verify the system forms a complete logs-metrics-traces feedback loop

  Background:
    Given request logging and trace persistence are enabled
    And admin user "admin" with password "admin123" can sign in
    And client key "demo-client-key" can call model "gpt-4o-mini"

  Scenario: A successful chat request is visible in recent requests and request detail
    When client "demo-client-key" sends POST "/v1/chat/completions" with request id "req_obs_success_001"
    And the request model is "gpt-4o-mini"
    And the request message contains "hello"
    Then the response status should be 200
    And the response header "X-Request-Id" should equal "req_obs_success_001"

    When admin requests GET "/admin/requests/recent?limit=10"
    Then the response should contain a request with requestId "req_obs_success_001"
    And that request should have model "gpt-4o-mini"
    And that request should have provider "openai"
    And that request should have routeId "openai-primary"
    And that request should have scene "default-chat"
    And that request should have status 200
    And that request should contain latencyMs
    And that request should contain usageTokens
    And that request should contain costUsd

    When admin requests GET "/internal/requests/req_obs_success_001"
    Then the response status should be 200
    And the trace requestId should equal "req_obs_success_001"
    And the trace requestBody should contain "hello"
    And the trace responseBody should contain "chatcmpl"
    And the trace provider should equal "openai"
    And the trace routeId should equal "openai-primary"

  Scenario: Recent requests can be filtered by model client and status
    Given requests already exist across multiple clients models and statuses
    When admin requests GET "/admin/requests/recent?client=demo-client-key&model=gpt-4o-mini&status=200&limit=50"
    Then each returned request should have clientId "demo-client-key"
    And each returned request should have model "gpt-4o-mini"
    And each returned request should have status 200

  Scenario: User can view only their own recent usage records
    Given user "user1" with password "pass1" exists
    When user "user1" sends POST "/v1/chat/completions" with request id "req_user_recent_001"
    And user "user1" requests GET "/auth/usage/recent?limit=10"
    Then the response status should be 200
    And the response should include usage request "req_user_recent_001"
    And the response should not expose other users' unrelated requests
