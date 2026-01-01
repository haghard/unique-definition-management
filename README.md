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


### Write path (Update)
 1) One database RTT (read-modify-write) to read existing data by `owner_id` from `definition_index_view` and update  it (set is_locker=true)
 2) One clustered RTT to perform `ConditionalPut` 


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

```
   lock(OwnerId) {
      lock(Old_Definition) {
         create_if_not_exist (New_Definition)
         release(Old_Definition)
      }
   }

```


## Reproduce Create conflicts

Create conflict1: `OwnerId(1)` attempt to obtain `definition=ff645a` and `definition=ff645b` at the same time from different clients (contention on `OwnerId(1)`)

```
 
grpcurl -d '{"definition":{"name":"ff645a","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96481"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff645b","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96481"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

```

Create conflict2: `OwnerId(1)` and `OwnerId(2)` attempt to obtain `definition=cff645` at the same time from different clients (Contention on `definition=cff645`)

```
  
grpcurl -d '{"definition":{"name":"cff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96483" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"cff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96484" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

```


## Update conflicts

Conflict #1: `OwnerId(1)` attempt to update definition=`aa` to definition=`bb` from different clients at the same time causing contention on `OwnerId(1)`

```
grpcurl -d '{"definition":{"name":"aa","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96486" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"bb","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96486","location":{"bucketId":"852982107908317497","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"bb","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96486","location":{"bucketId":"852982107908317497","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
 
```


Conflict #2: `OwnerId(1)` attempts to update definition=`ccf64567868a` to definition=`ccf64567868b` and definition=`a` to definition=`c` from different clients at the same time  causing contention on `OwnerId(1)`

``` 

grpcurl -d '{"definition":{"name":"ccf64567868","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"ccf64567868a","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96489","location":{"bucketId":"2820986158190524712","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ccf64567868b","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96489","location":{"bucketId":"2820986158190524712","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

`OwnerId(1)` attempt to update definition to `definition=a` and `definition=b` at the same time from different clients

```

Conflict #3: 
   `OwnerId(1)` attempts to update its definition to `ccf64567868b` while `OwnerId(2)` attempts to update its definition to the same value 
   and at the same time causing contention on definition(`cc645697`)


```

grpcurl -d '{"definition":{"name":"aaf645699","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96471" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"bbf645698","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96472" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


grpcurl -d '{"definition":{"name":"cc645697","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96471","location":{"bucketId":"2911202068030624907","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"cc645697","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96472","location":{"bucketId":"-4366455400474742120","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


`OwnerId(1)` attempt to update definition to `definition=a` and `definition=b` at the same time from different clients

```

        

Create/Update conflicts

```

grpcurl -d '{"definition":{"name":"a","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96482" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


grpcurl -d '{"definition":{"name":"b","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96482","location":{"bucketId":"-5157777011468103420","seqNum": "1"}}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"b","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96485"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


```


  
```
grpcurl -d '{"ownerId":"211367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/GetDefinitionLocation

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