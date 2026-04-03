variable "project_id" {
  description = "GCP Project ID"
  type        = string
}

variable "region" {
  description = "GCP region for resources"
  type        = string
  default     = "us-central1"
}

variable "environment" {
  description = "Environment name (dev, staging, prod)"
  type        = string
  default     = "prod"

  validation {
    condition     = contains(["dev", "staging", "prod"], var.environment)
    error_message = "Environment must be dev, staging, or prod."
  }
}

# Redis Configuration
variable "redis_tier" {
  description = "Redis tier (BASIC or STANDARD). Use BASIC for dev, STANDARD for production HA"
  type        = string
  default     = "STANDARD"

  validation {
    condition     = contains(["BASIC", "STANDARD"], var.redis_tier)
    error_message = "Redis tier must be BASIC or STANDARD."
  }
}

variable "redis_memory_gb" {
  description = "Redis memory size in GB"
  type        = number
  default     = 4

  validation {
    condition     = var.redis_memory_gb >= 1 && var.redis_memory_gb <= 300
    error_message = "Redis memory must be between 1 and 300 GB."
  }
}

# Cloud Run Configuration
variable "producer_service_memory" {
  description = "Cloud Run memory allocation for producer service (Mi)"
  type        = string
  default     = "2Gi"
}

variable "producer_service_cpu" {
  description = "Cloud Run CPU allocation for producer service"
  type        = string
  default     = "2"
}

variable "producer_service_concurrency" {
  description = "Max concurrent requests per Cloud Run instance"
  type        = number
  default     = 100
}

variable "consumer_service_memory" {
  description = "Cloud Run memory allocation for consumer service (Mi)"
  type        = string
  default     = "2Gi"
}

variable "consumer_service_cpu" {
  description = "Cloud Run CPU allocation for consumer service"
  type        = string
  default     = "2"
}

variable "consumer_service_concurrency" {
  description = "Max concurrent requests per Cloud Run instance"
  type        = number
  default     = 50
}

variable "producer_service_min_instances" {
  description = "Minimum Cloud Run instances for producer service"
  type        = number
  default     = 1
}

variable "producer_service_max_instances" {
  description = "Maximum Cloud Run instances for producer service"
  type        = number
  default     = 10
}

variable "consumer_service_min_instances" {
  description = "Minimum Cloud Run instances for consumer service"
  type        = number
  default     = 1
}

variable "consumer_service_max_instances" {
  description = "Maximum Cloud Run instances for consumer service"
  type        = number
  default     = 10
}

# Container Image Configuration
variable "producer_service_image" {
  description = "Container image URI for producer service"
  type        = string
  default     = ""  # Will be set via: -var="producer_service_image=gcr.io/..."
}

variable "consumer_service_image" {
  description = "Container image URI for consumer service"
  type        = string
  default     = ""
}

# DLQ Configuration
variable "dlq_max_delivery_attempts" {
  description = "Max delivery attempts before moving to DLQ"
  type        = number
  default     = 5
}

variable "dlq_ack_deadline_seconds" {
  description = "Pub/Sub ack deadline for DLQ messages (ms)"
  type        = number
  default     = 300  # High because replay takes time
}

# Monitoring
variable "alert_email" {
  description = "Email address for alert notifications"
  type        = string
  default     = ""
}

variable "enable_detailed_monitoring" {
  description = "Enable detailed monitoring (costs more)"
  type        = bool
  default     = true
}

# Tags for all resources
variable "labels" {
  description = "Labels to apply to all resources"
  type        = map(string)
  default = {
    managed_by = "terraform"
    team       = "platform"
  }
}
