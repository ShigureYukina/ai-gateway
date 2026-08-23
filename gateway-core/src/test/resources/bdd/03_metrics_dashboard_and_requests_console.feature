@p1 @metrics @dashboard @console
Feature: Metrics and admin console consistency
  As an admin user
  I want request counters success rates token totals and costs to be reflected in admin views
  So that the console can be used as an operational decision surface

  Background:
    Given the admin dashboard is available
    And the requests and costs page is available
    And the gateway records request count request outcome and latency metrics

  Scenario: Request list summary cards match recent request data
    Given recent requests include the following rows
      | requestId      | status | usageTokens | costUsd |
      | req_metric_001 | 200    | 100         | 0.0100  |
      | req_metric_002 | 200    | 50          | 0.0050  |
      | req_metric_003 | 500    | 0           | 0.0000  |
    When admin opens the requests console
    Then the total request count card should show 3
    And the total tokens card should show 150
    And the total cost card should equal 0.0150 USD within display precision
    And the success rate card should show 66.7 percent within display precision

  Scenario: Admin can inspect request detail from the requests table
    Given recent requests contain request "req_obs_success_001"
    When admin clicks request row "req_obs_success_001" in the requests console
    Then a request detail dialog should open
    And the dialog should show requestId "req_obs_success_001"
    And the dialog should show provider "openai"
    And the dialog should show routeId "openai-primary"
    And the dialog should show the request or response payload according to caller role

  Scenario: Dashboard reflects overall system and usage summary
    When admin opens the dashboard
    Then the dashboard should show cards for total requests total tokens total cost success rate active clients and registered users
    And the dashboard should show request status distribution for 2xx 4xx and 5xx
    And the dashboard should show system status including maintenance mode emergency rate limit and global route availability
