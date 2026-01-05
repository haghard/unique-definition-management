CREATE TABLE IF NOT EXISTS event_journal(
    slice INT NOT NULL,
    entity_type VARCHAR(255) NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    seq_nr BIGINT NOT NULL,
    db_timestamp timestamp with time zone NOT NULL,
    event_ser_id INTEGER NOT NULL,
    event_ser_manifest VARCHAR(255) NOT NULL,
    event_payload BYTEA NOT NULL,
    deleted BOOLEAN DEFAULT FALSE NOT NULL,
    writer VARCHAR(255) NOT NULL,
    adapter_manifest VARCHAR(255),
    tags TEXT ARRAY,
    meta_ser_id INTEGER,
    meta_ser_manifest VARCHAR(255),
    meta_payload BYTEA,
    PRIMARY KEY(persistence_id, seq_nr)
);

CREATE INDEX IF NOT EXISTS event_journal_slice_idx ON event_journal(slice, entity_type, db_timestamp, seq_nr);

CREATE TABLE IF NOT EXISTS snapshot(
    slice INT NOT NULL,
    entity_type VARCHAR(255) NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    seq_nr BIGINT NOT NULL,
    db_timestamp timestamp with time zone,
    write_timestamp BIGINT NOT NULL,
    ser_id INTEGER NOT NULL,
    ser_manifest VARCHAR(255) NOT NULL,
    snapshot BYTEA NOT NULL,
    tags TEXT ARRAY,
    meta_ser_id INTEGER,
    meta_ser_manifest VARCHAR(255),
    meta_payload BYTEA,
    PRIMARY KEY(persistence_id)
);

-- `snapshot_slice_idx` is only needed if the slice based queries are used together with snapshot as starting point
CREATE INDEX IF NOT EXISTS snapshot_slice_idx ON snapshot(slice, entity_type, db_timestamp);

CREATE TABLE IF NOT EXISTS durable_state (
    slice INT NOT NULL,
    entity_type VARCHAR(255) NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    db_timestamp timestamp with time zone NOT NULL,
    state_ser_id INTEGER NOT NULL,
    state_ser_manifest VARCHAR(255),
    state_payload BYTEA NOT NULL,
    tags TEXT ARRAY,
    PRIMARY KEY(persistence_id)
);

-- `durable_state_slice_idx` is only needed if the slice based queries are used
CREATE INDEX IF NOT EXISTS durable_state_slice_idx ON durable_state(slice, entity_type, db_timestamp, revision);

-- Primitive offset types are stored in this table.
-- If only timestamp based offsets are used this table is optional.
-- Configure akka.projection.r2dbc.offset-store.offset-table="" if the table is not created.
CREATE TABLE IF NOT EXISTS akka_projection_offset_store (
    projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    current_offset VARCHAR(255) NOT NULL,
    manifest VARCHAR(32) NOT NULL,
    mergeable BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
);

-- Timestamp based offsets are stored in this table.
CREATE TABLE IF NOT EXISTS akka_projection_timestamp_offset_store (
    projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    slice INT NOT NULL,
    persistence_id VARCHAR(255) NOT NULL,
    seq_nr BIGINT NOT NULL,
    -- timestamp_offset is the db_timestamp of the original event
    timestamp_offset timestamp with time zone NOT NULL,
    -- timestamp_consumed is when the offset was stored
    -- the consumer lag is timestamp_consumed - timestamp_offset
    timestamp_consumed timestamp with time zone NOT NULL,
    PRIMARY KEY(slice, projection_name, timestamp_offset, persistence_id, seq_nr)
);

CREATE TABLE IF NOT EXISTS akka_projection_management (
    projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    paused BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
);

create table pending_requests (owner_id UUID NOT NULL, request BYTEA NOT NULL, tag INTEGER NOT NULL, ts BIGINT NOT NULL);
alter table pending_requests add constraint pending_request__pk_owner_id primary key(owner_id);

create table definitions0 (name VARCHAR(400) NOT NULL, definition BYTEA NOT NULL, owner_id UUID NOT NULL, hash_bucket_id BIGINT NOT NULL, seq_num BIGINT NOT NULL,time BIGINT NOT NULL);
alter table definitions0 add constraint definitions0__pk primary key(hash_bucket_id,seq_num);

create table definitions1 (name VARCHAR(400) NOT NULL, definition BYTEA NOT NULL, owner_id UUID NOT NULL, hash_bucket_id BIGINT NOT NULL, seq_num BIGINT NOT NULL,time BIGINT NOT NULL);
alter table definitions1 add constraint definitions1__pk primary key(hash_bucket_id,seq_num)

create table definitions2 (name VARCHAR(400) NOT NULL, definition BYTEA NOT NULL, owner_id UUID NOT NULL, hash_bucket_id BIGINT NOT NULL, seq_num BIGINT NOT NULL,time BIGINT NOT NULL);
alter table definitions2 add constraint definitions2__pk primary key(hash_bucket_id,seq_num)

create table definitions3 (name VARCHAR(400) NOT NULL, definition BYTEA NOT NULL, owner_id UUID NOT NULL, hash_bucket_id BIGINT NOT NULL, seq_num BIGINT NOT NULL,time BIGINT NOT NULL);
alter table definitions3 add constraint definitions3__pk primary key(hash_bucket_id,seq_num)


    

TRUNCATE TABLE akka_projection_management;
TRUNCATE TABLE akka_projection_offset_store;
TRUNCATE TABLE akka_projection_timestamp_offset_store;
TRUNCATE TABLE event_journal;
TRUNCATE TABLE definitions0;
TRUNCATE TABLE definitions1;
TRUNCATE TABLE definitions2;
TRUNCATE TABLE definitions3;
TRUNCATE TABLE pending_requests;














