package tela.web

import java.io.Writer

import com.github.mustachejava.DefaultMustacheFactory

class NonEscapingMustacheFactory extends DefaultMustacheFactory {
  override def encode(value: String, writer: Writer): Unit = {
    writer.append(value)
  }
}
