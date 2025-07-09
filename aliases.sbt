
addCommandAlias(
  "a",
  "runMain com.definition.App\n" +
    "-DGRPC_PORT=8080\n" +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.1\n" +
    "-DCONTACT_POINTS=127.0.0.1,127.0.0.2"
)

//sudo ifconfig lo0 127.0.0.2 add
//sudo ifconfig lo0 alias 127.0.0.2 up
addCommandAlias(
  "b",
  "runMain com.definition.App\n" +
    "-DGRPC_PORT=8080\n" +
    "-Dakka.remote.artery.canonical.port=2550\n" +
    "-Dakka.remote.artery.canonical.hostname=127.0.0.2\n" +
    "-DCONTACT_POINTS=127.0.0.1,127.0.0.2"
)
