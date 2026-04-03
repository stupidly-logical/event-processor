terraform {
  required_version = ">= 1.5"

  required_providers {
    google = {
      source  = "hashicorp/google"
      version = "~> 5.0"
    }
  }

  # Uncomment to enable remote state (GCS backend)
  # backend "gcs" {
  #   bucket  = "your-project-tfstate"
  #   prefix  = "terraform/event-processor"
  #   encryption_key = "your-encryption-key"
  # }
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# Create service account for the event processor services
resource "google_service_account" "event_processor" {
  account_id   = "event-processor-sa"
  display_name = "Event Processor Service Account"
  description  = "Service account for event publishing system"
}

# Grant necessary permissions
resource "google_project_iam_member" "pubsub_publisher" {
  project = var.project_id
  role    = "roles/pubsub.publisher"
  member  = "serviceAccount:${google_service_account.event_processor.email}"
}

resource "google_project_iam_member" "pubsub_subscriber" {
  project = var.project_id
  role    = "roles/pubsub.subscriber"
  member  = "serviceAccount:${google_service_account.event_processor.email}"
}

resource "google_project_iam_member" "dataflow_admin" {
  project = var.project_id
  role    = "roles/dataflow.admin"
  member  = "serviceAccount:${google_service_account.event_processor.email}"
}

resource "google_project_iam_member" "dataflow_worker" {
  project = var.project_id
  role    = "roles/dataflow.worker"
  member  = "serviceAccount:${google_service_account.event_processor.email}"
}

resource "google_project_iam_member" "logging_log_writer" {
  project = var.project_id
  role    = "roles/logging.logWriter"
  member  = "serviceAccount:${google_service_account.event_processor.email}"
}

resource "google_project_iam_member" "monitoring_metric_writer" {
  project = var.project_id
  role    = "roles/monitoring.metricWriter"
  member  = "serviceAccount:${google_service_account.event_processor.email}"
}
