# Distributed sharded index that support ConditionPut: acquire only if the object doesn’t belong to other owner_id

## Requirements

✅ Optimize for low latency.

✅ Ensure correctness in all scenarios. 

✅ Clients are able to order their own operations and provide a globally unique ID (`owner_id`) for them.


### Optimize the write latency

You usually can't have both low latency and ordering in Distributed Systems. 

Each unique definition belongs to exactly one `owner_id` at any point in time - this is what we want to achieve.

It is a relative order invariant. Acquiring new definition requires a condition check, and then releasing its current definition which doesn't require any checks and cannot fail. 
Causal ordering if enough to guarantee that we never violate it.


//keeps locks with ttl for requests that are being processed

### Write path: Create (2RTTs)
    1) Read existing row by `owner_id` from `definition_index_view`. if found it inserts into `temporal_constraints` as an attempt to guarantee a total order of CREATE operations by `owner_id` 
    2) Asks `TakenDefinition` to perform `Conditional Put`.
                                                                            

### Write path: Update (2RTTs)
    1) Read existing row by `owner_id` from `definition_index_view`. if found it sets `isLocked`=true with ttl to prevent concurrent updates
    2) Asks `TakenDefinition` to perform `Conditional Put`.


### Implementation details
To support 2-dimensional locking (firstly, we need to lock by `owner_id`; secondly, by `definition`) we use a combination of 2 techniques:
a) explicit locking techniques such as `SELECT FOR UPDATE` on the database level.
b) akka's atomic and lock-free read-modify-write operation.


```
create DATABASE udefinitions

```


# How to run 

Execute all statements from `create_tables.sql`

```
sbt a
```

```
sbt b
```



### Example method calls

```

grpcurl -plaintext 127.0.0.1:8080 list

http GET 127.0.0.1:8079/definitions/cluster/members
http GET 127.0.0.2:8079/definitions/cluster/shards
http GET 127.0.0.2:8079/definitions/cluster/shards/tkn-dfn


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"3341739074684379528","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"aas13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"6898668511187520942","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"aas13335","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"2906301794710397039","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"aas13336","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"5481507287789486185","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"aas13336","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"5481507287789486185","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff6451324","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"3341739074684379528","seqNum":"3"}, "owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff6451325","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"4875145662544880660","seqNum":"1"}, "owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff6451326","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"6031681633450188570","seqNum":"1"}, "owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"322367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/GetCurrentValue

```



## Reproduce Create|Update conflicts

```

Create conflict

// Thread.sleep(3_000) for local testing

grpcurl -d '{"definition":{"name":"aff645a","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"bff645b","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


```



```

Update conflict

// Thread.sleep(3_000) for local testing

grpcurl -d '{"definition":{"name":"bbbff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"xff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"3958406442293610682","seqNum":"1"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"zff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"location":{"bucketId":"3958406442293610682","seqNum":"1"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
 

```

```
TRUNCATE table akka_projection_management;
TRUNCATE table akka_projection_offset_store;
TRUNCATE table snapshot;
TRUNCATE table event_tag;
DELETE FROM event_journal;
DROP TABLE definition_index_view;
```


"Correct but not fast. Fast but Not Correct" ->  "Fast and Correct".

*****

TODO:
1) pekko.persistence.r2dbc.journal.publish-events = on
2) At least once delivery instead of db locking


### Links

https://pekko.apache.org/docs/pekko-persistence-r2dbc/current/query.html#eventsbyslices
https://vladmihalcea.com/database-job-queue-skip-locked/
https://habr.com/ru/articles/940066/
https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html



https://www.cs.usfca.edu/~galles/visualization/BPlusTree.html
https://planetscale.com/blog/btrees-and-database-indexes
https://github.com/scylladb/scylla-tools-java/blob/0b4accdd5ecb69a6346151987ba974e6be02b123/src/java/org/apache/cassandra/utils/btree/BTree.java




https://doc.akka.io/libraries/akka-core/2.6/typed/reliable-delivery.html#durable-producer

https://github.com/crossroad0201/akka-cluster-sharding-sandbox/tree/main/src/main/scala/crossroad0201/sandbox/akkaclustersharding/pattern_b

dynamic-bucketing: https://planetscale.com/blog/btrees-and-database-indexes

CAS

https://github.com/crossroad0201/akka-cluster-sharding-sandbox/blob/main/src/main/scala/crossroad0201/sandbox/akkaclustersharding/pattern_b/TodoActorBroker.scala

https://github.com/hoytech/riblet/blob/master/src/RIBLT.h

Zone {

}

http://muratbuffalo.blogspot.com/2020/05/matchmaker-paxos-reconfigurable.html

https://github.com/MouslihAbdelhakim/sicrograd?tab=readme-ov-file

https://jhellerstein.github.io/blog/crdt-dont-read/
https://www.geeknarrator.com/blog/buf-schema-driven-dev


*****
I-offender - operations that may break app level invariants when executed concurrently.

Create(owner_id=1) <> Create(owner_id=1)
Update(owner_id=1) Update(owner_id=1,)
Concurrent: Create and Update that modify the same definition.


*******

Atomic read-modify-write loop
Linearizable CAS register


## License
This code is open source software licensed under the [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).
