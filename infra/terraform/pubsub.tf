# Main event topic
resource "google_pubsub_topic" "events" {
  name                       = "events"
  message_retention_duration = "604800s"  # 7 days

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

# DLQ topic for failed events
resource "google_pubsub_topic" "dlq" {
  name                       = "dlq"
  message_retention_duration = "604800s"

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

# Sink topics for downstream consumers
resource "google_pubsub_topic" "billing_events" {
  name                       = "billing-events"
  message_retention_duration = "604800s"

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

resource "google_pubsub_topic" "audit_events" {
  name                       = "audit-events"
  message_retention_duration = "604800s"

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

resource "google_pubsub_topic" "analytics_events" {
  name                       = "analytics-events"
  message_retention_duration = "604800s"

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

# Producer service subscription to events topic
resource "google_pubsub_subscription" "events_pull" {
  name    = "events-pull"
  topic   = google_pubsub_topic.events.name
  ack_deadline_seconds = 60

  # Exponential backoff: initial=100ms, max=600s
  dead_letter_policy {
    dead_letter_topic = google_pubsub_topic.dlq.id
    max_delivery_attempts = 5
  }

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

# DLQ subscription for replayer
resource "google_pubsub_subscription" "dlq_replay" {
  name    = "dlq-replay"
  topic   = google_pubsub_topic.dlq.name
  ack_deadline_seconds = 300  # DLQ entries take longer to process

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

# Billing events subscription
resource "google_pubsub_subscription" "billing_events_pull" {
  name    = "billing-events-pull"
  topic   = google_pubsub_topic.billing_events.name
  ack_deadline_seconds = 120

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

# Audit events subscription
resource "google_pubsub_subscription" "audit_events_pull" {
  name    = "audit-events-pull"
  topic   = google_pubsub_topic.audit_events.name
  ack_deadline_seconds = 120

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

# Analytics events subscription
resource "google_pubsub_subscription" "analytics_events_pull" {
  name    = "analytics-events-pull"
  topic   = google_pubsub_topic.analytics_events.name
  ack_deadline_seconds = 120

  labels = {
    environment = var.environment
    app         = "event-processor"
  }
}

# Outputs
output "events_topic" {
  value = google_pubsub_topic.events.id
}

output "dlq_topic" {
  value = google_pubsub_topic.dlq.id
}

output "billing_topic" {
  value = google_pubsub_topic.billing_events.id
}

output "audit_topic" {
  value = google_pubsub_topic.audit_events.id
}

output "analytics_topic" {
  value = google_pubsub_topic.analytics_events.id
}
