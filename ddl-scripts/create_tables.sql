//https://github.com/akka/akka-persistence-jdbc/blob/v5.0.4/core/src/main/resources/schema/postgres/postgres-create-schema.sql

create DATABASE udefinitions

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

//https://doc.akka.io/libraries/akka-projection/current/slick.html#schema
















//https://github.com/apache/pekko-persistence-r2dbc/blob/main/ddl-scripts/create_tables_mysql.sql

CREATE TABLE IF NOT EXISTS event_journal(
                                            slice INT NOT NULL,
                                            entity_type VARCHAR(255) NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    seq_nr BIGINT NOT NULL,
    db_timestamp TIMESTAMP(6) NOT NULL,

    event_ser_id INTEGER NOT NULL,
    event_ser_manifest VARCHAR(255) NOT NULL,
    event_payload BLOB NOT NULL,

    deleted BOOLEAN DEFAULT FALSE NOT NULL,
    writer VARCHAR(255) NOT NULL,
    adapter_manifest VARCHAR(255),
    tags TEXT, -- FIXME no array type, is this the best option?

    meta_ser_id INTEGER,
    meta_ser_manifest VARCHAR(255),
    meta_payload BLOB,

    PRIMARY KEY(persistence_id, seq_nr)
    );

-- `event_journal_slice_idx` is only needed if the slice based queries are used
CREATE INDEX event_journal_slice_idx ON event_journal(slice, entity_type, db_timestamp, seq_nr);

CREATE TABLE IF NOT EXISTS snapshot(
                                       slice INT NOT NULL,
                                       entity_type VARCHAR(255) NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    seq_nr BIGINT NOT NULL,
    write_timestamp BIGINT NOT NULL,
    ser_id INTEGER NOT NULL,
    ser_manifest VARCHAR(255) NOT NULL,
    snapshot BLOB NOT NULL,
    meta_ser_id INTEGER,
    meta_ser_manifest VARCHAR(255),
    meta_payload BLOB,

    PRIMARY KEY(persistence_id)
    );

CREATE TABLE IF NOT EXISTS durable_state (
                                             slice INT NOT NULL,
                                             entity_type VARCHAR(255) NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    db_timestamp TIMESTAMP(6) NOT NULL,

    state_ser_id INTEGER NOT NULL,
    state_ser_manifest VARCHAR(255),
    state_payload BLOB NOT NULL,
    tags TEXT, -- FIXME no array type, is this the best option?

    PRIMARY KEY(persistence_id, revision)
    );

-- `durable_state_slice_idx` is only needed if the slice based queries are used
CREATE INDEX durable_state_slice_idx ON durable_state(slice, entity_type, db_timestamp, revision);

-- Primitive offset types are stored in this table.
-- If only timestamp based offsets are used this table is optional.
-- Configure pekko.projection.r2dbc.offset-store.offset-table="" if the table is not created.
CREATE TABLE IF NOT EXISTS projection_offset_store (
                                                       projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    current_offset VARCHAR(255) NOT NULL,
    manifest VARCHAR(32) NOT NULL,
    mergeable BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
    );

-- Timestamp based offsets are stored in this table.
CREATE TABLE IF NOT EXISTS projection_timestamp_offset_store (
                                                                 projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    slice INT NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    seq_nr BIGINT NOT NULL,
    -- timestamp_offset is the db_timestamp of the original event
    timestamp_offset TIMESTAMP(6) NOT NULL,
    -- timestamp_consumed is when the offset was stored
    -- the consumer lag is timestamp_consumed - timestamp_offset
    timestamp_consumed TIMESTAMP(6) NOT NULL,
    PRIMARY KEY(slice, projection_name, timestamp_offset, persistence_id, seq_nr)
    );

CREATE TABLE IF NOT EXISTS projection_management (
                                                     projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    paused BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
    );


https://github.com/apache/pekko-persistence-r2dbc/blob/main/ddl-scripts/drop_tables_mysql.sql









     https://github.com/apache/pekko-persistence-r2dbc/blob/main/ddl-scripts/create_tables_postgres.sql