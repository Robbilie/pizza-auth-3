package moe.pizza.auth.webapp

import moe.pizza.auth.config.ConfigFile.ConfigFile
import moe.pizza.auth.graphdb.EveMapDb
import moe.pizza.auth.interfaces.{PilotGrader, UserDatabase}
import moe.pizza.auth.models.Pilot
import moe.pizza.auth.webapp.Types.{Session2, Session}
import moe.pizza.crestapi.CrestApi
import org.http4s.{HttpService, _}
import org.http4s.dsl.{Root, _}
import org.http4s.server._
import org.http4s.server.staticcontent.ResourceService
import org.http4s.server.syntax.ServiceOps
import play.twirl.api.Html
import moe.pizza.eveapi._
import scala.concurrent.ExecutionContext.Implicits.global
import org.http4s.twirl._
import scala.util.Try
import scalaz._
import Scalaz._
import scala.util.{Success => TSuccess}
import scala.util.{Failure => TFailure}


import scalaz.\/-

object NewWebapp {
  val PILOT = "pilot"
  val defaultCrestScopes = List("characterLocationRead", "characterAccountRead", "characterSkillsRead")
}

class NewWebapp(fullconfig: ConfigFile, graders: PilotGrader, portnumber: Int = 9021, ud: UserDatabase, crestapi: Option[CrestApi] = None, eve: Option[EVEAPI] = None, mapper: Option[EveMapDb] = None) {

  val log = org.log4s.getLogger
  val config = fullconfig.crest
  val groupconfig = fullconfig.auth.groups

  val crest = crestapi.getOrElse(new CrestApi(baseurl = config.loginUrl, cresturl = config.crestUrl, config.clientID, config.secretKey, config.redirectUrl))
  val eveapi = eve.getOrElse(new EVEAPI())
  val map = mapper.getOrElse {
    // make the graph database in the webapp for the MVP version
    val map = new EveMapDb("internal-map")
    // initialise the database
    map.provisionIfRequired()
    map
  }



  def staticrouter = staticcontent.resourceService(ResourceService.Config("/static/static/", "/static/"))

  import Utils._

  /*
  def dynamicrouter = HttpService {
    case req@GET -> Root => {
      val newsession = req.flash(Alerts.info, "hi, you have a session")
      Ok(templates.html.base("test-page", templates.html.landing(), newsession, None))
        .attachSessionifDefined(newsession)
    }
  }
  */

  implicit class ConvertableSession2(s: Session2) {
    def toNormalSession = new Session(s.alerts)
  }

  def dynamicWebRouter = HttpService {
    case req@GET -> Root => {
      req.getSession match {
        case Some(s) =>
          s.pilot match {
            case Some(pilot) =>
              Ok(
                templates.html.base(
                  "pizza-auth-3",
                  templates.html.main(),
                  req.getSession.map(_.toNormalSession),
                  req.getSession.flatMap(_.pilot)
                )
              ).attachSessionifDefined(req.getSession.map(_.copy(alerts = List())))
            case None =>
              Ok(templates.html.base(
                "pizza-auth-3",
                templates.html.landing(),
                req.getSession.map(_.toNormalSession),
                None
              )).attachSessionifDefined(req.getSession.map(_.copy(alerts = List())))
          }
        case None =>
          InternalServerError(templates.html.base("pizza-auth-3", Html("An error occurred with the session handler"), None, None))
      }
    }
    case req@GET -> Root / "login" => {
      Uri.fromString(crest.redirect("login", NewWebapp.defaultCrestScopes)) match {
        case \/-(url) => TemporaryRedirect(url)
        case _ => InternalServerError("unable to construct url")
      }
    }
    case req@GET -> Root / "signup" => {
      Uri.fromString(crest.redirect("signup", NewWebapp.defaultCrestScopes)) match {
        case \/-(url) => TemporaryRedirect(url)
        case _ => InternalServerError("unable to construct url")
      }
    }
    case req@GET -> Root / "logout" => {
      TemporaryRedirect(Uri(path = "/"))
        .clearSession()
    }

    case req@GET -> Root / "signup" / "confirm" => {
      req.getSession.flatMap(_.pilot) match {
        case Some(p) =>
          Ok(templates.html.base("pizza-auth-3", templates.html.signup(p), req.getSession.map(_.toNormalSession), req.getSession.flatMap(_.pilot)))
        case None =>
          TemporaryRedirect(Uri(path = "/"))
      }
    }


    case req@POST -> Root / "signup" / "confirm" => {
      req.getSession.flatMap(_.pilot) match {
        case Some(p) =>
          req.decode[UrlForm] { data =>
            val newemail = data.getFirstOrElse("email", "none")
            val pilotwithemail = p.copy(email = newemail)
            val password = data.getFirst("password").get
            val res = ud.addUser(pilotwithemail, password)
            TemporaryRedirect(Uri(path = "/"))
                .attachSessionifDefined(
                  req.flash(Alerts.success, s"Successfully created and signed in as ${p.uid}")
                )
          }
        case None =>
          TemporaryRedirect(Uri(path = "/"))
      }
    }

    case req@GET -> Root / "groups" => {
      req.getSession.flatMap(_.pilot) match {
        case Some(p) =>
          val groups = fullconfig.auth.groups
          Ok(templates.html.base("pizza-auth-3", templates.html.groups(p, groups.closed, groups.open), req.getSession.map(_.toNormalSession), req.getSession.flatMap(_.pilot)))
        case None =>
          TemporaryRedirect(Uri(path = "/"))
      }
    }

    case req@GET -> Root / "groups" / "apply" / group => {
      val goback = TemporaryRedirect(Uri(path = "/groups"))
      req.sessionResponse { (s: Session2, p: Pilot) =>
        // TODO extend for public users
        group match {
          case g if groupconfig.open.contains(g) =>
            ud.updateUser(p.copy(authGroups = p.authGroups :+ group)) match {
              case true =>
                goback.attachSessionifDefined(req.flash(Alerts.success, s"Joined $group"))
              case false =>
                goback.attachSessionifDefined(req.flash(Alerts.warning, s"Unable to join $group"))
            }
          case g if groupconfig.closed.contains(g) =>
            ud.updateUser(p.copy(authGroups = p.authGroups :+ s"$group-pending" )) match {
              case true =>
                goback.attachSessionifDefined(req.flash(Alerts.success, s"Applied to $group"))
              case false =>
                goback.attachSessionifDefined(req.flash(Alerts.warning, s"Unable to join $group"))
            }
          case _ =>
            goback.attachSessionifDefined(req.flash(Alerts.warning, s"Unable to find a group named $group"))
        }

      }
    }

    case req@GET -> Root / "groups" / "remove" / group => {
      val goback = TemporaryRedirect(Uri(path = "/groups"))
      req.sessionResponse { (s: Session2, p: Pilot) =>
        // TODO extend for public users
        group match {
          case g if p.authGroups.contains(g) =>
            ud.updateUser(p.copy(authGroups = p.authGroups.filter(_ != null) )) match {
              case true =>
                goback.attachSessionifDefined(req.flash(Alerts.success, s"Joined $group"))
              case false =>
                goback.attachSessionifDefined(req.flash(Alerts.warning, s"Unable to join $group"))
            }
          case _ =>
            goback.attachSessionifDefined(req.flash(Alerts.warning, s"You are not in a group named $group"))
        }
      }
    }


    case req@GET -> Root / "callback" => {
      val code = req.params("code")
      val state = req.params("state")
      val callbackresults = crest.callback(code).sync()
      val verify = crest.verify(callbackresults.access_token).sync()
      state match {
        case "signup" =>
          val charinfo = eveapi.eve.CharacterInfo(verify.characterID.toInt)
          val pilot = charinfo.map { ci =>
            val refresh = crest.refresh(callbackresults.refresh_token.get).sync()
            ci match {
              case Right(r) =>
                // has an alliance
                new Pilot(
                  Utils.sanitizeUserName(r.result.characterName),
                  Pilot.Status.unclassified,
                  r.result.alliance,
                  r.result.corporation,
                  r.result.characterName,
                  "none@none",
                  Pilot.OM.createObjectNode(),
                  List.empty[String],
                  List("%d:%s".format(r.result.characterID, refresh.refresh_token.get)),
                  List.empty[String]
                )
              case Left(l) =>
                // does not have an alliance
                new Pilot(
                  Utils.sanitizeUserName(l.result.characterName),
                  Pilot.Status.unclassified,
                  "",
                  l.result.corporation,
                  l.result.characterName,
                  "none@none",
                  Pilot.OM.createObjectNode(),
                  List.empty[String],
                  List("%d:%s".format(l.result.characterID, refresh.refresh_token.get)),
                  List.empty[String]
                )

            }
          }
          Try {
            pilot.sync()
          } match {
            case TSuccess(p) =>
              // grade the pilot
              val gradedpilot = p.copy(accountStatus = graders.grade(p))
              // mark it as ineligible if it fell through
              val gradedpilot2 = if (gradedpilot.accountStatus == Pilot.Status.unclassified) {
                gradedpilot.copy(accountStatus = Pilot.Status.ineligible)
              } else {
                gradedpilot
              }
              // store it and forward them on
              TemporaryRedirect(Uri(path = "/signup/confirm")).attachSessionifDefined(
                req.getSession.map(_.copy(pilot = Some(gradedpilot)))
              )
            case TFailure(f) =>
              val newsession = req.flash(Alerts.warning, "Unable to unpack CREST response, please try again later")
              TemporaryRedirect(Uri(path = "/")).attachSessionifDefined(newsession)
          }
        case "add" =>
          TemporaryRedirect(Uri(path = "/"))
        case "login" =>
          val uid = Utils.sanitizeUserName(verify.characterName)
          val pilot = ud.getUser(uid)
          val session = pilot match {
            case Some(p) =>
              req.flash(Alerts.success, "Thanks for logging in %s".format(verify.characterName))
                .map(_.copy(pilot = Some(p)))
            case None =>
              req.flash(Alerts.warning,
                "Unable to find a user associated with that EVE character, please sign up or use another character")
          }
          TemporaryRedirect(Uri(path = "/")).attachSessionifDefined(session)
        case _ =>
          TemporaryRedirect(Uri(path = "/"))
      }
    }
  }

  val secretKey = "SECRET IS GOING HERE"
  //UUID.randomUUID().toString
  val sessions = new SessionManager(secretKey)

  def router = staticrouter orElse sessions(dynamicWebRouter)

}