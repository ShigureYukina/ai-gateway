@p0 @security @trace @redaction
Feature: Trace access control and redaction
  As a security-conscious operator
  I want trace bodies to follow role-based visibility rules
  So that observability does not leak sensitive request or response data

  Background:
    Given request detail "req_trace_acl_001" exists in trace storage
    And that trace contains requestBody and responseBody
    And an admin principal can access system views
    And a non-admin system-view principal can access system views

  Scenario: Admin sees full request and response bodies
    When admin requests GET "/internal/requests/req_trace_acl_001"
    Then the response status should be 200
    And the trace requestBody should not be empty
    And the trace responseBody should not be empty

  Scenario: Non-admin system viewer sees redacted bodies only
    When a non-admin system-view principal requests GET "/internal/requests/req_trace_acl_001"
    Then the response status should be 200
    And the trace requestId should equal "req_trace_acl_001"
    And the trace requestBody should be null
    And the trace responseBody should be null
    And the trace provider should still be visible
    And the trace routeId should still be visible

  Scenario: Unauthorized caller cannot access request detail
    When a caller without system-view permission requests GET "/internal/requests/req_trace_acl_001"
    Then the response should be authorization denied
