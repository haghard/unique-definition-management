

i=99
j=199

while [ $i -ne $j ]
do
  i=$(($i+1))
  grpcurl -d '{"definition":{"name":"'a"${i}"'","address":"aaaa","city":"FL","state":"FL","country":"US","zipCode":"3335345"},"owner_id":"'aaa367c3-9ad3-47ef-a6b0-784d52c96"${i}"'" }' -plaintext 127.0.0.2:8080 com.definition.api.DefinitionService/Create;
  sleep .1
done
