# Distributed sharded index that support ConditionPut: acquire only if the object doesn’t belong to other owner_id

## Requirements

✅ Optimize for low latency.

✅ Ensure correctness in all scenarios. 

### Optimize the write latency

You usually can't have both low latency and ordering in Distributed Systems. 

Each unique definition belongs to exactly one `owner_id` at any point in time - this is what we want to achieve.

It is a relative order invariant. Acquiring a new definition requires condition check, and then releasing its current definition which doesn't require any checks and cannot fail. 
Causal ordering if enough to guarantee that we never violate it.
 
*****
I-offender - operations that may break app level invariants when executed concurrently.

Create(owner_id=1) <> Create(owner_id=1)
Update(owner_id=1) Update(owner_id=1,)
Concurrent: Create and Update that modify the same definition.


*******


### Write path
 1) One database RTT to transactionally read existing data by `owner_id` from `definition_index_view` and one insert into `temporal_constraints` as an attempt to guarantee a total order of create/update operations by `owner_id` 
 2) One akka-sharding clustered RTT to perform `Conditional Put` 


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
grpcurl -d '{"definition":{"name":"aas13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"2906301794710397039","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff6451324","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3341739074684379528","seqNum":"3"}, "owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"322367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/getDefinitionLocation

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

grpcurl -d '{"definition":{"name":"xff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3958406442293610682","seqNum":"1"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"zff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3958406442293610682","seqNum":"1"},"owner_id":"211367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
 

```

table TEMPORAL_CONSTRAIN TRX_WRITE_SET(trx_lock) owner_id, status=locked         (from=alice,to=bob,product_a) select_for_update 
