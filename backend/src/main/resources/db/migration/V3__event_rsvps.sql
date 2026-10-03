-- Zu-/Absagen zu Terminen aus den Kalendern des Corps (Termin-ID = UID aus dem Kalender, bei Serien mit #RECURRENCE-ID)
CREATE TABLE event_rsvps (
    event_id   TEXT        NOT NULL,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    status     TEXT        NOT NULL CHECK (status IN ('yes', 'no', 'maybe')),
    guests     INT         NOT NULL DEFAULT 0 CHECK (guests BETWEEN 0 AND 10),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, user_id)
);
CREATE INDEX event_rsvps_event ON event_rsvps (event_id);
