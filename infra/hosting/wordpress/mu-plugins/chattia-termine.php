<?php
/**
 * Plugin Name: Chattia – Termine
 * Description: Zeigt die öffentlichen Termine des Semesterprogramms (Nextcloud-Kalender) auf der Website.
 *              Shortcode: [chattia_termine anzahl="6"]. Quelle: öffentlicher Abo-Link in CHATTIA_EVENTS_ICS.
 *              Gepflegt wird nur der Kalender in der Cloud – die Website zieht stündlich nach.
 */

defined('ABSPATH') || exit;

const CHATTIA_EVENTS_CACHE = 'chattia_termine_ics';

/** Lädt den Kalender (1 h Cache, bei Fehlern gilt der letzte gute Stand weiter). */
function chattia_events_ics(): ?string {
    $cached = get_transient(CHATTIA_EVENTS_CACHE);
    if ($cached !== false) {
        return $cached;
    }
    $url = getenv('CHATTIA_EVENTS_ICS');
    if (!$url) {
        return null;
    }
    $response = wp_remote_get($url, ['timeout' => 8]);
    if (is_wp_error($response) || wp_remote_retrieve_response_code($response) !== 200) {
        return get_option(CHATTIA_EVENTS_CACHE . '_last_good') ?: null;
    }
    $body = wp_remote_retrieve_body($response);
    set_transient(CHATTIA_EVENTS_CACHE, $body, HOUR_IN_SECONDS);
    update_option(CHATTIA_EVENTS_CACHE . '_last_good', $body, false);
    return $body;
}

/** Minimaler ICS-Leser: nur das, was die Website braucht (Beginn, Titel, Ort, Sichtbarkeit). */
function chattia_parse_events(string $ics): array {
    $ics = preg_replace("/\r?\n[ \t]/", '', $ics); // gefaltete Zeilen zusammenfügen
    preg_match_all('/BEGIN:VEVENT(.*?)END:VEVENT/s', $ics, $blocks);
    $events = [];
    foreach ($blocks[1] as $block) {
        $props = [];
        foreach (preg_split("/\r?\n/", trim($block)) as $line) {
            if (!preg_match('/^([A-Z-]+)((?:;[^:]*)?):(.*)$/', $line, $m)) {
                continue;
            }
            $props[$m[1]] = ['params' => $m[2], 'value' => stripcslashes($m[3])];
        }
        // Nur ausdrücklich öffentliche Termine (Convente, Interna u. ä. bleiben in der Cloud)
        $class = strtoupper($props['CLASS']['value'] ?? 'PUBLIC');
        if ($class !== 'PUBLIC' || empty($props['DTSTART']) || empty($props['SUMMARY'])) {
            continue;
        }
        $start = chattia_parse_ics_date($props['DTSTART']['value'], $props['DTSTART']['params']);
        if ($start === null) {
            continue;
        }
        $events[] = [
            'start'  => $start,
            'allDay' => strlen($props['DTSTART']['value']) === 8,
            'title'  => $props['SUMMARY']['value'],
            'where'  => $props['LOCATION']['value'] ?? '',
        ];
    }
    usort($events, fn($a, $b) => $a['start'] <=> $b['start']);
    return $events;
}

function chattia_parse_ics_date(string $value, string $params): ?int {
    try {
        if (strlen($value) === 8) {
            return (new DateTimeImmutable($value, new DateTimeZone('Europe/Berlin')))->getTimestamp();
        }
        $tz = preg_match('/TZID=([^;:]+)/', $params, $m) ? new DateTimeZone($m[1]) : new DateTimeZone('Europe/Berlin');
        if (str_ends_with($value, 'Z')) {
            $tz = new DateTimeZone('UTC');
        }
        return (new DateTimeImmutable(rtrim($value, 'Z'), $tz))->getTimestamp();
    } catch (Exception $e) {
        return null;
    }
}

/** Deutsches Datum unabhängig vom installierten Sprachpaket, z. B. „Samstag, 28. November 2026, 18:00 Uhr“. */
function chattia_date_de(int $ts, bool $allDay): string {
    $days = ['Sonntag', 'Montag', 'Dienstag', 'Mittwoch', 'Donnerstag', 'Freitag', 'Samstag'];
    $months = ['Januar', 'Februar', 'März', 'April', 'Mai', 'Juni', 'Juli', 'August', 'September', 'Oktober', 'November', 'Dezember'];
    $d = (new DateTimeImmutable('@' . $ts))->setTimezone(new DateTimeZone('Europe/Berlin'));
    $text = $days[(int) $d->format('w')] . ', ' . $d->format('j') . '. ' . $months[(int) $d->format('n') - 1] . ' ' . $d->format('Y');
    return $allDay ? $text : $text . ', ' . $d->format('G:i') . ' Uhr';
}

add_shortcode('chattia_termine', function ($atts) {
    $atts = shortcode_atts(['anzahl' => 6], $atts);
    $ics = chattia_events_ics();
    if ($ics === null) {
        return '<p class="chattia-termine-leer">Das Semesterprogramm folgt in Kürze.</p>';
    }
    $today = strtotime('today', current_time('timestamp', true));
    $upcoming = array_slice(array_filter(chattia_parse_events($ics), fn($e) => $e['start'] >= $today), 0, (int) $atts['anzahl']);
    if (!$upcoming) {
        return '<p class="chattia-termine-leer">Derzeit stehen keine öffentlichen Termine an.</p>';
    }
    $html = '<ul class="chattia-termine">';
    foreach ($upcoming as $e) {
        $date = chattia_date_de($e['start'], $e['allDay']);
        $html .= sprintf(
            '<li><strong>%s</strong><br><span>%s%s</span></li>',
            esc_html($e['title']),
            esc_html($date),
            $e['where'] !== '' ? ' · ' . esc_html($e['where']) : ''
        );
    }
    return $html . '</ul>';
});
