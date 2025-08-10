
i=0
j=30

grpcurl -d '{"definition":{"name":"c","address":"aaaa","city":"FL","state":"FL","country":"US","zipCode":"3335345"},"seqNum":0,"owner_id":"aaa367c3-9ad3-47ef-a6b0-784d52c96113"}' -plaintext 127.0.0.2:8080 com.definition.api.DefinitionService/ConditionalPut;
sleep 1s

while [ $i -ne $j ]
do
  grpcurl -d '{"definition":{"name":"'c"${i}"'","address":"aaaa","city":"FL","state":"FL","country":"US","zipCode":"3335345"},"seqNum":1,"owner_id":"aaa367c3-9ad3-47ef-a6b0-784d52c96113"}' -plaintext 127.0.0.2:8080 com.definition.api.DefinitionService/ConditionalPut;
  sleep .1s
  i=$(($i+1))
done
