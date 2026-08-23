@p1 @alerts @operations
Feature: Alert center and operational visibility
  As an operator
  I want operational issues to appear as active alerts and recent alerts
  So that I can detect and respond to configuration or account risk quickly

  Background:
    Given the alert center is enabled
    And admins can access alert data

  Scenario: Disabled route appears as an active alert
    Given route "openai-primary" is disabled
    When admin requests the alert view
    Then the active alerts should include an item of type "route_disabled"
    And that alert should reference source "openai-primary"
    And that alert severity should be "warning"

  Scenario: Frozen user appears as an active alert
    Given user "risk-user" is frozen
    When admin requests the alert view
    Then the active alerts should include an item of type "account_frozen"
    And that alert should reference source "risk-user"
    And that alert severity should be "warning"

  Scenario: Open circuit breaker appears as an active alert until recovery
    Given route "alerts-circuit-route" has an open circuit breaker
    When admin requests the alert view
    Then the active alerts should include an item of type "circuit_open"
    And that alert should reference source "alerts-circuit-route"
    And that alert severity should be "critical"
    When the circuit for route "alerts-circuit-route" recovers
    And admin requests the alert view again
    Then the active alerts should not include an item of type "circuit_open" for source "alerts-circuit-route"

  Scenario: Alert center shows both active and recent lists
    When admin opens the alert center page
    Then the page should show an active alerts section
    And the page should show a recent alerts section
    And each listed alert should include type message source severity and detected time when available
