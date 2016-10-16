package tela.datastore

import java.net.URI

import org.apache.tika.metadata.TikaCoreProperties
import tela.baseinterfaces.DataStoreConnection._
import tela.baseinterfaces.{ComplexObject, SimpleObject}

object TestData {
  private[datastore] val TestDataRoot = "datastore/src/test/data"
  private[datastore] val TestTikaConfigFile = TestDataRoot + "/tika.xml"
  private[datastore] val TestIcalFileWithEvent = TestDataRoot + "/testIcalFile.ics"
  private[datastore] val TestIcalFileWithoutEvent = TestDataRoot + "/testIcalFileWithoutEvent.ics"
  private[datastore] val TestIcalFileWithEmptyEvent = TestDataRoot + "/testIcalFileWithEmptyEvent.ics"
  private[datastore] val HashOfTestIcalFile = "d89bda3b9b6cb525c3b9d1f446254cd76abdcc02"

  private[datastore] val GenericMediaFileType = new URI("http://schema.org/MediaObject")
  private[datastore] val HashPredicate = new URI("http://schema.org/alternateName")
  private[datastore] val FileFormatPredicate = new URI("http://schema.org/encodingFormat")
  private[datastore] val HtmlContentType = "text/html"

  private[datastore] val GenericFileDataMap = ComplexObject(GenericMediaFileType, Map(
    HashPredicate -> SimpleObject(List(HashPredicateKey)),
    FileFormatPredicate -> SimpleObject(List(FileFormatPredicateKey))))

  private[datastore] val MP3ContentType = "audio/mpeg"
  private[datastore] val MP3ObjectType = new URI("http://schema.org/AudioObject")

  val PersonType = new URI("http://schema.org/Person")
  private[datastore] val MP3FileDataMap = ComplexObject(MP3ObjectType, Map(
    new URI("http://schema.org/genre") -> SimpleObject(List("xmpDM:genre")),
    new URI("http://schema.org/name") -> SimpleObject(List("dc:title")),
    new URI("http://schema.org/author") -> ComplexObject(PersonType, Map(new URI("http://schema.org/name") -> SimpleObject(List("xmpDM:artist")))))
  )

  private[datastore] val ICalDataMap = ComplexObject(GenericMediaFileType, Map(
    new URI("http://schema.org/name") -> SimpleObject(List(TikaCoreProperties.TITLE.getName)))
  )
}
