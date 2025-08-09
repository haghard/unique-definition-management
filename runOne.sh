
i=0
j=20

grpcurl -d '{"definition":{"name":"a","address":"aaaa","city":"FL","state":"FL","country":"US","zipCode":"3335345"},"owner_id":"aaa367c3-9ad3-47ef-a6b0-784d52c96111"}' -plaintext 127.0.0.2:8080 com.definition.api.DefinitionService/Create;
sleep 2s

while [ $i -ne $j ]
do
  i=$(($i+1))
  grpcurl -d '{"definition":{"name":"'a"${i}"'","address":"aaaa","city":"FL","state":"FL","country":"US","zipCode":"3335345"},"owner_id":"aaa367c3-9ad3-47ef-a6b0-784d52c96111"}' -plaintext 127.0.0.2:8080 com.definition.api.DefinitionService/Update;
  sleep .5s
done
