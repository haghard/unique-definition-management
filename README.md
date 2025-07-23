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

http GET 127.0.0.1:8079/definitions/cluster/shards
http GET 127.0.0.1:8079/definitions/cluster/shards/tkn-dfn


```
1)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"jjj367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition_location":{"entityId":"3341739074684379528","seqNum":"1"}, "definition":{"name":"ff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"jjj367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


2)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"xxx367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition_location":{"entityId":"3341739074684379528","seqNum": "3"},"definition":{"name":"qqq13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"xxx367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

3)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"bnm367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definitionLocation":{"entityId":"3341739074684379528","seqNum":"3"},"definition":{"name":"34557385656gjf","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"bnm367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

4)

grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"pvb367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definitionLocation":{"entityId": "3341739074684379528","seqNum": "17"},"definition":{"name":"5656gjf","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"pvb367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

5)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"pab367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definitionLocation":{"entityId": "3341739074684379528","seqNum": "9"},"definition":{"name":"5656gjf111","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"pab367c3-9ad3-47ef-a6b0-784d52c96489"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


6)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"yyy367c3-9ad3-47ef-a6b0-784d52c96481"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definitionLocation":{"entityId": "3341739074684379528","seqNum":"11"},"definition":{"name":"ff6451111","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"yyy367c3-9ad3-47ef-a6b0-784d52c96481"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

```