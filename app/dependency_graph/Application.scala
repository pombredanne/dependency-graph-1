package dependency_graph

import java.net.URL

import org.joda.time.DateTime
import play.api.libs.json.{JsError, JsSuccess}
import play.api.mvc._

import scalaz.{\/, -\/, \/-}
import scala.xml.Elem

object Application extends Controller {
  private[this] val cache = Cache.create[Set[LibraryDependency], String](100)
  private[this] val metadataCache = Cache.create[(String, String), Elem](100)
  private[this] val artifactsCache = Cache.create[String, List[String]](1000)
  private[this] val pomCache = Cache.create[LibraryDependency, Elem](1000)

  private[this] def toResult(either: Either[String, String]) =
    either match {
      case Right(svg) =>
        println("cache = " + cache)
        Ok(svg).as("image/svg+xml")
      case Left(stdout) =>
        InternalServerError(stdout)
    }

  val post = Action(parse.tolerantJson) { json =>
    json.body.validate[Set[LibraryDependency]] match {
      case JsSuccess(dependencies, _) =>
        toResult(run(dependencies))
      case e: JsError =>
        BadRequest(e.toString)
    }
  }

  def redirectProjectPage(g: String, a: String, v: String, useCache: Boolean) = Action {
    val l = LibraryDependency(g, a, v)
    val pom = if (useCache) {
      pomCache.getOrElseUpdate(
        l,
        scala.xml.XML.load(new URL(l.pomURL)),
        DateTime.now().plusMillis(120)
      )
    } else {
      scala.xml.XML.load(new URL(l.pomURL))
    }
    val url = DependencyGraph.findURL(pom).getOrElse(l.pomURL)
    Redirect(url)
  }

  def latest(groupId: String, artifactId: String, useCache: Boolean) = Action {
    val xml = if(useCache) {
      metadataWithCache(groupId, artifactId)
    } else {
      DependencyGraph.metadataXmlFromCentral(
        groupId,
        artifactId
      )
    }
    val latestVersion = (xml.toList \ "versioning" \ "latest").text
    if(latestVersion.nonEmpty) {
      Redirect(routes.Application.graph(groupId, artifactId, latestVersion, useCache))
    } else {
      NotFound(
        <p>{"not found latest version of "}<pre>{s""""$groupId" % "$artifactId""""}</pre></p>
      ).as(HTML)
    }
  }

  def graph(g: String, a: String, v: String, useCache: Boolean) = Action {
    val set = Set(LibraryDependency(g, a, v))
    val result = if (useCache) {
      cache.get(set).map(Right(_)).getOrElse {
        run(set).right.map { svg =>
          cache.getOrElseUpdate(set, svg, DateTime.now().plusMinutes(30))
        }
      }
    } else {
      run(set)
    }
    toResult(result)
  }

  private[this] def metadataWithCache(groupId: String, artifactId: String) = {
    val key = (groupId, artifactId)
    metadataCache.get(key).orElse{
      DependencyGraph.metadataXmlFromCentral(
        groupId,
        artifactId
      ).map{ x =>
        metadataCache.put(key, x, DateTime.now().plusMinutes(30))
        x
      }
    }
  }

  def versions(groupId: String, artifactId: String, useCache: Boolean) = Action {
    val xml = if(useCache){
      metadataWithCache(groupId, artifactId).toList
    }else{
      DependencyGraph.metadataXmlFromCentral(groupId, artifactId).toList
    }
    val list = (xml \\ "version").map(_.text).toList.sorted

    val urlList = {
      <li>
        <a href={routes.Application.latest(groupId, artifactId, useCache).url} target="_blank">latest</a>
      </li>
    } :: {
      list.map { v =>
        <li>
          <a href={s"http://dependency-graph.herokuapp.com/$groupId/$artifactId/$v"} target="_blank">
            {v}
          </a>
        </li>
      }
    }

    val result = <html>
      <body>
        <ul>{urlList}</ul>
      </body>
    </html>
    Ok(result).as(HTML)
  }

  def artifacts(groupId: String, cache: Boolean) = Action{
    val result = if(cache) {
      artifactsCache.get(groupId).map(\/.right).getOrElse{
        MavenSearch.searchByGroupId(groupId).map{ a =>
          artifactsCache.put(groupId, a, DateTime.now().plusMinutes(60))
          a
        }
      }
    } else {
      MavenSearch.searchByGroupId(groupId)
    }

    result match {
      case \/-(res) =>
        Ok(<ul>{
          res.map{ a =>
            <li><a href={s"http://dependency-graph.herokuapp.com/$groupId/$a"} target="_blank">{a}</a></li>
          }
        }</ul>).as(HTML)
      case -\/(error) =>
        BadRequest(error.toString)
    }
  }

  def run(dependencies: Set[LibraryDependency]): Either[String, String] =
    DependencyGraph.withStdOut {
      DependencyGraph.generate(dependencies.toSeq)
    } match {
      case (Some(svg), _) =>
        cache.put(dependencies, svg, DateTime.now().plusMinutes(30))
        Right(svg)
      case (None, stdout) =>
        Left(stdout)
    }

}
