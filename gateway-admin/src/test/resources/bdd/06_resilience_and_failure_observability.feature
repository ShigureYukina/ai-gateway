@p1 @resilience @failure @observability
Feature: Resilience events are observable and actionable
  As an SRE
  I want failures quota rejections and route unavailability to leave observable evidence
  So that instability can be diagnosed and mitigated quickly

  Background:
    Given the gateway enforces quota and route availability policies
    And operational and request views are enabled for admins

  Scenario: Quota rejection is visible through API result and request history
    Given client "demo-client-key" daily token limit is set to 1
    And one successful request has already consumed that quota
    When client "demo-client-key" sends another POST "/v1/chat/completions"
    Then the response status should be 429
    And the response error code should be "quota_exceeded"
    And the rejected behavior should be visible from user quota or usage views

  Scenario: System status exposes route availability and operational gates
    When admin requests GET "/internal/system/status"
    Then the response status should be 200
    And the response should contain maintenance.active
    And the response should contain emergencyRateLimit.enabled
    And the response should contain globalCircuit.hasAvailableRoute

  Scenario: Provider or route disablement is visible in operational surfaces
    Given no enabled route has runtime availability
    When admin opens the dashboard
    Then the system status section should show unavailable route state
    And the operator should have enough information to continue investigation through requests runtime or alerts views
