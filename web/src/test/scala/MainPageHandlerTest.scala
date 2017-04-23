package tela.web

import java.io.{FileReader, StringWriter}

import akka.actor.ActorRef
import akka.testkit.TestActor
import akka.testkit.TestActor.{KeepRunning, NoAutoPilot}
import org.junit.Assert.{assertEquals, _}
import org.junit.{Before, Test}
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Cookie, Cookies, Result}
import play.api.test.Helpers.{contentAsString, contentType, status}
import play.api.test.{FakeRequest, Helpers}
import tela.baseinterfaces.LoginFailure
import tela.web.SessionManager.{GetSession, Login, Logout}

import scala.collection.JavaConversions._
import scala.concurrent.{Await, Future}
import scala.io.Source

class MainPageHandlerTest extends SessionManagerClientTestBase {
  private val TestPassword = "myPass"
  private val ContentFolder = "web/src/main/html/"
  private val TestAppIndex = "web/src/test/data/appIndex.json"
  private val AppIndexData = Json.parse(Source.fromFile(TestAppIndex).mkString)

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def loginScreenIsDisplayedWhenSessionCookieNotPresent(): Unit = {
    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result: Future[Result] = controller.mainPage().apply(FakeRequest())

    assertResponseContent(result, MainPageController.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  @Test def badLogin(): Unit = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, DefaultLanguage) =>
            sender ! Left(LoginFailure.InvalidCredentials)
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result = controller.handleLogin().apply(FakeRequest().withBody(MainPageController.LoginDetails(TestUsername, TestPassword)))
    assertResponseContent(result, MainPageController.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.BadCredentialsError -> true.toString, MainPageController.UserTemplateKey -> TestUsername))
  }

  @Test def connectionFailed(): Unit = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, DefaultLanguage) =>
            sender ! Left(LoginFailure.ConnectionFailure)
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result = controller.handleLogin().apply(FakeRequest().withBody(MainPageController.LoginDetails(TestUsername, TestPassword)))
    assertResponseContent(result, MainPageController.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.ConnectionFailedError -> true.toString, MainPageController.UserTemplateKey -> TestUsername))
  }

  @Test def redirectToIndexPageAndSetCookieOnSuccessfulLogin(): Unit = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, DefaultLanguage) =>
            sender ! Right(TestSessionId)
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result = controller.handleLogin().apply(FakeRequest().withBody(MainPageController.LoginDetails(TestUsername, TestPassword)))

    assertResponseContent(result, "", Status.FOUND, MainPageController.WebAppRoot, Cookie(SessionIdCookieName, TestSessionId, MainPageController.CookieExpiresWhenBrowserCloses, httpOnly = true))
  }

  @Test def requestWithValidSessionCookieGetsIndexPage(): Unit = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetSession(TestSessionId) =>
            sender ! Some(UserData(TestUsername, DefaultLanguage))
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result = controller.mainPage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertResponseContent(result, IndexPage, ContentFolder,
      getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.UserTemplateKey -> TestUsername,
        MainPageController.AppInfoKey -> getAppsFromIndexData,
        MainPageController.DefaultAppKey -> getDefaultAppFromIndexData,
        MainPageController.LocalizedAppNamesKey -> getLanguageFromIndexData(DefaultLanguage)))
  }

  @Test def requestWithInvalidSessionCookieRedirectsToLoginPage(): Unit = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetSession(TestSessionId) =>
            sender ! None
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result = controller.mainPage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))
    assertResponseContent(result, MainPageController.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  @Test def logout(): Unit = {
    var logoutCalledWithCorrectSessionId = false
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetSession(TestSessionId) =>
            sender ! Some(UserData(TestUsername, DefaultLanguage))
            KeepRunning
          case Logout(TestSessionId) =>
            logoutCalledWithCorrectSessionId = true
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result = controller.mainPage(logout = Some("")).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertResponseContent(result, MainPageController.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.UserTemplateKey -> TestUsername))
    assertTrue(logoutCalledWithCorrectSessionId)
    assertResponseContainsSingleCookie(result, Cookie(SessionIdCookieName, TestSessionId, MainPageController.CookieExpiresNow))
  }

  @Test def alternativeLanguage(): Unit = {
    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result: Future[Result] = controller.mainPage().apply(FakeRequest().withHeaders(HeaderNames.ACCEPT_LANGUAGE -> "es-ES,es;q=0.8,en;q=0.6"))

    assertResponseContent(result, MainPageController.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, "es"))
  }

  @Test def unknownLanguageForLoginPage(): Unit = {
    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result: Future[Result] = controller.mainPage().apply(FakeRequest().withHeaders(HeaderNames.ACCEPT_LANGUAGE -> "XX;q=0.8,en;q=0.6"))

    assertResponseContent(result, MainPageController.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  @Test def preferredLanguageIsSetOnLogin(): Unit = {
    var loginMessageReceived = false
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, "es") =>
            loginMessageReceived = true
            sender ! Left(LoginFailure.InvalidCredentials)
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    Await.result(controller.handleLogin().apply(
      FakeRequest().withBody(MainPageController.LoginDetails(TestUsername, TestPassword)).withHeaders(HeaderNames.ACCEPT_LANGUAGE -> "es-ES,es;q=0.8,en;q=0.6")
    ), timeout.duration)
    assertTrue(loginMessageReceived)
  }

  @Test def preferredLanguageIsEstablishedFromSessionData(): Unit = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetSession(TestSessionId) =>
            sender ! Some(UserData(TestUsername, "es"))
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result = controller.mainPage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)).withHeaders(HeaderNames.ACCEPT_LANGUAGE -> "en;q=0.8"))

    assertResponseContent(result, IndexPage, ContentFolder, getMappingsForLanguage(ContentFolder, "es"),
      Map(MainPageController.UserTemplateKey -> TestUsername,
        MainPageController.AppInfoKey -> getAppsFromIndexData,
        MainPageController.DefaultAppKey -> getDefaultAppFromIndexData,
        MainPageController.LocalizedAppNamesKey -> getLanguageFromIndexData("es")))
  }

  @Test def unknownLanguageInSessionData(): Unit = {
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetSession(TestSessionId) =>
            sender ! Some(UserData(TestUsername, "XX"))
            NoAutoPilot
        }
    })

    val controller = new MainPageController(sessionManagerProbe.ref, ContentFolder, TestAppIndex)
    val result = controller.mainPage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionId)))

    assertResponseContent(result, IndexPage, ContentFolder,
      getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.UserTemplateKey -> TestUsername,
        MainPageController.AppInfoKey -> getAppsFromIndexData,
        MainPageController.DefaultAppKey -> getDefaultAppFromIndexData,
        MainPageController.LocalizedAppNamesKey -> getLanguageFromIndexData(DefaultLanguage)))
  }

  private def assertResponseContent(response: Future[Result], template: String, contentRoot: String, templateMappings: Map[String, String]*): Unit = {
    val mustache = (new NonEscapingMustacheFactory).compile(new FileReader(contentRoot + "/" + template), "")
    val writer = new StringWriter
    mustache.execute(writer, templateMappings.map(mapAsJavaMap).toArray[Object])
    assertResponseContent(response, writer.toString)
  }

  private def assertResponseContent(response: Future[Result], expected: String): Unit = {
    assertEquals(expected, contentAsString(response)(tela.web.timeout))
    assertEquals(TextHtmlContentType, contentType(response)(tela.web.timeout).get)
  }

  private def assertResponseContent(response: Future[Result], expectedBody: String, statusCode: Int, location: String, cookie: Cookie): Unit = {
    assertEquals(expectedBody, contentAsString(response)(tela.web.timeout))
    assertEquals(statusCode, status(response))
    assertEquals(Some(location), Helpers.redirectLocation(response))
    assertResponseContainsSingleCookie(response, cookie)
  }

  private def assertResponseContainsSingleCookie(response: Future[Result], cookie: Cookie): Unit = {
    // Comparison on these Cookies objects is tricky...the code below asserts that the response contains only one cookie,
    // and that it matches the expected cookie
    val cookiesInResponse: Cookies = Helpers.cookies(response)
    assertTrue(cookiesInResponse.get(cookie.name).contains(cookie))
    cookiesInResponse.foreach(responseCookie => assertEquals(cookie, responseCookie))
  }

  private def getMappingsForLanguage(contentFolder: String, lang: String): Map[String, String] = {
    Json.parse(Source.fromFile(contentFolder + "/" + LanguagesFolder + "/" + lang + LanguageFileExtension).mkString).as[Map[String, String]]
  }

  private def getLanguageFromIndexData(language: String): String = {
    (AppIndexData \ MainPageController.LanguagesKeyInIndexHash \ language).as[JsValue].toString()
  }

  private def getDefaultAppFromIndexData: String = {
    (AppIndexData \ MainPageController.DefaultAppKeyInIndexHash).as[String]
  }

  private def getAppsFromIndexData: String = {
    (AppIndexData \ MainPageController.AppsKeyInIndexHash).as[JsValue].toString()
  }
}
