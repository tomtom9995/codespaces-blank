<?php
/**
 * Plugin Name: Chattia – zentraler Login
 * Description: Konfiguriert „OpenID Connect Generic“ für den zentralen Login (Keycloak) und ordnet WordPress-Rollen
 *              anhand der Keycloak-Gruppen zu. Nur Website-Redaktion und IT-Admins dürfen sich anmelden.
 */

defined('ABSPATH') || exit;

const CHATTIA_ROLE_MAP = [
    'it-admins'         => 'administrator',
    'website-redaktion' => 'editor',
];

/** Einstellungen kommen aus der Umgebung, nicht aus der Datenbank (nachvollziehbar, versioniert). */
add_filter('pre_option_openid_connect_generic_settings', function () {
    $issuer = rtrim(getenv('CHATTIA_AUTH_URL') ?: '', '/') . '/realms/chattia';
    $auth = $issuer . '/protocol/openid-connect';
    return [
        'login_type'               => 'button',
        'login_button_text'        => 'Mit Chattia-Konto anmelden',
        'client_id'                => 'wordpress',
        'client_secret'            => getenv('CHATTIA_OIDC_SECRET') ?: '',
        'scope'                    => 'openid email profile',
        'endpoint_login'           => $auth . '/auth',
        'endpoint_userinfo'        => $auth . '/userinfo',
        'endpoint_token'           => $auth . '/token',
        'endpoint_end_session'     => $auth . '/logout',
        'endpoint_jwks'            => $auth . '/certs',
        'issuer'                   => $issuer,
        'jwks_cache_ttl'           => 3600,
        'acr_values'               => '',
        'no_sslverify'             => 0,
        'http_request_timeout'     => 10,
        // Lokal (Docker) liegt Keycloak in einem privaten Netz; in Produktion auf 0 lassen.
        'allow_internal_idp'       => getenv('CHATTIA_ALLOW_INTERNAL_IDP') ? 1 : 0,
        'identity_key'             => 'preferred_username',
        'nickname_key'             => 'preferred_username',
        'email_format'             => '{email}',
        'displayname_format'       => '{given_name} {family_name}',
        'identify_with_username'   => true,
        'state_time_limit'         => 300,
        'enforce_privacy'          => 0,
        'alternate_redirect_uri'   => 0,
        'token_refresh_enable'     => 1,
        'link_existing_users'      => 1,
        'create_if_does_not_exist' => 1,
        'redirect_user_back'       => 1,
        'redirect_on_logout'       => 1,
        'enable_logging'           => 0,
        'log_limit'                => 1000,
    ];
});

function chattia_groups(array $claim): array {
    $groups = $claim['groups'] ?? [];
    return is_array($groups) ? $groups : array_filter(array_map('trim', explode(',', (string) $groups)));
}

function chattia_role_for(array $claim): ?string {
    foreach (CHATTIA_ROLE_MAP as $group => $role) {
        if (in_array($group, chattia_groups($claim), true)) {
            return $role;
        }
    }
    return null;
}

/** Nur berechtigte Gruppen dürfen sich anmelden bzw. ein Konto bekommen. */
add_filter('openid-connect-generic-user-login-test', fn($ok, $claim) => $ok && chattia_role_for((array) $claim) !== null, 10, 2);
add_filter('openid-connect-generic-user-creation-test', fn($ok, $claim) => $ok && chattia_role_for((array) $claim) !== null, 10, 2);

/** Rolle bei jeder Anmeldung aus Keycloak übernehmen (entzogene Rechte wirken sofort). */
add_action('openid-connect-generic-update-user-using-current-claim', function ($user, $claim) {
    $role = chattia_role_for((array) $claim);
    if ($role && $user instanceof WP_User) {
        $user->set_role($role);
    }
}, 10, 2);
add_action('openid-connect-generic-user-create', function ($user, $claim) {
    $role = chattia_role_for((array) $claim);
    if ($role && $user instanceof WP_User) {
        $user->set_role($role);
    }
}, 10, 2);

/** XML-RPC wird für die Visitenkarten-Website nicht gebraucht und ist ein beliebtes Angriffsziel. */
add_filter('xmlrpc_enabled', '__return_false');
