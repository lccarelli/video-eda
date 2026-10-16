CREATE TABLE video (
  video_id               TEXT PRIMARY KEY,
  creator_id             TEXT NOT NULL,
  title                  TEXT NOT NULL,
  status                 TEXT NOT NULL,
  upload_idempotency_key TEXT UNIQUE,
  CONSTRAINT video_id_format    CHECK (video_id   ~ '^vid_[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT creator_id_format  CHECK (creator_id ~ '^usr_[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT video_title_not_blank CHECK (length(trim(title)) > 0),
  CONSTRAINT video_status_known CHECK (status IN ('UPLOADED', 'PROCESSING', 'PUBLISHED', 'FAILED', 'DELETED'))
);

CREATE TABLE outbox (
  outbox_seq     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  event_id       TEXT        NOT NULL UNIQUE,
  topic          TEXT        NOT NULL,
  aggregate_id   TEXT        NOT NULL,
  type           TEXT        NOT NULL,
  occurred_at    TIMESTAMPTZ NOT NULL,
  correlation_id TEXT        NOT NULL,
  sequence       INT         NOT NULL,
  data           JSONB       NOT NULL,
  published_at   TIMESTAMPTZ,
  CONSTRAINT outbox_event_id_format       CHECK (event_id       ~ '^evt_[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT outbox_correlation_id_format CHECK (correlation_id ~ '^cor_[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'),
  CONSTRAINT outbox_sequence_positive     CHECK (sequence >= 1),
  CONSTRAINT outbox_stream_sequence_unique UNIQUE (aggregate_id, topic, sequence)
);

CREATE INDEX outbox_unpublished ON outbox (outbox_seq) WHERE published_at IS NULL;
