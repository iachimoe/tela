package tela.datastore

import java.io.InputStream
import java.text.SimpleDateFormat
import java.util
import java.util.{Collections, Locale}

import net.fortuna.ical4j.data.CalendarBuilder
import net.fortuna.ical4j.model.property.{DtStart, Geo}
import net.fortuna.ical4j.model.{Component, Property}
import org.apache.tika.metadata.{Metadata, TikaCoreProperties}
import org.apache.tika.mime.MediaType
import org.apache.tika.parser.{AbstractParser, ParseContext}
import org.xml.sax.ContentHandler

import scala.reflect.ClassTag

//TODO This is a very rudimentary parser that is unlikely to stand up very well in the real world
//Hopefully Tika includes an ical parser soon
class ICalParser extends AbstractParser {
  private val dateFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)

  override def getSupportedTypes(context: ParseContext): util.Set[MediaType] = Collections.singleton(MediaType.parse(ICalContentType))

  override def parse(stream: InputStream, handler: ContentHandler, metadata: Metadata, context: ParseContext): Unit = {
    getVEventFromCalendar(stream).foreach(component => {
      property[Property](Property.SUMMARY, component).foreach(property => metadata.set(TikaCoreProperties.TITLE, property.getValue))
      property[Property](Property.DESCRIPTION, component).foreach(property => metadata.set(TikaCoreProperties.DESCRIPTION, property.getValue))
      property[Geo](Property.GEO, component).foreach(property => {
        metadata.set(TikaCoreProperties.LATITUDE, property.getLatitude.toString)
        metadata.set(TikaCoreProperties.LONGITUDE, property.getLongitude.toString)
      })
      property[DtStart](Property.DTSTART, component).foreach(property => metadata.set(TikaCoreProperties.METADATA_DATE, dateFormatter.format(property.getDate)))
    })
  }

  private def getVEventFromCalendar(stream: InputStream): Option[Component] =
    Option(new CalendarBuilder().build(stream).getComponents.getComponent(Component.VEVENT))

  private def property[T <: Property : ClassTag](property: String, component: Component): Option[T] =
    Option(component.getProperty(property)).collect({ case result: T => result })
}
