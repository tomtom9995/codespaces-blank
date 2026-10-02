<?php
// Wird von Nextcloud zusätzlich zu config.php geladen (alle *.config.php im config-Ordner).
$CONFIG = array (
  'memcache.local' => '\OC\Memcache\APCu',
  'memcache.locking' => '\OC\Memcache\Redis',
  'memcache.distributed' => '\OC\Memcache\Redis',
  // Bestehende Konten (z. B. aus der alten Instanz) werden beim ersten SSO-Login übernommen,
  // wenn der Keycloak-Benutzername dem bisherigen Nextcloud-Benutzernamen entspricht.
  'user_oidc' => array (
    'auto_provision' => true,
    'soft_auto_provision' => true,
    'single_logout' => true,
  ),
  // Papierkorb und Versionen: mindestens 30 Tage, spätestens nach 90 Tagen automatisch aufräumen.
  'trashbin_retention_obligation' => '30, 90',
  'versions_retention_obligation' => '30, 365',
  'skeletondirectory' => '',
  'activity_expire_days' => 365,
  'log_type' => 'file',
  'loglevel' => 2,
);
