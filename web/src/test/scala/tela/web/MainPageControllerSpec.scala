package tela.web

import java.io.StringWriter
import java.nio.file.{Files, Path, Paths}
import org.apache.pekko.actor.ActorRef
import org.apache.pekko.testkit.TestActor.NoAutoPilot
import org.scalatest.matchers.should.Matchers._
import play.api.http.{HeaderNames, Status}
import play.api.libs.json.JsValue
import play.api.mvc.{Cookie, Result}
import play.api.test.FakeRequest
import tela.baseinterfaces.LoginFailure
import tela.web.SessionManager.{GetSession, Login, Logout}

import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Await, Future}

class MainPageControllerSpec extends SessionManagerClientBaseSpec {
  private val ContentFolder = Paths.get("web/src/main/html/")
  private val TestAppIndex = Paths.get("web/src/test/data/appIndex.json")
  private val AppIndexData = JsonFileHelper.getContents(TestAppIndex)

  private def testEnvironment(runTest: TestEnvironment[MainPageController] => Unit): Unit = {
    runTest(createTestEnvironment((sessionManager, _) =>
      new MainPageController(sessionManager, ContentFolder, TestAppIndex,
        controllerComponents(), createMockAssetsFinder())))
  }

  "mainPage" should "display login screen when session cookie is not present" in testEnvironment { environment =>
    val result: Future[Result] = environment.client.mainPage().apply(FakeRequest())
    assertResponseContent(result, MainPageController.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  it should "display an appropriate error on receipt of invalid credentials for login request" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case Login(TestUsername, TestPassword, DefaultLanguage) =>
        sender ! Left(LoginFailure.InvalidCredentials)
        NoAutoPilot
    })

    val result = environment.client.handleLogin().apply(FakeRequest().withBody(MainPageController.LoginDetails(TestUsername, TestPassword)))
    assertResponseContent(result, MainPageController.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.BadCredentialsError -> true.toString, MainPageController.UserTemplateKey -> TestUsername))
  }

  it should "display an appropriate error when the XMPP server is down" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case Login(TestUsername, TestPassword, DefaultLanguage) =>
        sender ! Left(LoginFailure.ConnectionFailure)
        NoAutoPilot
    })

    val result = environment.client.handleLogin().apply(FakeRequest().withBody(MainPageController.LoginDetails(TestUsername, TestPassword)))
    assertResponseContent(result, MainPageController.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.ConnectionFailedError -> true.toString, MainPageController.UserTemplateKey -> TestUsername))
  }

  it should "redirect to index page and set cookie on successful login" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case Login(TestUsername, TestPassword, DefaultLanguage) =>
        sender ! Right(TestSessionId)
        NoAutoPilot
    })

    val result = environment.client.handleLogin().apply(FakeRequest().withBody(MainPageController.LoginDetails(TestUsername, TestPassword)))

    assertResponseContent(result, "", Status.FOUND, MainPageController.WebAppRoot, Cookie(SessionIdCookieName, TestSessionIdAsString, MainPageController.CookieExpiresWhenBrowserCloses, httpOnly = true))
  }

  it should "display home page for request with valid session cookie" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case GetSession(TestSessionId) =>
        sender ! Some(UserData(TestUsername, DefaultLanguage))
        NoAutoPilot
    })

    val result = environment.client.mainPage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    assertResponseContent(result, IndexPage, ContentFolder,
      getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.UserTemplateKey -> TestUsername,
        MainPageController.AppInfoKey -> getAppsFromIndexData,
        MainPageController.DefaultAppKey -> getDefaultAppFromIndexData,
        MainPageController.LocalizedAppNamesKey -> getLanguageFromIndexData(DefaultLanguage)))
  }

  it should "redirect to login page on receipt of request with invalid session cookie" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = false)

    val result = environment.client.mainPage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))
    assertResponseContent(result, MainPageController.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  it should "display login page using the language requested by the browser" in testEnvironment { environment =>
    environment.configureTestProbe()
    val result: Future[Result] = environment.client.mainPage().apply(FakeRequest().withHeaders(HeaderNames.ACCEPT_LANGUAGE -> s"$SpanishLanguageCode-ES,es;q=0.8,en;q=0.6"))

    assertResponseContent(result, MainPageController.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, SpanishLanguageCode))
  }

  it should "display login page using the default language if an unknown language is requested by the browser" in testEnvironment { environment =>
    environment.configureTestProbe()
    val result: Future[Result] = environment.client.mainPage().apply(FakeRequest().withHeaders(HeaderNames.ACCEPT_LANGUAGE -> s"$UnknownLanguageCode;q=0.8,en;q=0.6"))

    assertResponseContent(result, MainPageController.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  it should "set the preferred language in the session manager on login" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case Login(TestUsername, TestPassword, SpanishLanguageCode) =>
        sender ! Right(TestSessionId)
        NoAutoPilot
    })

    Await.result(environment.client.handleLogin().apply(
      FakeRequest().withBody(MainPageController.LoginDetails(TestUsername, TestPassword)).withHeaders(HeaderNames.ACCEPT_LANGUAGE -> s"$SpanishLanguageCode-ES,es;q=0.8,en;q=0.6")
    ), GeneralTimeoutAsDuration)
    environment.sessionManagerProbe.expectMsg(Login(TestUsername, TestPassword, SpanishLanguageCode))
  }

  it should "prefer the language set in the session data of a logged in user to that specified by the browser" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case GetSession(TestSessionId) =>
        sender ! Some(UserData(TestUsername, SpanishLanguageCode))
        NoAutoPilot
    })

    val result = environment.client.mainPage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)).withHeaders(HeaderNames.ACCEPT_LANGUAGE -> "en;q=0.8"))

    assertResponseContent(result, IndexPage, ContentFolder, getMappingsForLanguage(ContentFolder, SpanishLanguageCode),
      Map(MainPageController.UserTemplateKey -> TestUsername,
        MainPageController.AppInfoKey -> getAppsFromIndexData,
        MainPageController.DefaultAppKey -> getDefaultAppFromIndexData,
        MainPageController.LocalizedAppNamesKey -> getLanguageFromIndexData(SpanishLanguageCode)))
  }

  it should "use the default language is the session language is set to an unknown value (should never happen)" in testEnvironment { environment =>
    environment.configureTestProbe((sender: ActorRef) => {
      case GetSession(TestSessionId) =>
        sender ! Some(UserData(TestUsername, UnknownLanguageCode))
        NoAutoPilot
    })

    val result = environment.client.mainPage().apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    assertResponseContent(result, IndexPage, ContentFolder,
      getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.UserTemplateKey -> TestUsername,
        MainPageController.AppInfoKey -> getAppsFromIndexData,
        MainPageController.DefaultAppKey -> getDefaultAppFromIndexData,
        MainPageController.LocalizedAppNamesKey -> getLanguageFromIndexData(DefaultLanguage)))
  }

  "logout" should "send logout request to session manager, expire session cookie and display login page" in testEnvironment { environment =>
    environment.configureTestProbeWithGetSessionHandler(shouldReturnUserData = true, (sender: ActorRef) => {
      case _: Logout => NoAutoPilot
    })

    val result = environment.client.mainPage(logout = Some("")).apply(FakeRequest().withCookies(Cookie(SessionIdCookieName, TestSessionIdAsString)))

    assertResponseContent(result, MainPageController.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageController.UserTemplateKey -> TestUsername))
    environment.sessionManagerProbe.expectMsg(GetSession(TestSessionId))
    environment.sessionManagerProbe.expectMsg(Logout(TestSessionId))
    assertResponseContainsSingleCookie(result, Cookie(SessionIdCookieName, TestSessionIdAsString, MainPageController.CookieExpiresNow))
  }

  private def assertResponseContent(response: Future[Result], template: Path, contentRoot: Path, templateMappings: Map[String, String]*): Unit = {
    val mustache = (new NonEscapingMustacheFactory).compile(Files.newBufferedReader(contentRoot.resolve(template)), "")
    val writer = new StringWriter

    val templateMappingsIncludingWebjarPaths = templateMappings.toVector :+ Map(
      BootstrapCssKey -> BootstrapTestAssetPath,
      FontAwesomeCssKey -> FontAwesomeTestAssetPath
    )
    mustache.execute(writer, templateMappingsIncludingWebjarPaths.map(_.asJava).toArray[Object])
    assertResponseContent(response, writer.toString)
  }

  private def assertResponseContent(response: Future[Result], expected: String): Unit = {
    contentAsString(response) should === (expected)
    contentType(response) should === (Some(TextHtmlContentType))
  }

  private def assertResponseContent(response: Future[Result], expectedBody: String, statusCode: Int, location: String, cookie: Cookie): Unit = {
    contentAsString(response) should === (expectedBody)
    status(response) should === (statusCode)
    redirectLocation(response) should === (Some(location))
    assertResponseContainsSingleCookie(response, cookie)
  }

  private def assertResponseContainsSingleCookie(response: Future[Result], cookie: Cookie): Unit = {
    cookies(response).toVector should === (Vector(cookie))
  }

  private def getMappingsForLanguage(contentFolder: Path, lang: String): Map[String, String] = {
    JsonFileHelper.getContents(contentFolder.resolve(LanguagesFolder).resolve(s"$lang$LanguageFileExtension")).as[Map[String, String]]
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
