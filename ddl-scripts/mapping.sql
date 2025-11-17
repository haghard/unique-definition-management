DROP TABLE `mapping0`;
CREATE TABLE `mapping0`
(
    `DEFINITION_ID` bigint NOT NULL AUTO_INCREMENT,
    `definition`    BLOB   NOT NULL,
    PRIMARY KEY (`DEFINITION_ID`),
    INDEX (`definition`(3072)) -- 65535
);

ALTER TABLE `mapping0`
    ADD COLUMN h1 VARCHAR(32) GENERATED ALWAYS AS (MD5(`definition`)) STORED;

ALTER TABLE `mapping0`
    ADD COLUMN h2 VARCHAR(40) GENERATED ALWAYS AS (SHA1(`definition`)) STORED;

ALTER TABLE `mapping0`
    ADD UNIQUE INDEX h_ind (h1,h2);



DROP TABLE `mapping1`;
CREATE TABLE `mapping1`
(
    `DEFINITION_ID` bigint NOT NULL AUTO_INCREMENT,
    `definition`    BLOB   NOT NULL,
    PRIMARY KEY (`DEFINITION_ID`),
    INDEX (`definition`(3072)) -- 65535
);

ALTER TABLE `mapping1`
    ADD COLUMN h1 VARCHAR(32) GENERATED ALWAYS AS (MD5(`definition`)) STORED;

ALTER TABLE `mapping1`
    ADD COLUMN h2 VARCHAR(40) GENERATED ALWAYS AS (SHA1(`definition`)) STORED;

ALTER TABLE `mapping1`
    ADD UNIQUE INDEX h_ind (h1,h2);



DROP TABLE `mapping2`;
CREATE TABLE `mapping2`
(
    `DEFINITION_ID` bigint NOT NULL AUTO_INCREMENT,
    `definition`    BLOB   NOT NULL,
    PRIMARY KEY (`DEFINITION_ID`),
    INDEX (`definition`(3072)) -- 65535
);

ALTER TABLE `mapping2`
    ADD COLUMN h1 VARCHAR(32) GENERATED ALWAYS AS (MD5(`definition`)) STORED;

ALTER TABLE `mapping2`
    ADD COLUMN h2 VARCHAR(40) GENERATED ALWAYS AS (SHA1(`definition`)) STORED;

ALTER TABLE `mapping2`
    ADD UNIQUE INDEX h_ind (h1,h2);

DROP TABLE `mapping3`;
CREATE TABLE `mapping3`
(
    `DEFINITION_ID` bigint NOT NULL AUTO_INCREMENT,
    `definition`    BLOB   NOT NULL,
    PRIMARY KEY (`DEFINITION_ID`),
    INDEX (`definition`(3072)) -- 65535
);

ALTER TABLE `mapping3`
    ADD COLUMN h1 VARCHAR(32) GENERATED ALWAYS AS (MD5(`definition`)) STORED;

ALTER TABLE `mapping3`
    ADD COLUMN h2 VARCHAR(40) GENERATED ALWAYS AS (SHA1(`definition`)) STORED;

ALTER TABLE `mapping3`
    ADD UNIQUE INDEX h_ind (h1,h2);



