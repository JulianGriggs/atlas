/*
 * Copyright 2014-2016 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.netflix.atlas.wiki

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.regex.Pattern

import akka.actor.ActorSystem
import akka.actor.Props
import com.netflix.atlas.akka.RequestHandlerActor
import com.netflix.atlas.core.model.DataVocabulary
import com.netflix.atlas.core.model.FilterVocabulary
import com.netflix.atlas.core.model.MathVocabulary
import com.netflix.atlas.core.model.QueryVocabulary
import com.netflix.atlas.core.model.StatefulVocabulary
import com.netflix.atlas.core.model.StyleVocabulary
import com.netflix.atlas.core.stacklang.StandardVocabulary
import com.netflix.atlas.core.stacklang.Vocabulary
import com.netflix.atlas.core.util.Streams._
import com.netflix.atlas.json.Json
import com.netflix.atlas.webapi.ApiSettings
import com.netflix.atlas.webapi.LocalDatabaseActor
import com.netflix.atlas.wiki.pages._
import com.netflix.iep.service.DefaultClassFactory
import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging

import scala.concurrent.Await
import scala.concurrent.duration.Duration


/**
 * Simple script for processing the wiki docs. Custom pages can be generated by creating a simple
 * class. Markdown pages can include a line that starts with `/api/v1/graph` to include a
 * rendered image using the graph api and a formatted expression.
 */
object Main extends StrictLogging {

  type ListBuilder = scala.collection.mutable.Builder[String, List[String]]

  val GraphImage = """(.*)<img[^><]+src="([^"]+)"[^><]+>(.*)""".r

  val config = ConfigFactory.load()
  val system = ActorSystem("wiki")
  val db = ApiSettings.newDbInstance
  system.actorOf(Props(new LocalDatabaseActor(db)), "db")
  val webApi = system.actorOf(Props(new RequestHandlerActor(config, new DefaultClassFactory())))

  val vocabs = List(
    StandardVocabulary,
    QueryVocabulary,
    DataVocabulary,
    MathVocabulary,
    StatefulVocabulary,
    FilterVocabulary,
    StyleVocabulary
  )

  val vocabDocs = Map(
    "std" ->
      """
        |Standard operations for manipulating the stack.
      """.stripMargin,
    "query" ->
      """
        |Query expression used to select a set of time series. For more information see the
        |[stack language tutorial](Stack-Language#query).
      """.stripMargin,
    "data" ->
      """
        |Expression for how to get data from the underlying storage. This is the minimal set that
        |a storage layer would need to support. For more information see the
        |[stack language tutorial](Stack-Language#aggregation).
      """.stripMargin,
    "math" ->
      """
        |Defines mathematical operators to transform or combine time series. The base set can be
        |supported in a global or online streaming context. For more information see the
        |[stack language tutorial](Stack-Language#math).
      """.stripMargin,
    "stateful" ->
      """
        |Mathematical operations that require state, i.e., data from previous time intervals to
        |compute the result. May not be supported in all contexts.
      """.stripMargin,
    "filter" ->
      """
        |Mathematical operations that require all data across the time being considered to
        |compute. These are typically used for filtering after the fact. Only supported in a
        |global evaluation context.
      """.stripMargin,
    "style" ->
      """
        |Applies presentation attributes to the data. For more information see the
        |[stack language tutorial](Stack-Language#presentation).
      """.stripMargin
  )

  val overrides = Map(
    DesEpicSignal.word -> DesEpicSignal,
    DesEpicViz.word    -> DesEpicViz,
    DesFast.word       -> DesFast,
    DesSlow.word       -> DesSlow,
    DesSlower.word     -> DesSlower,
    DesSimple.word     -> DesSimple,
    SDesFast.word      -> SDesFast,
    SDesSlow.word      -> SDesSlow,
    SDesSlower.word    -> SDesSlower,
    DistStddev.word    -> DistStddev
  )

  private def writeFile(data: String, f: File): Unit = {
    scope(fileOut(f)) { _.write(data.getBytes("UTF-8")) }
  }

  @scala.annotation.tailrec
  private def process(lines: List[String], output: ListBuilder, graph: GraphHelper): List[String] = {
    lines match {
      case v :: vs if v.trim.startsWith("/api/v1/graph") =>
        output += graph.image(v)
        process(vs, output, graph)
      case GraphImage(pre, v, post) :: vs if v.trim.startsWith("/api/v1/graph") =>
        output += (pre + graph.imageHtml(v) + post)
        process(vs, output, graph)
      case v :: vs =>
        output += v
        process(vs, output, graph)
      case Nil =>
        output.result()
    }
  }

  private def processTemplate(f: File, output: File): Unit = {
    val path = s"${output.getName}/gen-images"
    val graph = new GraphHelper(webApi, new File(output, "gen-images"), path)
    val template = scope(fileIn(f)) { in => lines(in).toList }
    val processed = process(template, List.newBuilder[String], graph)
    writeFile(processed.mkString("\n"), new File(output, f.getName))
  }

  private def copyVerbatim(f: File, output: File): Unit = {
    logger.info(s"copy verbatim: $f to $output")
    copyVerbatim(fileIn(f), fileOut(new File(output, f.getName)))
  }

  private def copyVerbatim(fin: InputStream, fout: OutputStream): Unit = {
    scope(fout) { out =>
      scope(fin) { in =>
        val buf = new Array[Byte](4096)
        var length = in.read(buf)
        while (length > 0) {
          out.write(buf, 0, length)
          length = in.read(buf)
        }
      }
    }
  }

  private def copy(input: File, output: File): Unit = {
    if (!output.exists) {
      logger.info(s"creating directory: $output")
      output.mkdir()
    }
    require(output.isDirectory, s"could not find or create directory: $output")
    input.listFiles.foreach {
      case f if f.isDirectory             => copy(f, new File(output, f.getName))
      case f if f.getName.endsWith(".md") => processTemplate(f, output)
      case f                              => copyVerbatim(f, output)
    }
  }

  private def generateStackLangRef(output: File): Unit = {
    val dir = new File(output, "stacklang")
    dir.mkdirs()

    val graph = new GraphHelper(webApi, new File(dir, "gen-images"), "stacklang/gen-images")

    val sidebar = new StringBuilder
    vocabs.foreach { vocab =>
      sidebar.append(s"* [${vocab.name}](Reference-${vocab.name})\n")
    }
    writeFile(sidebar.toString(), new File(dir, "_Sidebar.md"))

    vocabs.foreach { v => generateVocabRef(dir, graph, v) }
  }

  def generateVocabRef(output: File, graph: GraphHelper, vocab: Vocabulary): Unit = {
    val dir = new File(output, vocab.name)
    dir.mkdirs()

    val header = s"> [[Home]] ▸ [[Stack Language Reference]] ▸ __${vocab.name}__\n\n"

    writeFile(header + vocabDocs(vocab.name), new File(dir, s"Reference-${vocab.name}.md"))

    val sidebar = new StringBuilder
    vocabs.foreach { v =>
      if (v.name == vocab.name) {
        sidebar.append(s"* __${v.name}__\n")
        vocab.words.sortWith(_.name < _.name).foreach { w =>
          val page = overrides.getOrElse(w, BasicStackWordPage(vocab, w))
          val fname = page.name
          sidebar.append(s"    * [${w.name}]($fname)\n")
        }
      } else {
        sidebar.append(s"* [${v.name}](Reference-${v.name})\n")
      }
    }
    writeFile(sidebar.toString(), new File(dir, "_Sidebar.md"))

    vocab.words.sortWith(_.name < _.name).foreach { w =>
      val page = overrides.getOrElse(w, BasicStackWordPage(vocab, w))
      val fname = page.name
      val f = new File(dir, s"$fname.md")
      writeFile(header + page.content(graph), f)
    }
  }

  def generateScriptedPages(output: File, pages: List[Page]): Unit = {
    val graph = new GraphHelper(webApi, new File(output, "gen-images"), "gen-images")
    pages.foreach { p => writeFile(p.content(graph), p.file(output)) }
  }

  private def listFiles(f: File): List[File] = {
    if (f.isDirectory) f.listFiles().flatMap(listFiles).toList else List(f)
  }

  private def sectionDocs(name: String, text: String): List[Document] = {
    val lines = text.split("\n")
    val pattern = Pattern.compile("""^#+\s+(.+)$""")

    val sections = List.newBuilder[Document]
    var title = null.asInstanceOf[String]
    val buffer = new StringBuilder
    lines.foreach { line =>
      val matcher = pattern.matcher(line)
      if (matcher.matches()) {
        if (title != null) {
          sections += Document(toLink(name, Some(title)), buffer.toString(), title)
        }
        title = matcher.group(1)
        buffer.clear()
      } else {
        buffer.append(line).append('\n')
      }
    }

    sections.result()
  }

  private def toLink(fname: String, title: Option[String] = None): String = {
    val href = fname.replace(".md", "")
    title.fold(href) { t =>
      val anchor = t.trim.toLowerCase(Locale.US)
        .replace(' ', '-')
        .replaceAll("[^-a-z0-9]", "")
      s"$href#$anchor"
    }
  }

  private def toTitle(fname: String): String = {
    fname.replace(".md", "").replace('-', ' ')
  }

  def generateSearchIndex(output: File): Unit = {
    val files = listFiles(output).filter { f =>
      val n = f.getName
      n.endsWith(".md") && !n.startsWith("_")
    }
    val docs = files.flatMap { file =>
      val text = new String(scope(fileIn(file))(byteArray), StandardCharsets.UTF_8)
      val loc = toLink(file.getName)
      val title = toTitle(file.getName)
      Document(loc, text, title) :: sectionDocs(file.getName, text)
    }
    val json = Json.encode(Index(docs))
    scope(fileOut(new File(output, "search_index.json"))) { out =>
      out.write(json.getBytes(StandardCharsets.UTF_8))
    }
  }

  def main(args: Array[String]): Unit = {
    try {
      if (args.length != 2) {
        System.err.println("Usage: Main <input-dir> <output-dir>")
        System.exit(1)
      }

      val input = new File(args(0))
      require(input.isDirectory, s"input-dir is not a directory: $input")

      val output = new File(args(1))
      output.mkdirs()
      require(output.isDirectory, s"could not find or create output directry: $output")

      copy(input, output)

      generateStackLangRef(output)
      generateScriptedPages(output, List(
        new DES,
        new StackLanguageReference(vocabs, vocabDocs),
        new TimeZones
      ))
      generateSearchIndex(output)
    } finally {
      Await.ready(system.terminate(), Duration.Inf)
    }
  }


  case class Index(docs: List[Document])
  case class Document(location: String, text: String, title: String)
}
