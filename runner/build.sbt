name := "runner"

libraryDependencies += "com.typesafe.play" %% "play-guice" % "2.8.1"

//TODO to keep websockets alive in dev mode, as per hack in tela.conf
PlayKeys.devSettings += "play.server.http.idleTimeout" -> "infinite"
