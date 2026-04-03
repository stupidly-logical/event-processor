output "project_id" {
  value       = var.project_id
  description = "GCP Project ID"
}

output "region" {
  value       = var.region
  description = "GCP Region"
}

# Pub/Sub Outputs
output "events_topic_id" {
  value       = google_pubsub_topic.events.id
  description = "Main events Pub/Sub topic"
}

output "dlq_topic_id" {
  value       = google_pubsub_topic.dlq.id
  description = "Dead letter queue Pub/Sub topic"
}

output "billing_topic_id" {
  value       = google_pubsub_topic.billing_events.id
  description = "Billing events sink topic"
}

output "audit_topic_id" {
  value       = google_pubsub_topic.audit_events.id
  description = "Audit events sink topic"
}

output "analytics_topic_id" {
  value       = google_pubsub_topic.analytics_events.id
  description = "Analytics events sink topic"
}

# Redis Outputs
output "redis_host" {
  value       = google_redis_instance.cache.host
  description = "Redis instance host address"
}

output "redis_port" {
  value       = google_redis_instance.cache.port
  description = "Redis instance port"
}

output "redis_auth_string" {
  value       = google_redis_instance.cache.auth_string
  sensitive   = true
  description = "Redis authentication password"
}

output "redis_connection_string" {
  value       = "redis://:${google_redis_instance.cache.auth_string}@${google_redis_instance.cache.host}:${google_redis_instance.cache.port}"
  sensitive   = true
  description = "Full Redis connection string with auth"
}

# Cloud Run Outputs
output "producer_service_url" {
  value       = google_cloud_run_service.producer.status[0].url
  description = "Producer service Cloud Run URL"
}

output "consumer_service_url" {
  value       = google_cloud_run_service.consumer.status[0].url
  description = "Consumer service Cloud Run URL"
}

output "producer_service_account" {
  value       = google_service_account.event_processor.email
  description = "Service account email for IAM role assignments"
}

# Configuration Summary
output "deployment_summary" {
  value = {
    environment = var.environment
    region      = var.region
    redis_size  = "${var.redis_memory_gb}GB"
    redis_tier  = var.redis_tier
    producer = {
      cpu         = var.producer_service_cpu
      memory      = var.producer_service_memory
      min_scale   = var.producer_service_min_instances
      max_scale   = var.producer_service_max_instances
      concurrency = var.producer_service_concurrency
    }
    consumer = {
      cpu         = var.consumer_service_cpu
      memory      = var.consumer_service_memory
      min_scale   = var.consumer_service_min_instances
      max_scale   = var.consumer_service_max_instances
      concurrency = var.consumer_service_concurrency
    }
  }
  description = "Summary of deployed configuration"
}

output "next_steps" {
  value = <<-EOT
    Deployment complete! Next steps:

    1. Update your applications with environment variables:
       export REDIS_HOST=${google_redis_instance.cache.host}
       export REDIS_PORT=${google_redis_instance.cache.port}
       export PUBSUB_PROJECT_ID=${var.project_id}

    2. Test the services:
       curl ${google_cloud_run_service.producer.status[0].url}/actuator/health
       curl ${google_cloud_run_service.consumer.status[0].url}/actuator/health

    3. Publish a test event:
       curl -X POST ${google_cloud_run_service.producer.status[0].url}/api/v1/events/publish \\
         -H "Content-Type: application/json" \\
         -d '{
           "type": "PushEvent",
           "actor": {"id": 123, "login": "test"},
           "repo": {"id": 456, "name": "test/repo"},
           "payload": {"size": 1},
           "created_at": "2024-01-01T00:00:00Z"
         }'

    4. Monitor metrics:
       https://console.cloud.google.com/monitoring?project=${var.project_id}

    5. View logs:
       gcloud logging read "resource.type=cloud_run_revision" --project=${var.project_id}
  EOT
}
