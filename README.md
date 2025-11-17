### Distributed sharded index that support ConditionPut: acquire only if the object doesn’t belong to other owner_id

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


grpcurl -d '{"definition":{"name":"ff645181","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff645182","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96482" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


grpcurl -d '{"definition":{"name":"ff645186","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481","location":{"shardId":2,"definitionId":2} }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


```



## Reproduce Create|Update conflicts

```

Create conflict

// Thread.sleep(3_000) for local testing

T1
grpcurl -d '{"definition":{"name":"ff645181","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

T2
grpcurl -d '{"definition":{"name":"ff645182","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

```



```

Update conflict

// Thread.sleep(3_000) for local testing

grpcurl -d '{"definition":{"name":"ff645181","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"ff645186","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481","location":{"shardId":2,"definitionId":1} }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff645186","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481","location":{"shardId":2,"definitionId":1} }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


OR

grpcurl -d '{"definition":{"name":"ff645181","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

grpcurl -d '{"definition":{"name":"ff645185","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481","location":{"shardId":2,"definitionId":1} }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff645185","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96483" }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut



grpcurl -d '{"definition":{"name":"ff645185","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96481","location":{"shardId":2,"definitionId":1} }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff645185","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"311367c3-9ad3-47ef-a6b0-784d52c96482","location":{"shardId":3,"definitionId":1} }' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut

 

```


"Correct but not fast. Fast but Not Correct" ->  "Fast and Correct".


## License
This code is open source software licensed under the [Apache 2.0 License](http://www.apache.org/licenses/LICENSE-2.0.html).
