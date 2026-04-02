# Redis Memorystore instance for deduplication cache
resource "google_redis_instance" "cache" {
  name            = "event-processor-cache"
  tier            = var.redis_tier  # "BASIC" or "STANDARD"
  memory_size_gb  = var.redis_memory_gb
  region          = var.region
  redis_version   = "7.2"
  display_name    = "Event Processor Dedup Cache"

  # For HA deployment, use STANDARD tier with replica_count
  replica_count = var.redis_tier == "STANDARD" ? 1 : 0

  # Dedup entries expire after 5 minutes
  redis_configs = {
    "maxmemory-policy" = "allkeys-lru"  # Evict oldest accessed keys when full
    "timeout"          = "300"           # Close idle connections after 5 min
  }

  auth_enabled = true

  labels = {
    environment = var.environment
    app         = "event-processor"
  }

  depends_on = [google_service_account.event_processor]
}

# VPC peering to allow Cloud Run/Dataflow to reach Redis
resource "google_compute_network" "default" {
  name = "event-processor-network"
}

resource "google_compute_global_address" "redis_address" {
  name          = "redis-address"
  purpose       = "VPC_PEERING"
  address_type  = "INTERNAL"
  prefix_length = 16
  network       = google_compute_network.default.id
}

resource "google_service_networking_connection" "redis_connection" {
  network                 = google_compute_network.default.id
  service                 = "servicenetworking.googleapis.com"
  reserved_peering_ranges = [google_compute_global_address.redis_address.name]
}

# Firewall rule to allow Cloud Run/Dataflow to Redis
resource "google_compute_firewall" "allow_redis" {
  name    = "allow-event-processor-to-redis"
  network = google_compute_network.default.name

  allow {
    protocol = "tcp"
    ports    = ["6379"]
  }

  source_ranges = ["0.0.0.0/0"]  # Restrict in production
  target_tags   = ["event-processor"]
}

# Outputs
output "redis_host" {
  value = google_redis_instance.cache.host
}

output "redis_port" {
  value = google_redis_instance.cache.port
}

output "redis_auth_string" {
  value     = google_redis_instance.cache.auth_string
  sensitive = true
}

output "redis_connection_string" {
  value     = "redis://:${google_redis_instance.cache.auth_string}@${google_redis_instance.cache.host}:${google_redis_instance.cache.port}"
  sensitive = true
}
