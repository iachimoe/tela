import java.net.URI

import play.api.libs.functional.syntax._
import play.api.libs.json._
import tela.baseinterfaces._

object DataMappingReads {
  implicit val simpleObjectReads: Reads[SimpleObject] = (
    (JsPath \ "properties").read[Vector[String]] and
      (JsPath \ "dataType").read[String].map(DataType.withName)
    ) (SimpleObject.apply _)

  implicit lazy val complexObjectReads: Reads[ComplexObject] = (
    (JsPath \ DataStoreConnection.MediaItemTypeKey).read[String].map(new URI(_)) and
      (JsPath \ "children").lazyRead[Map[String, RDFObjectDefinition]](Reads.map(rdfObjectDefinitionReads)).map(_.map({ case (k: String, v: RDFObjectDefinition) => (new URI(k), v) }))
    ) (ComplexObject.apply _)

  implicit val rdfObjectDefinitionReads = new Reads[RDFObjectDefinition] {
    override def reads(json: JsValue): JsResult[RDFObjectDefinition] = {
      json.validate[SimpleObject].orElse(Json.fromJson[ComplexObject](json))
    }
  }
}
