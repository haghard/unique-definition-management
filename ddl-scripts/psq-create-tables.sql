//https://github.com/akka/akka-persistence-jdbc/blob/v5.0.4/core/src/main/resources/schema/postgres/postgres-create-schema.sql


CREATE TABLE IF NOT EXISTS public.event_journal(
    ordering BIGSERIAL,
    persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    deleted BOOLEAN DEFAULT FALSE NOT NULL,

    writer VARCHAR(255) NOT NULL,
    write_timestamp BIGINT,
    adapter_manifest VARCHAR(255),

    event_ser_id INTEGER NOT NULL,
    event_ser_manifest VARCHAR(255) NOT NULL,
    event_payload BYTEA NOT NULL,

    meta_ser_id INTEGER,
    meta_ser_manifest VARCHAR(255),
    meta_payload BYTEA,

    PRIMARY KEY(persistence_id, sequence_number)
    );

CREATE UNIQUE INDEX event_journal_ordering_idx ON public.event_journal(ordering);

CREATE TABLE IF NOT EXISTS public.event_tag(
                                               event_id BIGINT,
                                               tag VARCHAR(256),
    PRIMARY KEY(event_id, tag),
    CONSTRAINT fk_event_journal
    FOREIGN KEY(event_id)
    REFERENCES event_journal(ordering)
    ON DELETE CASCADE
    );

CREATE TABLE IF NOT EXISTS public.snapshot (
                                               persistence_id VARCHAR(255) NOT NULL,
    sequence_number BIGINT NOT NULL,
    created BIGINT NOT NULL,

    snapshot_ser_id INTEGER NOT NULL,
    snapshot_ser_manifest VARCHAR(255) NOT NULL,
    snapshot_payload BYTEA NOT NULL,

    meta_ser_id INTEGER,
    meta_ser_manifest VARCHAR(255),
    meta_payload BYTEA,

    PRIMARY KEY(persistence_id, sequence_number)
    );

CREATE TABLE IF NOT EXISTS public.durable_state (
                                                    global_offset BIGSERIAL,
                                                    persistence_id VARCHAR(255) NOT NULL,
    revision BIGINT NOT NULL,
    state_payload BYTEA NOT NULL,
    state_serial_id INTEGER NOT NULL,
    state_serial_manifest VARCHAR(255),
    tag VARCHAR,
    state_timestamp BIGINT NOT NULL,
    PRIMARY KEY(persistence_id)
    );
CREATE INDEX CONCURRENTLY state_tag_idx on public.durable_state (tag);
CREATE INDEX CONCURRENTLY state_global_offset_idx on public.durable_state (global_offset);


CREATE TABLE IF NOT EXISTS akka_projection_offset_store (
                                                            projection_name VARCHAR(255) NOT NULL,
    projection_key VARCHAR(255) NOT NULL,
    current_offset VARCHAR(255) NOT NULL,
    manifest VARCHAR(4) NOT NULL,
    mergeable BOOLEAN NOT NULL,
    last_updated BIGINT NOT NULL,
    PRIMARY KEY(projection_name, projection_key)
    );

CREATE INDEX IF NOT EXISTS akka_projection_name_index ON akka_projection_offset_store (projection_name);

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














