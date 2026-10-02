# Backup-Speicher für Chattia in einem EIGENEN Google-Cloud-Projekt (getrennt vom Betriebsprojekt).
# Wer den Server oder das Betriebsprojekt übernimmt, kommt hier nicht heran.
#
#   terraform init && terraform apply -var project_id=chattia-backup -var server_service_account=…
#
# Aufbau (siehe docs/plan/backup.md):
#   primary  – restic-Repository, tägliche Sicherung. Server darf schreiben, aber nicht löschen.
#              Soft Delete 30 Tage: Auch gelöschte Objekte sind noch einen Monat wiederherstellbar.
#   archive  – Jahresarchive (age-verschlüsselt), Bucket Lock 10 Jahre: niemand kann löschen, auch kein Admin.

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
  type        = string
  description = "Eigenes Projekt nur für Backups"
}

variable "region" {
  type    = string
  default = "europe-west3" # Frankfurt
}

variable "server_service_account" {
  type        = string
  description = "Dienstkonto der VM (Betriebsprojekt), z. B. chattia-vm@chattia-prod.iam.gserviceaccount.com"
}

variable "archive_retention_years" {
  type    = number
  default = 10
}

variable "lock_archive_retention" {
  type        = bool
  default     = false
  description = "Erst nach einem Probelauf auf true setzen – eine gesperrte Frist lässt sich NIE mehr verkürzen."
}

provider "google" {
  project = var.project_id
  region  = var.region
}

# ---------- Primäres restic-Repository ----------

resource "google_storage_bucket" "primary" {
  name                        = "${var.project_id}-primary"
  location                    = var.region
  storage_class               = "STANDARD"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  soft_delete_policy {
    retention_duration_seconds = 30 * 24 * 3600
  }

  # restic verwaltet Versionen selbst; Objektversionierung würde Speicher doppelt belegen.
  versioning {
    enabled = false
  }
}

# ---------- Jahresarchiv (unveränderbar) ----------

resource "google_storage_bucket" "archive" {
  name                        = "${var.project_id}-archive"
  location                    = "EU" # mehrere Rechenzentren in der EU
  storage_class               = "ARCHIVE"
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"

  retention_policy {
    retention_period = var.archive_retention_years * 365 * 24 * 3600
    is_locked        = var.lock_archive_retention
  }

  soft_delete_policy {
    retention_duration_seconds = 90 * 24 * 3600
  }

  # Nach Ablauf der Frist automatisch löschen (Datensparsamkeit, DSGVO)
  lifecycle_rule {
    condition {
      age = var.archive_retention_years * 365 + 30
    }
    action {
      type = "Delete"
    }
  }
}

# ---------- Rechte ----------

# Server: lesen + neue Objekte anlegen. Kein Löschen, kein Überschreiben (dafür bräuchte er storage.objects.delete).
resource "google_storage_bucket_iam_member" "server_primary_create" {
  bucket = google_storage_bucket.primary.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${var.server_service_account}"
}

resource "google_storage_bucket_iam_member" "server_primary_read" {
  bucket = google_storage_bucket.primary.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${var.server_service_account}"
}

# Ausnahme: restic muss seine eigenen Sperrdateien wieder entfernen können – nur unter restic/locks/.
resource "google_storage_bucket_iam_member" "server_primary_locks" {
  bucket = google_storage_bucket.primary.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${var.server_service_account}"
  condition {
    title      = "nur-restic-locks"
    expression = "resource.name.startsWith(\"projects/_/buckets/${google_storage_bucket.primary.name}/objects/restic/locks/\")"
  }
}

resource "google_storage_bucket_iam_member" "server_archive_create" {
  bucket = google_storage_bucket.archive.name
  role   = "roles/storage.objectCreator"
  member = "serviceAccount:${var.server_service_account}"
}

# Wartungsjob: das einzige Konto, das Snapshots nach der Aufbewahrungsregel löschen darf (restic forget --prune).
resource "google_service_account" "maintenance" {
  account_id   = "backup-maintenance"
  display_name = "Backup-Wartung (Aufbewahrung + Prüfung)"
}

resource "google_storage_bucket_iam_member" "maintenance_primary" {
  bucket = google_storage_bucket.primary.name
  role   = "roles/storage.objectAdmin"
  member = "serviceAccount:${google_service_account.maintenance.email}"
}

# Restic-Passwörter im Secret Manager dieses Projekts (Inhalt wird manuell gesetzt, nicht im Terraform-State).
resource "google_secret_manager_secret" "restic_password" {
  secret_id = "restic-password"
  replication {
    auto {}
  }
}

resource "google_secret_manager_secret_iam_member" "maintenance_reads_password" {
  secret_id = google_secret_manager_secret.restic_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.maintenance.email}"
}

resource "google_secret_manager_secret_iam_member" "server_reads_password" {
  secret_id = google_secret_manager_secret.restic_password.id
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${var.server_service_account}"
}

output "restic_repository" {
  value = "gs:${google_storage_bucket.primary.name}:/restic"
}

output "archive_bucket" {
  value = google_storage_bucket.archive.name
}

output "maintenance_service_account" {
  value = google_service_account.maintenance.email
}
