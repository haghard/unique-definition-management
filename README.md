# Distributed sharded index that support ConditionPut operation

## Requirements

✅ Optimize the write latency. 
✅ Ensure correctness in all scenarios. 

### Optimize the write latency

I rely on eventual consistency to guarantee low latency for all operations.
We can reply without having to wait for each update being applied on the read side. Moreover, attempt we ensure a total order of operation by `owner_id`.
Why it is possible ? Because it doesn't violate our app level invariant: Each unique definition belongs to exactly one owner_id at any point in time.

### Write path
 1) One database RTT to lookup data by `owner_id` as an attempt to ensure a total order of updates by `owner_id` 
 2) One akka-sharding RTT to perform `Conditional Put`
           

### Ensure correctness: Conflict detection and resolution strategy

We do not prevent concurrent writes by the same `owner_id`, although it should not happen under normal circumstances. But we detect them and rollback the conflicting change.




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


`grpcurl -plaintext 127.0.0.1:8080 list`

`http GET 127.0.0.1:8079/definitions/cluster/members`

http GET 127.0.0.2:8079/definitions/cluster/shards
http GET 127.0.0.2:8079/definitions/cluster/shards/tkn-dfn


```


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3341739074684379528","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"aas13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"2906301794710397039","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff6451324","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3341739074684379528","seqNum":"3"}, "owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"owner_id": "222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/getDefinitionLocation

```



## Reproduce Create|Update conflicts

```

Create conflict

// Thread.sleep(3_000) for local testing

grpcurl -d '{"definition":{"name":"aff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"bff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


```



```

Update conflict

// Thread.sleep(3_000) for local testing

grpcurl -d '{"definition":{"name":"bbbff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"xff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3341739074684379528","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"zff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"definitionLocation":{"bucketId":"3341739074684379528","seqNum":"1"},"owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

```