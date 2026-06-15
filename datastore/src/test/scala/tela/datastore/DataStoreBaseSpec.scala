package tela.datastore

import java.net.URI
import java.nio.file.Paths
import org.apache.tika.metadata.TikaCoreProperties
import org.eclipse.rdf4j.model.util.Models
import org.eclipse.rdf4j.model.{IRI, Literal, Model, Value}
import org.eclipse.rdf4j.model.util.Values.iri
import org.eclipse.rdf4j.model.vocabulary.GEO
import org.scalatest.matchers.{MatchResult, Matcher}
import tela.baseinterfaces.DataStoreConnection.{DataLocationPredicateKey, FileFormatPredicateKey, FileNamePredicateKey, TextContentPredicateKey}
import tela.baseinterfaces.MultiplicityStrategy.Split
import tela.baseinterfaces.{BaseSpec, ComplexObject, DataType, SimpleObject}

import java.util.UUID
import scala.jdk.CollectionConverters.*

trait DataStoreBaseSpec extends BaseSpec {
  protected def asIRI(uri: URI): IRI = iri(uri.toString)
  protected def urnFromUuid(uuid: UUID): URI = new URI(DataStoreConnectionImpl.URNBaseForUUIDs + uuid)

  private def modelToString(model: Model): String = {
    def objectValue(v: Value): String = {
      if (v.isLiteral) {
        val literalValue = v.asInstanceOf[Literal]
        s"<${literalValue.getDatatype}>${literalValue.stringValue()}"
      } else v.stringValue()
    }

    model
      .iterator().asScala.toVector
      .sortBy(_.getPredicate.stringValue())
      .map { triple =>
        s"${triple.getSubject.stringValue()} ${triple.getPredicate.stringValue()} ${objectValue(triple.getObject).replaceAll("\n", "\\\\n")}"
      }
      .mkString("\n").prepended('\n').appended('\n')
  }

  protected def compareModels(expected: Model, actual: Model): MatchResult = {
    MatchResult(
      Models.isomorphic(expected, actual),
      s"""Expected ${modelToString(expected)}, but got ${modelToString(actual)}""",
      s"""Got the expected value $expected""")
  }

  protected def beIsomorphicWith(expectedValue: Model): Matcher[Model] = {
    new Matcher[Model]() {
      override def apply(actual: Model): MatchResult = {
        compareModels(expectedValue, actual)
      }
    }
  }

  protected val TestDataRoot = Paths.get("datastore/src/test/data")
  protected val TestTikaConfigFile = TestDataRoot.resolve("tika.xml")

  protected val TestHtmlFileName = Paths.get("testHTMLFile.html")
  protected val TestHtmlFile = TestDataRoot.resolve(TestHtmlFileName)
  protected val HashOfTestHtmlFile = "ee2b12a3e4521b1821c190b1efdaccfdffe2a6d5d1325fa24b9fe6a8630e8d43"

  protected val TestTextFileName = Paths.get("testTextFile.txt")
  protected val TestTextFile = TestDataRoot.resolve(TestTextFileName)
  protected val HashOfTestTextFile = "d9014c4624844aa5bac314773d6b689ad467fa4e1d1a50a1b8a99d5a95f72ff5"

  protected val TestMP3FileName = Paths.get("testMP3.mp3")
  protected val TestMP3 = TestDataRoot.resolve(TestMP3FileName)
  protected val HashOfTestMP3 = "b92a405044afe1280fb2ba6778aa159eae627069e30a2196867d9b0079473b0d"

  protected val TestZipFileName = Paths.get("testZipFileWithTextAndMP3.zip")
  protected val TestZipFile = TestDataRoot.resolve(TestZipFileName)
  protected val HashOfTestZipFile = "5fb0a7f0b4053d8558264c079990a739797fa10542eb6a1751fa285753728dc4"

  protected val TestTgzFileName = Paths.get("testTgzWithTextAndMP3.tgz")
  protected val TestTarFileName = "testTgzWithTextAndMP3.tar"
  protected val HashOfTestTgzFile = "56d045aa6546f4c7b98d1e37b0651417a13dfda4d0ca79e64a0212c32a5e2fdf"

  protected val TestFileLastModified = "2022-07-20T08:22:00.000Z"

  protected val TestUUID = UUID.fromString("00000000-0000-0000-c000-000000000046")
  protected val URNWithTestUUID = DataStoreConnectionImpl.URNBaseForUUIDs + TestUUID
  protected val URNWithTestUUIDAsURI = new URI(URNWithTestUUID)

  protected val TestIcalWithEventFileName = Paths.get("testIcalFile.ics")
  protected val TestIcalFileWithEvent = TestDataRoot.resolve(TestIcalWithEventFileName)
  protected val TestIcalFileWithoutEvent = TestDataRoot.resolve("testIcalFileWithoutEvent.ics")
  protected val TestIcalFileWithEmptyEvent = TestDataRoot.resolve("testIcalFileWithEmptyEvent.ics")
  protected val HashOfTestIcalFile = "1017e03f73106cb1bf6f13f335b92b19eef1c1c8f5cafc1add8a68cdc09cdd9d"

  protected val TestEmailFileName = Paths.get("testEmailFile.mbox")
  protected val TestEmailFile = TestDataRoot.resolve(TestEmailFileName)
  protected val HashOfTestEmailFile = "d097166a2c5e275bf5a1d9cc43f702109d3418025b6da64d99932af617037def"

  protected val SchemaDotOrgBase = new URI("http://schema.org")
  protected val NamePredicate = SchemaDotOrgBase.resolve("name")
  protected val GenericMediaFileType = SchemaDotOrgBase.resolve("MediaObject")
  protected val DataLocationPredicate = SchemaDotOrgBase.resolve("identifier")
  protected val FileFormatPredicate = SchemaDotOrgBase.resolve("encodingFormat")
  protected val FileNamePredicate = SchemaDotOrgBase.resolve("alternateName")
  protected val TextContentPredicate = SchemaDotOrgBase.resolve("description")
  protected val LastModifiedPredicate = SchemaDotOrgBase.resolve("lastModified")
  protected val PlainTextContentType = "text/plain"
  protected val ZipFileContentType = "application/zip"
  protected val GzipFileContentType = "application/gzip"
  protected val TarFileContentType = "application/x-tar"

  protected val GenericFileDataMap = ComplexObject(GenericMediaFileType, Map(
    DataLocationPredicate -> SimpleObject(Vector(DataLocationPredicateKey)),
    FileNamePredicate -> SimpleObject(Vector(FileNamePredicateKey)),
    FileFormatPredicate -> SimpleObject(Vector(FileFormatPredicateKey)),
    TextContentPredicate -> SimpleObject(Vector(TextContentPredicateKey)),
    LastModifiedPredicate -> SimpleObject(Vector(TikaCoreProperties.MODIFIED.getName), DataType.DateTime)))

  protected val MP3ContentType = "audio/mpeg"
  protected val MP3ObjectType = SchemaDotOrgBase.resolve("AudioObject")
  protected val AuthorPredicate = SchemaDotOrgBase.resolve("author")
  protected val GenrePredicate = SchemaDotOrgBase.resolve("genre")

  protected val PersonObjectType = SchemaDotOrgBase.resolve("Person")
  protected val MP3FileDataMap = ComplexObject(MP3ObjectType, Map(
    GenrePredicate -> SimpleObject(Vector("xmpDM:genre")),
    NamePredicate -> SimpleObject(Vector("dc:title")),
    AuthorPredicate -> ComplexObject(PersonObjectType, Map(NamePredicate -> SimpleObject(Vector("xmpDM:artist")))))
  )

  protected val ICalDataMap = ComplexObject(GenericMediaFileType, Map(
    NamePredicate -> SimpleObject(Vector(TikaCoreProperties.TITLE.getName))
  ))

  protected val GeoCoordinatesPredicateIri = GEO.AS_WKT
  protected val GeoCoordinatesPredicateUri = new URI(GeoCoordinatesPredicateIri.stringValue())

  protected val PlainTextDataMap = ComplexObject(GenericMediaFileType, Map(
    NamePredicate -> SimpleObject(Vector(TikaCoreProperties.RESOURCE_NAME_KEY))
  ))

  protected val EmailContentType = "message/rfc822"
  protected val EmailObjectType = SchemaDotOrgBase.resolve("EmailMessage")
  protected val DateSentPredicate = SchemaDotOrgBase.resolve("dateSent")
  protected val SenderPredicate = SchemaDotOrgBase.resolve("sender")
  protected val EmailPredicate = SchemaDotOrgBase.resolve("email")
  protected val ToRecipientPredicate = SchemaDotOrgBase.resolve("toRecipient")
  protected val CcRecipientPredicate = SchemaDotOrgBase.resolve("ccRecipient")

  protected val ContactPointObjectType = new URI("http://schema.org/ContactPoint")
  protected val EmailDataMap = ComplexObject(EmailObjectType, Map(
    DateSentPredicate -> SimpleObject(Vector(TikaCoreProperties.CREATED.getName), DataType.DateTime),
    SenderPredicate -> ComplexObject(PersonObjectType,
      Map(EmailPredicate -> SimpleObject(Vector("Message:From-Email")),
        NamePredicate -> SimpleObject(Vector("Message:From-Name")))),
    ToRecipientPredicate -> ComplexObject(ContactPointObjectType,
      Map(EmailPredicate -> SimpleObject(Vector("Message-To"), multiplicityStrategy = Split))),
    CcRecipientPredicate -> ComplexObject(ContactPointObjectType,
      Map(EmailPredicate -> SimpleObject(Vector("Message-Cc"), multiplicityStrategy = Split))))
  )

  protected val DateCreatedPredicate = SchemaDotOrgBase.resolve("dateCreated")
  protected val PlaceObjectType = SchemaDotOrgBase.resolve("Place")
  protected val EventObjectType = SchemaDotOrgBase.resolve("Event")
  protected val LocationPredicate = SchemaDotOrgBase.resolve("location")
}
