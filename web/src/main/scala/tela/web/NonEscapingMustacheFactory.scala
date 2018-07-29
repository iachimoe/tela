package tela.web

import java.io.Writer

import com.github.mustachejava.DefaultMustacheFactory

//TODO Be more clever about how we test the usage of this...
class NonEscapingMustacheFactory extends DefaultMustacheFactory {
  override def encode(value: String, writer: Writer): Unit = {
    writer.append(value)
  }
}
