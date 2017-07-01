package tela.datastore

import java.io.{BufferedInputStream, FileInputStream}

import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.parser.ParseContext
import org.apache.tika.sax.BodyContentHandler
import org.scalatest.Matchers._

class ICalParserSpec extends DataStoreBaseSpec {
  "ICalParser" should "extract title, description lat/long and date for event" in {
    val metadata = getMetadataFromIcalFile(TestIcalFileWithEvent)

    metadata.get(TikaCoreProperties.TITLE) should === ("DDD London #3 - Strategic and Collaborative Domain-Driven Design")
    metadata.get(TikaCoreProperties.DESCRIPTION) should === ("Domain-Driven Design London / DDD London\nTuesday, July 5 at 6:30 PM")
    metadata.get(TikaCoreProperties.LATITUDE) should === ("51.52")
    metadata.get(TikaCoreProperties.LONGITUDE) should === ("-0.10")
    metadata.get(TikaCoreProperties.METADATA_DATE) should === ("2016-07-05T18:30:00")
  }

  it should "return no metadata for a file without an event" in {
    val metadata = getMetadataFromIcalFile(TestIcalFileWithoutEvent)
    metadata.size() should === (0)
  }

  it should "return no metadata for a file with an empty event" in {
    val metadata = getMetadataFromIcalFile(TestIcalFileWithEmptyEvent)
    metadata.size() should === (0)
  }

  private def getMetadataFromIcalFile(filename: String): Metadata = {
    val parser = new ICalParser()
    val metadata = new Metadata()
    val handler = new BodyContentHandler(-1)
    val context = new ParseContext()

    val stream = new BufferedInputStream(new FileInputStream(filename))
    try {
      parser.parse(stream, handler, metadata, context)
      metadata
    } finally {
      stream.close()
    }
  }
}
