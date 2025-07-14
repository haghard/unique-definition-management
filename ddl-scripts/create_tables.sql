#https://github.com/akka/akka-persistence-jdbc/blob/v5.0.4/core/src/main/resources/schema/postgres/postgres-create-schema.sql

CREATE TABLE IF NOT EXISTS event_journal(
    ordering SERIAL PRIMARY KEY,
    deleted BOOLEAN DEFAULT false NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    writer TEXT NOT NULL,
    write_timestamp BIGINT NOT NULL,
    adapter_manifest TEXT NOT NULL,
    event_payload BLOB NOT NULL,
    event_ser_id INTEGER NOT NULL,
    event_ser_manifest TEXT NOT NULL,
    meta_payload BLOB,
    meta_ser_id INTEGER,meta_ser_manifest TEXT,
    UNIQUE INDEX(persistence_id,sequence_number)
);

CREATE TABLE IF NOT EXISTS event_tag (
    event_id BIGINT UNSIGNED NOT NULL,
    tag VARCHAR(255) NOT NULL,
    PRIMARY KEY(tag, event_id),
    FOREIGN KEY (event_id)
    REFERENCES event_journal(ordering)
    ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS snapshot (
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    created BIGINT NOT NULL,
    snapshot_ser_id INTEGER NOT NULL,
    snapshot_ser_manifest TEXT NOT NULL,
    snapshot_payload BLOB NOT NULL,
    meta_ser_id INTEGER,
    meta_ser_manifest TEXT,
    meta_payload BLOB,
    PRIMARY KEY (persistence_id, sequence_number)
);


CREATE TABLE IF NOT EXISTS akka_projection_offset_store (
    projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    current_offset VARCHAR(255) NOT NULL,
    manifest VARCHAR(4) NOT NULL,
    mergeable BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
);

CREATE INDEX akka_projection_name_index ON akka_projection_offset_store (projection_name);

CREATE TABLE IF NOT EXISTS akka_projection_management (
    projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    paused BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
);