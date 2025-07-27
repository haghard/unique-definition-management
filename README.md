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
http GET 127.0.0.1:8079/definitions/cluster/shards/taken-dfn


```
1)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"406393e3-331d-4807-9100-ff69ebfdae56"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition_location":{"entityId":"3341739074684379528","seqNum":"1"}, "definition":{"name":"ff13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"406393e3-331d-4807-9100-ff69ebfdae56"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


2)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"406393e3-331d-4807-9100-ff69ebfdae51"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definition_location":{"entityId":"3341739074684379528","seqNum": "3"},"definition":{"name":"qqq13334","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"406393e3-331d-4807-9100-ff69ebfdae51"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

3)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"406393e3-331d-4807-9100-ff69ebfdae52"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definitionLocation":{"entityId":"3341739074684379528","seqNum":"5"},"definition":{"name":"34557385656gjf","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"406393e3-331d-4807-9100-ff69ebfdae52"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

4)

grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"406393e3-331d-4807-9100-ff69ebfdae53"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definitionLocation":{"entityId":"3341739074684379528","seqNum":"7"},"definition":{"name":"5656gjf","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"406393e3-331d-4807-9100-ff69ebfdae53"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update

5)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"406393e3-331d-4807-9100-ff69ebfdae54"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definitionLocation":{"entityId": "3341739074684379528","seqNum":"9"},"definition":{"name":"5656gjf111","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"}, "owner_id":"406393e3-331d-4807-9100-ff69ebfdae54"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


6)
grpcurl -d '{"definition":{"name":"ff645","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"406393e3-331d-4807-9100-ff69ebfdae55"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Create
grpcurl -d '{"definitionLocation":{"entityId":"3341739074684379528","seqNum":"11"},"definition":{"name":"ff6451111","address":"a","city":"FL","state":"FL","country":"US","zipCode":"34234sd"},"owner_id":"406393e3-331d-4807-9100-ff69ebfdae55"}' -plaintext 127.0.0.1:8080 com.definition.api.DefinitionService/Update


```