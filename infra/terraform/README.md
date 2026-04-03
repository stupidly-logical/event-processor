# Terraform Infrastructure as Code

Complete infrastructure definition for production deployment of the Event Processor to Google Cloud Platform.

## Files

- **main.tf** — Provider configuration and service account setup
- **pubsub.tf** — Pub/Sub topics and subscriptions (events, DLQ, billing, audit, analytics)
- **redis.tf** — Redis Memorystore instance with VPC peering
- **cloud_run.tf** — Cloud Run service deployments for producer and consumer
- **monitoring.tf** — Uptime checks, alert policies, and monitoring dashboard
- **variables.tf** — Input variables (region, resource sizes, instance counts, etc.)
- **outputs.tf** — Output values (service URLs, resource IDs, connection strings)

## Quick Start

### Prerequisites

```bash
# Install Terraform 1.5+
terraform --version

# Authenticate with GCP
gcloud auth login
gcloud config set project YOUR_PROJECT_ID

# Enable required APIs
gcloud services enable \
  pubsub.googleapis.com \
  redis.googleapis.com \
  run.googleapis.com \
  monitoring.googleapis.com \
  servicenetworking.googleapis.com
```

### Deploy Infrastructure

```bash
# From infra/terraform/
cd infra/terraform

# Initialize Terraform
terraform init

# Plan deployment
terraform plan \
  -var="project_id=YOUR_PROJECT_ID" \
  -var="region=us-central1" \
  -var="redis_memory_gb=4" \
  -var="alert_email=your-email@example.com"

# Apply changes
terraform apply \
  -var="project_id=YOUR_PROJECT_ID" \
  -var="region=us-central1" \
  -var="redis_memory_gb=4" \
  -var="alert_email=your-email@example.com"

# Capture outputs
terraform output
```

### Deploy Service Images

After infrastructure is ready, build and push container images:

```bash
# From project root
export PROJECT_ID=YOUR_PROJECT_ID
export REGION=us-central1

# Build images
mvn clean package -Pprod

# Push to Artifact Registry
docker build -t $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/producer:latest \
  -f producer-service/Dockerfile producer-service/
docker build -t $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/consumer:latest \
  -f consumer-service/Dockerfile consumer-service/

docker push $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/producer:latest
docker push $REGION-docker.pkg.dev/$PROJECT_ID/event-processor/consumer:latest

# Update Cloud Run services
terraform apply \
  -var="project_id=$PROJECT_ID" \
  -var="producer_service_image=$REGION-docker.pkg.dev/$PROJECT_ID/event-processor/producer:latest" \
  -var="consumer_service_image=$REGION-docker.pkg.dev/$PROJECT_ID/event-processor/consumer:latest"
```

## Configuration Options

### Redis Sizing

```bash
# Development (Basic tier, 1GB)
terraform apply -var="redis_tier=BASIC" -var="redis_memory_gb=1"

# Production (Standard tier with HA, 4GB)
terraform apply -var="redis_tier=STANDARD" -var="redis_memory_gb=4"

# High volume production (32GB)
terraform apply -var="redis_tier=STANDARD" -var="redis_memory_gb=32"
```

### Autoscaling

```bash
# Adjust Cloud Run instance counts
terraform apply \
  -var="producer_service_min_instances=3" \
  -var="producer_service_max_instances=20" \
  -var="consumer_service_min_instances=2" \
  -var="consumer_service_max_instances=10"
```

### Memory & CPU

```bash
# Scale up producer service
terraform apply \
  -var="producer_service_memory=4Gi" \
  -var="producer_service_cpu=4" \
  -var="producer_service_concurrency=200"
```

## Monitoring & Alerting

### View Dashboard

```bash
# Get dashboard URL
terraform output dashboard_url

# Or navigate manually:
# https://console.cloud.google.com/monitoring/dashboards
```

### Alert Policies

Alert policies are automatically created when you provide an alert email:

```bash
terraform apply -var="alert_email=your-email@example.com"
```

Alerts include:
- Producer/Consumer service uptime checks (every 60s)
- DLQ depth > 1000 (trigger after 5 minutes)
- Circuit breaker state = OPEN
- Redis memory > 80%
- Publish latency P95 > 5s

## Troubleshooting

### Terraform State

#### Remote State (Recommended for teams)

```bash
# Create GCS bucket for state
gsutil mb gs://$PROJECT_ID-tfstate

# Enable versioning
gsutil versioning set on gs://$PROJECT_ID-tfstate

# Configure backend in main.tf:
terraform {
  backend "gcs" {
    bucket = "YOUR_PROJECT_ID-tfstate"
    prefix = "terraform/event-processor"
  }
}

# Re-initialize
terraform init
```

#### Local State

State stored in `terraform.tfstate` (default). Commit to version control with git-crypt or similar.

### Common Errors

**Error: "Permission denied" when creating resources**

```bash
# Check IAM permissions
gcloud projects get-iam-policy $PROJECT_ID --flatten="bindings[].members" --filter="bindings.members:serviceAccount:*"

# Grant required roles to your user
gcloud projects add-iam-policy-binding $PROJECT_ID \
  --member=user:YOUR_EMAIL \
  --role=roles/editor
```

**Error: "Redis Region is not available"**

```bash
# Check available regions
gcloud redis regions list

# Use a different region
terraform apply -var="region=us-west1"
```

**Error: "Pub/Sub topic already exists"**

```bash
# Import existing topic into state
terraform import google_pubsub_topic.events projects/$PROJECT_ID/topics/events
```

### Destroy Infrastructure

⚠️ **Warning:** This is destructive and cannot be undone.

```bash
terraform destroy \
  -var="project_id=$PROJECT_ID" \
  -var="region=$REGION"
```

## Cost Optimization

### Development Environment

```hcl
# Use cheaper options for non-prod
terraform apply \
  -var="redis_tier=BASIC" \
  -var="redis_memory_gb=1" \
  -var="producer_service_min_instances=1" \
  -var="producer_service_max_instances=3" \
  -var="consumer_service_min_instances=1" \
  -var="consumer_service_max_instances=2" \
  -var="enable_detailed_monitoring=false"
```

### Estimated Monthly Costs

| Component | Dev | Prod |
|-----------|-----|------|
| Redis | $37 (1GB Basic) | $180 (4GB Standard) |
| Cloud Run (1M requests) | $20 | $20 |
| Pub/Sub (10GB ingress) | $5 | $50 |
| Monitoring | $0 | $10 (custom metrics) |
| **Total** | ~$65 | ~$260 |

## Upgrades & Changes

### Update Redis Size

```bash
# Current: 4GB, upgrade to 8GB
terraform apply -var="redis_memory_gb=8"
```

### Update Service Images

```bash
# New image built with tag v2.0.0
terraform apply \
  -var="producer_service_image=gcr.io/$PROJECT_ID/event-processor/producer:v2.0.0" \
  -var="consumer_service_image=gcr.io/$PROJECT_ID/event-processor/consumer:v2.0.0"
```

### Add Slack Notifications

Currently only email is configured. To add Slack:

1. Create Slack app webhook: https://api.slack.com/messaging/webhooks
2. Add custom notification channel to monitoring.tf:

```hcl
resource "google_monitoring_notification_channel" "slack" {
  display_name = "Slack"
  type         = "slack"
  labels = {
    channel_name = "#alerts"
  }
  user_labels = {
    slack_url = var.slack_webhook_url  # Pass via -var
  }
}
```

## Security

### Service Account Permissions

Only necessary IAM roles are granted:
- Pub/Sub Publisher & Subscriber
- Dataflow Admin & Worker
- Cloud Logging & Monitoring
- Redis Admin (restricted to service account)

### Network Security

- Redis protected by VPC peering
- Firewall allows only necessary traffic
- Service accounts have minimal required permissions
- Secrets stored in Cloud Secret Manager

### Secrets Management

Redis auth string is stored in Cloud Secret Manager and injected at runtime.

To rotate Redis password:

```bash
# Update Redis instance password
gcloud redis instances update EVENT_PROCESSOR_CACHE --auth-enabled

# Get new password
gcloud redis instances describe EVENT_PROCESSOR_CACHE --format="value(authString)"

# Update Secret Manager
gcloud secrets versions add event-processor-redis-auth --data-file=-
```

## Advanced Topics

### Custom Networking

To restrict Cloud Run traffic:

```hcl
# Add to variables.tf
variable "allow_cidr_ranges" {
  description = "CIDR ranges allowed to call services"
  type        = list(string)
  default     = ["0.0.0.0/0"]  # Change to restrict
}

# Update firewall in cloud_run.tf
resource "google_compute_firewall" "allow_cloud_run" {
  source_ranges = var.allow_cidr_ranges
  ...
}
```

### Multi-Region Deployment

Extend Terraform for multi-region:

```bash
# Create separate workspaces per region
terraform workspace new us-west1
terraform workspace new eu-west1

# Deploy to each region
for region in us-central1 us-west1 eu-west1; do
  terraform workspace select $region
  terraform apply -var="region=$region"
done
```

## Maintenance

### Regular Backups

Pub/Sub topics automatically retain messages (configurable in pubsub.tf).

Redis backups are automatic in Standard tier. For Basic tier, use:

```bash
gcloud redis instances export event-processor-cache \
  gs://$PROJECT_ID-redis-backups/backup.rdb
```

### Monitoring Cloud Run Logs

```bash
gcloud logging read "resource.type=cloud_run_revision AND jsonPayload.service=event-processor-producer" \
  --project=$PROJECT_ID \
  --format=json \
  --limit=100
```

---

**Documentation Last Updated:** 2024-01-02
