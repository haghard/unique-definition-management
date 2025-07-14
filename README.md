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
grpcurl -d '{"definition":{"name":"a11","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"aaa367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition":{"name":"bbb1","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"bbb367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition":{"name":"ccc1","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"ccc367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition":{"name":"ddd1","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"ddd367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition":{"name":"eee567","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"eee367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition":{"name":"f67","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"fff367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create


grpcurl -d '{"prev_definition":{"name":"a11","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"new_definition":{"name":"ccc11","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"aaa367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update
grpcurl -d '{"prev_definition":{"name":"bbb1","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"new_definition":{"name":"a11","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"bbb367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

grpcurl -d '{"definition":{"name":"bbb1","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"ggg367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"hhh367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"prev_definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"new_definition":{"name":"ff646","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"hhh367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"iii367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"prev_definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"new_definition":{"name":"ff647","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"iii367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"jjj367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"prev_definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"new_definition":{"name":"ff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"jjj367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"kkk367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create

```