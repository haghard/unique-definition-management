## Distributed sharded index that support ConditionPut: acquire only if the object doesn’t belong to other owner_id

## Requirements

✅ Optimize for low latency.

✅ Ensure correctness in all scenarios. 

✅ Clients are able to order their own operations and provide a globally unique ID (`owner_id`) for them.


### Optimize the write latency

You usually can't have both low latency and ordering in Distributed Systems. 

Each unique definition belongs to exactly one `owner_id` at any point in time - this is what we want to achieve.

It is a relative order invariant. Acquiring a new definition requires condition check, and then releasing its current definition which doesn't require any checks and cannot fail. 
Causal ordering if enough to guarantee that we never violate it.


### Write path
 1) One database RTT to transactionally read existing data by `owner_id` from `definition_index_view` and one insert into `temporal_constraints` as an attempt to guarantee a total order of create/update operations by `owner_id` 
 2) One akka-sharding clustered RTT to perform `Conditional Put` 


### I-offender 
Operations that may break app level invariants when executed concurrently.

### Create

1) `OwnerId(1)` attempt to obtain definition=`a` and definition=`b` at the same time  
   Create(ownerId(1), definition=a) <> Create(ownerId(1), definition=b)

2) `OwnerId(1)` and `OwnerId(2)` attempt to obtain definition=`a` at the same time
   Create(ownerId(1), definition=a) <> Create(ownerId(2), definition=a)

### Update
    
1) `OwnerId(1)` attempts to update definition=`a` to definition=`b` from different clients at the same time
2) `OwnerId(1)` attempts to update definition=`a` to definition=`b` and definition=`a` to definition=`c` from different clients at the same time

```
create DATABASE udefinitions

```


# How to run 

1) create DATABASE udefinitions
2) Execute all statements from `create_tables.sql`

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
grpcurl -d '{"definition":{"name":"ff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3341739074684379528","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"aas13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"6898668511187520942","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff6451324","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3341739074684379528","seqNum":"3"}, "owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"322367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/getDefinitionLocation

```



## Reproduce Create conflicts

Create conflict1: `OwnerId(1)` attempt to obtain `definition=a` and `definition=b` at the same time from different clients

```
 
grpcurl -d '{"definition":{"name":"ff645a","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96481" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff645b","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96481" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

```

Create conflict2: `OwnerId(1)` and `OwnerId(2)` attempt to obtain `definition=a` at the same time from different clients

```
  
grpcurl -d '{"definition":{"name":"cff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96483" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"cff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96484" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


```


Update conflict1: `OwnerId(1)` attempt to update definition=`a` to definition=`b` from different clients at the same time
```
grpcurl -d '{"definition":{"name":"af64567868","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96488" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"af64567868a","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96488","location":{"bucketId":"-3052600320989721612","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"af64567868a","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96488","location":{"bucketId":"-3052600320989721612","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
 
```


Update conflict2: `OwnerId(1)` attempts to update definition=`a` to definition=`b` and definition=`a` to definition=`c` from different clients at the same time
``` 
grpcurl -d '{"definition":{"name":"ccf64567868","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"ccf64567868a","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96489","location":{"bucketId":"2820986158190524712","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ccf64567868b","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96489","location":{"bucketId":"2820986158190524712","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


`OwnerId(1)` attempt to update definition to `definition=a` and `definition=b` at the same time from different clients

```


```
TRUNCATE table akka_projection_management;
TRUNCATE table akka_projection_offset_store;
TRUNCATE table snapshot;
TRUNCATE table event_tag;
DELETE FROM event_journal;
DROP TABLE definition_index_view;
```


### Links

https://pekko.apache.org/docs/pekko-persistence-r2dbc/current/query.html#eventsbyslices
https://vladmihalcea.com/database-job-queue-skip-locked/
https://habr.com/ru/articles/940066/
https://dev.mysql.com/doc/refman/8.4/en/innodb-transaction-isolation-levels.html