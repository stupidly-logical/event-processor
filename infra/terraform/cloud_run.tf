# Producer Service Cloud Run deployment
resource "google_cloud_run_service" "producer" {
  name     = "event-processor-producer"
  location = var.region

  template {
    spec {
      service_account_name = google_service_account.event_processor.email

      containers {
        image = var.producer_service_image

        resources {
          limits = {
            memory = var.producer_service_memory
            cpu    = var.producer_service_cpu
          }
        }

        env {
          name  = "JAVA_OPTS"
          value = "-Dspring.profiles.active=prod -Xmx1500m"
        }

        env {
          name  = "PUBSUB_PROJECT_ID"
          value = var.project_id
        }

        env {
          name  = "REDIS_HOST"
          value = google_redis_instance.cache.host
        }

        env {
          name  = "REDIS_PORT"
          value = tostring(google_redis_instance.cache.port)
        }

        env {
          name  = "REDIS_AUTH_STRING"
          value_from {
            secret_key_ref {
              name = google_secret_manager_secret.redis_auth.id
              key  = "latest"
            }
          }
        }

        # Liveness probe
        liveness_probe {
          http_get {
            path = "/actuator/health/liveness"
            port = 8080
          }
          initial_delay_seconds = 30
          period_seconds        = 30
        }

        # Readiness probe
        startup_probe {
          http_get {
            path = "/actuator/health/readiness"
            port = 8080
          }
          initial_delay_seconds = 10
          period_seconds        = 10
        }
      }

      timeout_seconds = 300
    }

    metadata {
      annotations = {
        "autoscaling.knative.dev/min-scale"         = tostring(var.producer_service_min_instances)
        "autoscaling.knative.dev/max-scale"         = tostring(var.producer_service_max_instances)
        "autoscaling.knative.dev/target-utilization" = "0.7"
      }
    }
  }

  traffic {
    percent         = 100
    latest_revision = true
  }

  depends_on = [
    google_service_networking_connection.redis_connection,
    google_secret_manager_secret_version.redis_auth
  ]
}

# Consumer Service Cloud Run deployment
resource "google_cloud_run_service" "consumer" {
  name     = "event-processor-consumer"
  location = var.region

  template {
    spec {
      service_account_name = google_service_account.event_processor.email

      containers {
        image = var.consumer_service_image

        resources {
          limits = {
            memory = var.consumer_service_memory
            cpu    = var.consumer_service_cpu
          }
        }

        env {
          name  = "JAVA_OPTS"
          value = "-Dspring.profiles.active=prod -Xmx1500m"
        }

        env {
          name  = "PUBSUB_PROJECT_ID"
          value = var.project_id
        }

        env {
          name  = "REDIS_HOST"
          value = google_redis_instance.cache.host
        }

        env {
          name  = "REDIS_PORT"
          value = tostring(google_redis_instance.cache.port)
        }

        env {
          name  = "REDIS_AUTH_STRING"
          value_from {
            secret_key_ref {
              name = google_secret_manager_secret.redis_auth.id
              key  = "latest"
            }
          }
        }

        # Liveness probe
        liveness_probe {
          http_get {
            path = "/actuator/health/liveness"
            port = 8081
          }
          initial_delay_seconds = 30
          period_seconds        = 30
        }

        # Readiness probe
        startup_probe {
          http_get {
            path = "/actuator/health/readiness"
            port = 8081
          }
          initial_delay_seconds = 10
          period_seconds        = 10
        }
      }

      timeout_seconds = 300
    }

    metadata {
      annotations = {
        "autoscaling.knative.dev/min-scale"         = tostring(var.consumer_service_min_instances)
        "autoscaling.knative.dev/max-scale"         = tostring(var.consumer_service_max_instances)
        "autoscaling.knative.dev/target-utilization" = "0.7"
      }
    }
  }

  traffic {
    percent         = 100
    latest_revision = true
  }

  depends_on = [
    google_service_networking_connection.redis_connection,
    google_secret_manager_secret_version.redis_auth
  ]
}

# Service account for Cloud Run to access Redis
resource "google_service_account_iam_member" "cloud_run_redis" {
  service_account_id = google_service_account.event_processor.name
  role               = "roles/redis.admin"
  member             = "serviceAccount:${google_service_account.event_processor.email}"
}

# Make services publicly accessible (implement auth via Service-to-Service)
resource "google_cloud_run_service_iam_member" "producer_public" {
  service      = google_cloud_run_service.producer.name
  location     = google_cloud_run_service.producer.location
  role         = "roles/run.invoker"
  member       = "allUsers"
}

resource "google_cloud_run_service_iam_member" "consumer_public" {
  service      = google_cloud_run_service.consumer.name
  location     = google_cloud_run_service.consumer.location
  role         = "roles/run.invoker"
  member       = "allUsers"
}

# Store Redis auth string in Secret Manager
resource "google_secret_manager_secret" "redis_auth" {
  secret_id = "event-processor-redis-auth"

  labels = {
    environment = var.environment
  }
}

resource "google_secret_manager_secret_version" "redis_auth" {
  secret      = google_secret_manager_secret.redis_auth.id
  secret_data = google_redis_instance.cache.auth_string
}

# Grant Cloud Run secret accessor role
resource "google_secret_manager_secret_iam_member" "producer_redis_auth" {
  secret_id = google_secret_manager_secret.redis_auth.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.event_processor.email}"
}

# Outputs
output "producer_service_url" {
  value = google_cloud_run_service.producer.status[0].url
}

output "consumer_service_url" {
  value = google_cloud_run_service.consumer.status[0].url
}
