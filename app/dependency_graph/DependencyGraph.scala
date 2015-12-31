package dependency_graph

import java.io.{ByteArrayOutputStream, PrintStream}

import sbt.IO
import sbt.Path._

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.sys.process.{Process, ProcessLogger}
import scala.util.control.NonFatal
import scala.xml.{Elem, XML}

object DependencyGraph {
  // https://github.com/jrudolph/sbt-dependency-graph/issues/84
  val pluginSbtContents =
    """addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.7.5")"""

  private[this] val logger = ProcessLogger(println(_))

  def generate(dependencies: Seq[LibraryDependency]): String = {
    IO.withTemporaryDirectory { dir =>
      val project = dir / "project"
      IO.createDirectory(project)
      IO.write(project / "p.sbt", pluginSbtContents)
      IO.write(dir / "build.sbt", ("graphSettings" +: dependencies).mkString("\n\n"))
      val args = new xsbt.boot.LauncherArguments("dependencyDot" :: Nil, false)
      println(xsbt.boot.Launch(dir, args))
      val svg = dir / "graph.svg"
      val dot = dir / "target" / "dependencies-compile.dot"
      if (!dot.isFile) {
        println("failed to write dot file? " + dependencies)
      }
      val dotLines = IO.readLines(dot)
      IO.write(dot, dotLines.filterNot(_.contains("default:sbt_")).mkString("\n"))
      Process(Seq("dot", "-o" + svg.getAbsolutePath, "-Tsvg", dot.getAbsolutePath), dir).!(logger)
      IO.read(svg)
    }
  }

  def withStdOut[A](action: => A): (Option[A], String) = {
    val encode = "UTF-8"
    val outStream = new ByteArrayOutputStream
    sbt.Using.bufferedOutputStream(outStream) { out =>
      val p = new PrintStream(out, true, encode)
      this.synchronized {
        val originalOut = System.out
        val originalErr = System.err
        try {
          System.setOut(p)
          System.setErr(p)
          try {
            val f = Future(action)(ExecutionContext.global)
            val r = Await.result(f, 29.seconds)
            p.flush()
            p.close()
            out.close()
            out.flush()
            Some(r) -> outStream.toString(encode)
          } catch {
            case NonFatal(e) =>
              e.printStackTrace()
              val oo = new ByteArrayOutputStream
              val pp = new PrintStream(oo, true, encode)
              e.printStackTrace(pp)
              None -> oo.toString(encode)
          }
        } finally {
          System.setOut(originalOut)
          System.setErr(originalErr)
          p.close()
        }
      }
    }
  }

  private[this] def metadataXml(baseUrl: String, groupId: String, artifactId: String): Option[Elem] =
    try {
      val url = s"$baseUrl/${groupId.replace('.', '/')}/$artifactId/maven-metadata.xml"
      Some(XML.load(url))
    } catch {
      case _: org.xml.sax.SAXParseException => // ignore
        None
      case NonFatal(e) =>
        e.printStackTrace()
        None
    }

  def versions(baseUrl: String, org: String, name: String): List[String] =
    metadataXml(baseUrl, org, name) match {
      case Some(x) =>
        (x \\ "version").map(_.text).toList.sorted
      case None =>
        Nil
    }

}