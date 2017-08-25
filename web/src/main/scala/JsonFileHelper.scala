package tela.web

import java.nio.file.{Files, Path}

import play.api.libs.json.{JsValue, Json}

object JsonFileHelper {
  def getContents(path: Path): JsValue = {
    Json.parse(new String(Files.readAllBytes(path)))
  }
}
