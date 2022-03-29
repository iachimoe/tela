name := "runner"

libraryDependencies += "com.typesafe.play" %% "play-guice" % "2.8.13"

//TODO to keep websockets alive in dev mode, as per hack in tela.conf
PlayKeys.devSettings += "play.server.http.idleTimeout" -> "infinite"

enablePlugins(AshScriptPlugin)
Docker / packageName := "tela"
dockerBuildOptions += "--no-cache"
dockerBaseImage := "eclipse-temurin:11-alpine"
