package dependency_graph

import java.net.URL

import org.joda.time.DateTime
import play.api.libs.json.{Json, JsError, JsSuccess}
import play.api.mvc._
import play.core.routing.HandlerInvoker

import scalaz.{\/, -\/, \/-}
import scala.xml.Elem

object Application extends Controller {
  private object Generators {
    val png = GraphGenerator.cached(60, 100, GraphType.PNG)
    val svg = GraphGenerator.svg(60, 100)
    val gif = GraphGenerator.cached(60, 100, GraphType.GIF)
    val dot = GraphGenerator.cached(60, 100, GraphType.DOT)
  }
  private[this] val metadataCache = Cache.create[(String, String), Elem](100)
  private[this] val artifactsCache = Cache.create[String, List[String]](1000)
  private[this] val pomCache = Cache.create[LibraryDependency, Elem](1000)

  private[this] def toResult[A](either: Either[String, A], graphType: GraphType.Aux[A]) =
    either match {
      case Right(p) =>
        graphType.asPlayResult(p)
      case Left(stdout) =>
        InternalServerError(stdout)
    }

  private[this] val methods: List[Map[String, String]] = {
    import scala.language.reflectiveCalls
    val urlKey = "url"
    router.Routes.getClass.getMethods.filter { m =>
      m.getReturnType == classOf[HandlerInvoker[_]] && m.getParameterCount == 0
    }.map { m =>
      m.invoke(router.Routes).asInstanceOf[{def cachedHandlerTags: Map[String, String]}].cachedHandlerTags.collect{
        case ("ROUTE_COMMENTS", value) => ("description", value)
        case ("ROUTE_PATTERN", value) => (urlKey, value)
        case ("ROUTE_VERB", value) => ("method", value)
      }
    }.filterNot{ map =>
      Set("/favicon.ico").exists(ignore => Some(ignore) == map.get(urlKey))
    }.toList
  }

  private[this] val urlListResponse = Ok(Json.toJson(methods))

  val urlList = Action{urlListResponse}

  val post = Action(parse.tolerantJson) { json =>
    json.body.validate[Seq[LibraryDependency]] match {
      case JsSuccess(dependencies, _) =>
        val link = LinkType.Redirect // TODO
        toResult(Generators.svg.get((dependencies, "dependency graph", link), true), GraphType.SVG)
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

  def latest(groupId: String, artifactId: String, useCache: Boolean, format: Option[String], link: Option[String]) = Action {
    GraphType.parseWithDefault(format) match {
      case Right(t) =>
        val xml = if (useCache) {
          metadataWithCache(groupId, artifactId)
        } else {
          DependencyGraph.metadataXmlFromCentral(
            groupId,
            artifactId
          )
        }
        val latestVersion = (xml.toList \ "versioning" \ "latest").text
        if (latestVersion.nonEmpty) {
          val r = t match {
            case GraphType.DOT =>
              routes.Application.dot(groupId, artifactId, latestVersion, useCache)
            case GraphType.GIF =>
              routes.Application.gif(groupId, artifactId, latestVersion, useCache)
            case GraphType.PNG =>
              routes.Application.png(groupId, artifactId, latestVersion, useCache)
            case GraphType.SVG =>
              val l = link.flatMap(LinkType.unapply).map(_.value)
              routes.Application.svg(groupId, artifactId, latestVersion, useCache, l)
          }
          Redirect(r)
        } else {
          NotFound(<p>
            {"not found latest version of "}<pre>{s""""$groupId" % "$artifactId""""}</pre>
          </p>).as(HTML)
        }
      case Left(invalid) =>
        BadRequest(s"invalid format $invalid")
    }
  }

  def graph(groupId: String, artifactId: String, version: String, useCache: Boolean, link: Option[String]) =
    svg(groupId, artifactId, version, useCache, link)

  def run[A](l: LibraryDependency, useCache: Boolean, generator: GraphGenerator[(Seq[LibraryDependency], String), A], graphType: GraphType.Aux[A], link: LinkType = LinkType.Redirect) = Action{
    val title = s"${l.groupId}/${l.artifactId}/${l.version} dependency graph"
    val result = generator.get((l :: Nil, title), useCache)
    toResult(result, graphType)
  }

  def gif(groupId: String, artifactId: String, version: String, useCache: Boolean) =
    run(LibraryDependency(groupId, artifactId, version), useCache, Generators.gif, GraphType.GIF)

  def svg(groupId: String, artifactId: String, version: String, useCache: Boolean, link: Option[String]) = Action{
    LinkType.from(link) match {
      case Right(l) =>
        val dep = LibraryDependency(groupId, artifactId, version)
        val title = s"${dep.groupId}/${dep.artifactId}/${dep.version} dependency graph"
        val result = Generators.svg.get((dep :: Nil, title, l), useCache)
        toResult(result, GraphType.SVG)
      case Left(invalid) =>
        BadRequest(s"invalid link parameter $invalid")
    }
  }

  def png(groupId: String, artifactId: String, version: String, useCache: Boolean) =
    run(LibraryDependency(groupId, artifactId, version), useCache, Generators.png, GraphType.PNG)

  def dot(groupId: String, artifactId: String, version: String, useCache: Boolean) =
    run(LibraryDependency(groupId, artifactId, version), useCache, Generators.dot, GraphType.DOT)

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

    if(list.nonEmpty) {
      val urlList = {
        <li>
          <a href={routes.Application.latest(groupId, artifactId, useCache, GraphType.SVG.idOpt, None).url} target="_blank">latest</a>
        </li>
      } :: {
        list.map { v =>
          <li>
            <a href={routes.Application.graph(groupId, artifactId, v, true, Some(LinkType.Redirect.value)).url} target="_blank">
              {v}
            </a>
          </li>
        }
      }

      val result = <html>
        <body>
          <ul>
            {urlList}
          </ul>
        </body>
      </html>
      Ok(result).as(HTML)
    } else {
      val result = <p>{"Not found. See "}<a href={routes.Application.artifacts(groupId).url}>artifacts for "{groupId}"</a></p>
      NotFound(result).as(HTML)
    }
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
            <li><a href={routes.Application.versions(groupId, a, cache).url} target="_blank">{a}</a></li>
          }
        }</ul>).as(HTML)
      case -\/(error) =>
        BadRequest(error.toString)
    }
  }

  val favicon = Action{
    Ok("").as(BINARY)
  }

  def gist(id: String, format: Option[String], link: Option[String]) = Action{
    Gist.fetch(id) match {
      case \/-(gist) =>
        gist.files.get("build.sbt") match {
          case Some(buildFile) =>
            GraphType.parseWithDefault(format) match {
              case Right(GraphType.SVG) =>
                LinkType.from(link) match {
                  case Right(l) =>
                    val graph = DependencyGraph.svgFromSettings(
                      plainBuildSettings = buildFile.content,
                      title = gist.description,
                      link = l,
                      filterRoot = false
                    )
                    GraphType.SVG.asPlayResult(graph)
                  case Left(invalid) =>
                    BadRequest(s"invalid link type $invalid")
                }
              case Right(tpe) =>
                val graph: tpe.A = DependencyGraph.generateFrom(
                  title = gist.description,
                  additionalBuildSettings = buildFile.content,
                  graphType = tpe,
                  filterRoot = false
                )
                tpe.asPlayResult(graph)
              case Left(invalid) =>
                BadRequest(s"invalid format $invalid")
            }
          case None =>
            NotFound("could not found `build.sbt`")
        }
      case -\/(error) =>
        error.printStackTrace()
        BadRequest(error.toString)
    }
  }
}
