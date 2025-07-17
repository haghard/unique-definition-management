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

0)
grpcurl -d '{"definition":{"name":"bbb1","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"bbb367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition":{"name":"ccc1","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd","brand":"my-brand"},"owner_id":"ccc367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition":{"name":"eee567","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"eee367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create

1)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"jjj367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"prev_definition_location":{"entityId": 739737633,"seqNum": "1"}, "new_definition":{"name":"ff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"jjj367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


2)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"xxx367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"prev_definition_location":{"entityId":739737633,"seqNum":"3"},"new_definition":{"name":"qqq13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"xxx367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


3)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"bnm367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"prev_definition_location":{"entityId":739737633,"seqNum":"5"},"new_definition":{"name":"34557385656gjf","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"bnm367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


4)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"pvb367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"prev_definition_location":{"entityId":739737633,"seqNum":"7"},"new_definition":{"name":"5656gjf","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"pvb367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


```