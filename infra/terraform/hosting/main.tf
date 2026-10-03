# Betriebsumgebung für Keycloak, Nextcloud, WordPress (infra/hosting) auf einer VM in Frankfurt.
# Siehe docs/plan/nextcloud-wordpress.md („Zielbild“).
#
#   terraform init && terraform apply -var project_id=chattia-prod -var backup_project_id=chattia-backup
#
# Danach: DNS (auth., cloud., api., Apex) auf die Ausgabe "ip" zeigen lassen, .env per Secret Manager befüllen.

terraform {
  required_version = ">= 1.6"
  required_providers {
    google = {
      source  = "hashicorp/google"
      version = ">= 6.0"
    }
  }
}

variable "project_id" {
  type = string
}

variable "region" {
  type    = string
  default = "europe-west3"
}

variable "zone" {
  type    = string
  default = "europe-west3-a"
}

variable "machine_type" {
  type    = string
  default = "e2-standard-2" # 2 vCPU, 8 GB – reicht für ~300 Mitglieder
}

variable "data_disk_gb" {
  type    = number
  default = 300
}

variable "repo_url" {
  type        = string
  description = "Git-Repository mit infra/hosting (wird auf der VM ausgecheckt)"
}

variable "repo_ref" {
  type    = string
  default = "main"
}

variable "admin_members" {
  type        = list(string)
  default     = []
  description = "Wer per IAP-SSH auf die VM darf, z. B. [\"user:it@chattia.de\"]"
}

provider "google" {
  project = var.project_id
  region  = var.region
  zone    = var.zone
}

# ---------- Netz ----------

resource "google_compute_address" "web" {
  name = "chattia-web"
}

resource "google_compute_firewall" "web" {
  name          = "chattia-allow-web"
  network       = "default"
  direction     = "INGRESS"
  source_ranges = ["0.0.0.0/0"]
  target_tags   = ["chattia-web"]
  allow {
    protocol = "tcp"
    ports    = ["80", "443"]
  }
}

# SSH nur über Identity-Aware Proxy (kein offener Port 22 im Internet)
resource "google_compute_firewall" "iap_ssh" {
  name          = "chattia-allow-iap-ssh"
  network       = "default"
  direction     = "INGRESS"
  source_ranges = ["35.235.240.0/20"]
  target_tags   = ["chattia-web"]
  allow {
    protocol = "tcp"
    ports    = ["22"]
  }
}

resource "google_project_iam_member" "iap_users" {
  for_each = toset(var.admin_members)
  project  = var.project_id
  role     = "roles/iap.tunnelResourceAccessor"
  member   = each.value
}

resource "google_project_iam_member" "os_login" {
  for_each = toset(var.admin_members)
  project  = var.project_id
  role     = "roles/compute.osAdminLogin"
  member   = each.value
}

# ---------- Dienstkonto der VM ----------
# Darf Geheimnisse lesen und Logs schreiben. Rechte am Backup-Bucket vergibt infra/terraform/backup (nur anlegen/lesen).

resource "google_service_account" "vm" {
  account_id   = "chattia-vm"
  display_name = "Chattia VM (Keycloak, Nextcloud, WordPress)"
}

resource "google_project_iam_member" "vm_roles" {
  for_each = toset(["roles/secretmanager.secretAccessor", "roles/logging.logWriter", "roles/monitoring.metricWriter"])
  project  = var.project_id
  role     = each.value
  member   = "serviceAccount:${google_service_account.vm.email}"
}

# ---------- Speicher ----------

resource "google_compute_disk" "data" {
  name = "chattia-data"
  type = "pd-balanced"
  size = var.data_disk_gb
  lifecycle {
    prevent_destroy = true # Nutzerdaten – nie versehentlich per terraform destroy löschen
  }
}

resource "google_compute_resource_policy" "daily_snapshots" {
  name = "chattia-daily-snapshots"
  snapshot_schedule_policy {
    schedule {
      daily_schedule {
        days_in_cycle = 1
        start_time    = "01:00" # UTC, vor dem restic-Backup
      }
    }
    retention_policy {
      max_retention_days    = 14
      on_source_disk_delete = "KEEP_AUTO_SNAPSHOTS"
    }
    snapshot_properties {
      storage_locations = [var.region]
      labels            = { app = "chattia" }
    }
  }
}

resource "google_compute_disk_resource_policy_attachment" "data_snapshots" {
  name = google_compute_resource_policy.daily_snapshots.name
  disk = google_compute_disk.data.name
}

# ---------- VM ----------

resource "google_compute_instance" "web" {
  name         = "chattia-web"
  machine_type = var.machine_type
  tags         = ["chattia-web"]

  boot_disk {
    initialize_params {
      image = "debian-cloud/debian-13"
      size  = 20
      type  = "pd-balanced"
    }
  }

  attached_disk {
    source      = google_compute_disk.data.id
    device_name = "chattia-data"
  }

  network_interface {
    network = "default"
    access_config {
      nat_ip = google_compute_address.web.address
    }
  }

  service_account {
    email  = google_service_account.vm.email
    scopes = ["cloud-platform"]
  }

  shielded_instance_config {
    enable_secure_boot          = true
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }

  metadata = {
    enable-oslogin = "TRUE"
    startup-script = templatefile("${path.module}/startup.sh", { repo_url = var.repo_url, repo_ref = var.repo_ref, project_id = var.project_id })
  }

  allow_stopping_for_update = true
}

output "ip" {
  value = google_compute_address.web.address
}

output "vm_service_account" {
  description = "Für infra/terraform/backup (Variable server_service_account)"
  value       = google_service_account.vm.email
}

output "ssh" {
  value = "gcloud compute ssh chattia-web --zone ${var.zone} --tunnel-through-iap"
}
