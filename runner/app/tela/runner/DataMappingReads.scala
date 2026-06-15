package tela.runner

import play.api.libs.functional.syntax.*
import play.api.libs.json.*
import tela.baseinterfaces.*
import tela.baseinterfaces.MultiplicityStrategy.Aggregate

import java.net.URI

object DataMappingReads {
  implicit val simpleObjectReads: Reads[SimpleObject] = (
    (JsPath \ "properties").read[Vector[String]] and
      (JsPath \ "dataType").read[String].map(DataType.valueOf) and
      (JsPath \ "multiplicityStrategy").readNullable[String].map(multiplicityStrategy =>
        multiplicityStrategy.map(MultiplicityStrategy.valueOf).getOrElse(Aggregate))
    ) (SimpleObject.apply _)

  implicit lazy val complexObjectReads: Reads[ComplexObject] = (
    (JsPath \ DataStoreConnection.ObjectTypeKey).read[String].map(new URI(_)) and
      (JsPath \ "children").lazyRead[Map[String, RDFObjectDefinition]](Reads.map(rdfObjectDefinitionReads)).map(_.map(
        { case (k: String, v: RDFObjectDefinition) => (new URI(k), v) }))
    ) (ComplexObject.apply _)

  implicit val rdfObjectDefinitionReads: Reads[RDFObjectDefinition] = (json: JsValue) => {
    json.validate[SimpleObject].orElse(Json.fromJson[ComplexObject](json))
  }
}
