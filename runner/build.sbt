name := "runner"

libraryDependencies += "org.playframework" %% "play-guice" % "3.0.11"

// Sadly at the time of writing this FileSystemProvider doesn't get picked up in Play framework dev mode by default
// The workaround I have found is to set the SBT_OPTS environment variable to something like:
// "-Xbootclasspath/a:/PATH/TO/TARBALLFS.JAR:/PATH/TO/COMMONS-COMPRESS.JAR:/PATH/TO/COMMONS-IO.JAR:/PATH/TO/COMMONS-LANG.JAR"
libraryDependencies += "io.github.iachimoe.tarballfs" % "tarballfs" % "1.0.0"

// This is useful for testing downloads of files embedded within large archives that take a long time to decompress
PlayKeys.devSettings += "play.server.http.idleTimeout" -> "300s"

//TODO This is not nice right now. Have to explicitly run sbt compile before
//running in dev environment to make sure deps are picked up
//https://discuss.lightbend.com/t/typescript-with-play-framework/5990/2
//illustrates how "watching" facility might be enabled, for webpack rather than esbuild
//but the principle should be the same
import scala.sys.process.Process
import java.io.File
lazy val packageWebDeps = TaskKey[Unit]("packageWebDeps", "Package js/css dependencies")
packageWebDeps := {
  List("npm ci", "node packageWebDependencies.mjs").foreach(command => {
    if (Process(command, new File("./runner")).! != 0) {
      throw new IllegalStateException(s"running $command failed!")
    }
  })
}

(Compile / compile) := ((Compile / compile) dependsOn packageWebDeps).value

enablePlugins(AshScriptPlugin)
Docker / packageName := "tela"
dockerBuildOptions += "--no-cache"
dockerBaseImage := "eclipse-temurin:25-alpine"
dockerBuildxPlatforms := Seq("linux/arm64/v8", "linux/amd64")
