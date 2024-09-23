name := "runner"

libraryDependencies += "org.playframework" %% "play-guice" % "3.0.5"

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
dockerBaseImage := "eclipse-temurin:21-alpine"

//dockerBuildxPlatforms := Seq("linux/arm64/v8", "linux/amd64") //WHYYYY DOESN'T THIS WORK????
//From here https://github.com/sbt/sbt-native-packager/issues/1548 doesn't seem to work
/*dockerBuildCommand := dockerExecCommand.value ++ (
  if (dockerBuildxPlatforms.value.isEmpty) { Seq("build") }
  else { Seq("buildx", "build",  s"--platform=${dockerBuildxPlatforms.value.mkString(",")}") }
  ++ dockerBuildOptions.value ++ Seq(".")
)*/
