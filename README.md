# Demo

```
create DATABASE udefinitions

```


# How to run 

1) create DATABASE udefinitions
2) Execute all statements from `create_tables.sql`
3) 
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
grpcurl -d '{"definition":{"name":"ff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"111367c3-9ad3-47ef-a6b0-784d52c96489", "seqNum":"1"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut
grpcurl -d '{"definition":{"name":"ff6451324","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"222367c3-9ad3-47ef-a6b0-784d52c96489","seqNum":"3"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/ConditionalPut



```