# Uptime check for Producer service
resource "google_monitoring_uptime_check_config" "producer" {
  display_name = "event-processor-producer-uptime"
  timeout      = "10s"
  period       = "60s"

  http_check {
    request_method = "GET"
    path           = "/actuator/health"
    port           = 443
    use_ssl        = true
  }

  monitored_resource {
    type = "uptime-url"
    labels = {
      host = replace(google_cloud_run_service.producer.status[0].url, "https://", "")
    }
  }

  selected_regions = ["USA", "EUROPE", "ASIA_PAC"]
}

# Uptime check for Consumer service
resource "google_monitoring_uptime_check_config" "consumer" {
  display_name = "event-processor-consumer-uptime"
  timeout      = "10s"
  period       = "60s"

  http_check {
    request_method = "GET"
    path           = "/actuator/health"
    port           = 443
    use_ssl        = true
  }

  monitored_resource {
    type = "uptime-url"
    labels = {
      host = replace(google_cloud_run_service.consumer.status[0].url, "https://", "")
    }
  }

  selected_regions = ["USA", "EUROPE", "ASIA_PAC"]
}

# Alert policy: Producer service down
resource "google_monitoring_alert_policy" "producer_down" {
  count = var.alert_email != "" ? 1 : 0

  display_name = "event-processor-producer-down"
  combiner     = "OR"

  conditions {
    display_name = "Producer service uptime check failed"

    condition_threshold {
      filter          = "resource.type = \"uptime-url\" AND resource.label.host = \"${replace(google_cloud_run_service.producer.status[0].url, "https://", "")}\" AND metric.type = \"monitoring.googleapis.com/uptime_check/check_passed\""
      duration        = "60s"
      comparison      = "COMPARISON_LT"
      threshold_value = 1
    }
  }

  notification_channels = [google_monitoring_notification_channel.email[0].id]
}

# Alert policy: High DLQ depth
resource "google_monitoring_alert_policy" "dlq_depth_high" {
  count = var.enable_detailed_monitoring ? 1 : 0

  display_name = "event-processor-dlq-depth-high"
  combiner     = "OR"

  conditions {
    display_name = "DLQ depth > 1000"

    condition_threshold {
      filter          = "resource.type = \"pubsub_subscription\" AND resource.label.subscription_id = \"${google_pubsub_subscription.dlq_replay.id}\" AND metric.type = \"pubsub.googleapis.com/subscription/num_unacked_messages\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 1000
    }
  }

  notification_channels = var.alert_email != "" ? [google_monitoring_notification_channel.email[0].id] : []
}

# Alert policy: Circuit breaker open
resource "google_monitoring_alert_policy" "circuit_breaker_open" {
  count = var.enable_detailed_monitoring ? 1 : 0

  display_name = "event-processor-circuit-breaker-open"
  combiner     = "OR"

  conditions {
    display_name = "Circuit breaker state is OPEN"

    condition_threshold {
      filter          = "resource.type = \"cloud_run_revision\" AND metric.type = \"custom.googleapis.com/event_processor/circuit_breaker_state\" AND metric.label.state = \"OPEN\""
      duration        = "60s"
      comparison      = "COMPARISON_GT"
      threshold_value = 0
    }
  }

  notification_channels = var.alert_email != "" ? [google_monitoring_notification_channel.email[0].id] : []
}

# Alert policy: Redis memory high
resource "google_monitoring_alert_policy" "redis_memory_high" {
  count = var.enable_detailed_monitoring ? 1 : 0

  display_name = "event-processor-redis-memory-high"
  combiner     = "OR"

  conditions {
    display_name = "Redis memory usage > 80%"

    condition_threshold {
      filter          = "resource.type = \"redis_instance\" AND metric.type = \"redis.googleapis.com/stats/memory/usage\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = google_redis_instance.cache.memory_size_gb * 1000 * 0.8  # 80% threshold
    }
  }

  notification_channels = var.alert_email != "" ? [google_monitoring_notification_channel.email[0].id] : []
}

# Alert policy: High publish latency
resource "google_monitoring_alert_policy" "publish_latency_high" {
  count = var.enable_detailed_monitoring ? 1 : 0

  display_name = "event-processor-publish-latency-high"
  combiner     = "OR"

  conditions {
    display_name = "P95 publish latency > 5s"

    condition_threshold {
      filter          = "resource.type = \"cloud_run_revision\" AND metric.type = \"custom.googleapis.com/event_processor/publish_latency\" AND metric.label.quantile = \"0.95\""
      duration        = "300s"
      comparison      = "COMPARISON_GT"
      threshold_value = 5000  # 5 seconds in milliseconds
    }
  }

  notification_channels = var.alert_email != "" ? [google_monitoring_notification_channel.email[0].id] : []
}

# Email notification channel
resource "google_monitoring_notification_channel" "email" {
  count           = var.alert_email != "" ? 1 : 0
  display_name    = "event-processor-alerts"
  type            = "email"
  enabled         = true
  labels = {
    email_address = var.alert_email
  }
}

# Log sink for Cloud Logging
resource "google_logging_project_sink" "event_processor_logs" {
  name        = "event-processor-logs-sink"
  destination = "logging.googleapis.com/logs/event-processor"

  filter = jsonencode({
    resource = {
      type = "cloud_run_revision"
    }
  })

  unique_writer_identity = true
}

# Dashboard (JSON)
resource "google_monitoring_dashboard" "event_processor" {
  dashboard_json = jsonencode({
    displayName = "Event Processor"
    mosaicLayout = {
      columns = 12
      tiles = [
        {
          width  = 6
          height = 4
          widget = {
            title = "Events Published (Rate)"
            xyChart = {
              dataSets = [
                {
                  timeSeriesQuery = {
                    timeSeriesFilter = {
                      filter = "metric.type = \"custom.googleapis.com/event_processor/events_published\" resource.type = \"cloud_run_revision\""
                    }
                  }
                }
              ]
            }
          }
        },
        {
          xPos   = 6
          width  = 6
          height = 4
          widget = {
            title = "DLQ Depth"
            xyChart = {
              dataSets = [
                {
                  timeSeriesQuery = {
                    timeSeriesFilter = {
                      filter = "metric.type = \"pubsub.googleapis.com/subscription/num_unacked_messages\" resource.label.subscription_id = \"${google_pubsub_subscription.dlq_replay.id}\""
                    }
                  }
                }
              ]
            }
          }
        },
        {
          yPos   = 4
          width  = 6
          height = 4
          widget = {
            title = "Publish Latency (P95)"
            xyChart = {
              dataSets = [
                {
                  timeSeriesQuery = {
                    timeSeriesFilter = {
                      filter = "metric.type = \"custom.googleapis.com/event_processor/publish_latency\" metric.label.quantile = \"0.95\""
                    }
                  }
                }
              ]
            }
          }
        },
        {
          xPos   = 6
          yPos   = 4
          width  = 6
          height = 4
          widget = {
            title = "Circuit Breaker State"
            xyChart = {
              dataSets = [
                {
                  timeSeriesQuery = {
                    timeSeriesFilter = {
                      filter = "metric.type = \"custom.googleapis.com/event_processor/circuit_breaker_state\""
                    }
                  }
                }
              ]
            }
          }
        }
      ]
    }
  })
}

output "notification_channel_id" {
  value       = var.alert_email != "" ? google_monitoring_notification_channel.email[0].id : null
  description = "Notification channel ID for alerts"
}

output "dashboard_url" {
  value       = "https://console.cloud.google.com/monitoring/dashboards/custom/${google_monitoring_dashboard.event_processor.id}?project=${var.project_id}"
  description = "URL to monitoring dashboard"
}
