package tela.web

import akka.actor.ActorRef
import akka.testkit.TestActor.{KeepRunning, NoAutoPilot}
import akka.testkit.{TestActor, TestActorRef}
import io.netty.handler.codec.http._
import org.junit.Assert._
import org.junit.{Before, Test}
import org.mashupbots.socko.events.{HttpRequestEvent, HttpResponseStatus}
import tela.baseinterfaces.LoginFailure
import tela.web.SessionManager.{GetSession, Login, Logout}

class MainPageHandlerTest extends SockoHandlerTestBase {
  private val TestPassword = "myPass"
  private val ContentFolder = "web/src/main/html/"

  @Before def initialize(): Unit = {
    doBasicInitialization()
  }

  @Test def loginScreenIsDisplayedWhenSessionCookieNotPresent(): Unit = {
    initializeTestActor()

    val event: HttpRequestEvent = createHttpRequestEvent(HttpMethod.GET, MainPageHandler.WebAppRoot)

    handler ! event

    assertResponseContent(event.response, MainPageHandler.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  @Test def badLogin(): Unit = {
    initializeTestActor()

    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, DefaultLanguage) =>
            sender ! Left(LoginFailure.InvalidCredentials)
            NoAutoPilot
        }
    })

    val event = createHttpRequestEvent(HttpMethod.POST, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.CONTENT_TYPE -> "application/x-www-form-urlencoded"),
      MainPageHandler.UsernameRequestParameter + "=" + TestUsername + "&" + MainPageHandler.PasswordRequestParameter + "=" + TestPassword)
    handler ! event

    assertResponseContent(event.response, MainPageHandler.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageHandler.BadCredentialsError -> true.toString, MainPageHandler.UserTemplateKey -> TestUsername))
  }

  @Test def connectionFailed(): Unit = {
    initializeTestActor()

    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, DefaultLanguage) =>
            sender ! Left(LoginFailure.ConnectionFailure)
            NoAutoPilot
        }
    })

    val event = createHttpRequestEvent(HttpMethod.POST, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.CONTENT_TYPE -> "application/x-www-form-urlencoded"),
      MainPageHandler.UsernameRequestParameter + "=" + TestUsername + "&" + MainPageHandler.PasswordRequestParameter + "=" + TestPassword)
    handler ! event

    assertResponseContent(event.response, MainPageHandler.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map(MainPageHandler.ConnectionFailedError -> true.toString, MainPageHandler.UserTemplateKey -> TestUsername))
  }

  @Test def redirectToIndexPageAndSetCookieOnSuccessfulLogin(): Unit = {
    initializeTestActor()

    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, DefaultLanguage) =>
            sender ! Right(TestSessionId)
            NoAutoPilot
        }
    })

    val event = createHttpRequestEvent(HttpMethod.POST, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.CONTENT_TYPE -> "application/x-www-form-urlencoded"),
      MainPageHandler.UsernameRequestParameter + "=" + TestUsername + "&" + MainPageHandler.PasswordRequestParameter + "=" + TestPassword)
    handler ! event

    assertEquals(HttpResponseStatus.FOUND, event.response.status)
    assertEquals(MainPageHandler.WebAppRoot, event.response.headers.get(HttpHeaders.Names.LOCATION).get)

    assertCookie(createCookie(SessionIdCookieName, TestSessionId, MainPageHandler.CookieExpiresWhenBrowserCloses), event)

    assertResponseBody("")
  }

  @Test def requestWithValidSessionCookieGetsIndexPage(): Unit = {
    initializeTestActor()

    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, DefaultLanguage) =>
            sender ! Right(TestSessionId)
            KeepRunning
          case GetSession(TestSessionId) =>
            sender ! Some(new UserData(TestUsername, DefaultLanguage))
            NoAutoPilot
        }
    })

    handler ! createHttpRequestEvent(HttpMethod.POST, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.CONTENT_TYPE -> "application/x-www-form-urlencoded"),
      MainPageHandler.UsernameRequestParameter + "=" + TestUsername + "&" + MainPageHandler.PasswordRequestParameter + "=" + TestPassword)

    val event = createHttpRequestEvent(HttpMethod.GET, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    initializeTestActor()
    handler ! event

    assertResponseContent(event.response, IndexPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage), Map(MainPageHandler.UserTemplateKey -> TestUsername))
  }

  @Test def requestWithInvalidSessionCookieRedirectsToLoginPage(): Unit = {
    initializeTestActor()

    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case GetSession(TestSessionId) =>
            sender ! None
            NoAutoPilot
        }
    })

    val event = createHttpRequestEvent(HttpMethod.GET, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    handler ! event

    assertResponseContent(event.response, MainPageHandler.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  @Test def logout(): Unit = {
    initializeTestActor()

    var logoutCalledWithCorrectSessionId = false
    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, DefaultLanguage) =>
            sender ! Right(TestSessionId)
            KeepRunning
          case GetSession(TestSessionId) =>
            sender ! Some(new UserData(TestUsername, DefaultLanguage))
            KeepRunning
          case Logout(TestSessionId) =>
            logoutCalledWithCorrectSessionId = true
            NoAutoPilot
        }
    })

    handler ! createHttpRequestEvent(HttpMethod.POST, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.CONTENT_TYPE -> "application/x-www-form-urlencoded"),
      MainPageHandler.UsernameRequestParameter + "=" + TestUsername + "&" + MainPageHandler.PasswordRequestParameter + "=" + TestPassword)

    val event = createHttpRequestEvent(HttpMethod.GET, MainPageHandler.WebAppRoot + "?" + MainPageHandler.LogoutParameter,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId))))

    initializeTestActor()

    handler ! event

    assertResponseContent(event.response, MainPageHandler.LoginPage,
      ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage),
      Map("user" -> TestUsername))
    assertTrue(logoutCalledWithCorrectSessionId)

    assertCookie(createCookie(SessionIdCookieName, TestSessionId, MainPageHandler.CookieExpiresNow), event)
  }

  @Test def alternativeLanguage(): Unit = {
    initializeTestActor()

    val event: HttpRequestEvent = createHttpRequestEvent(HttpMethod.GET, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.ACCEPT_LANGUAGE -> "es-ES,es;q=0.8,en;q=0.6"))

    handler ! event

    assertResponseContent(event.response, MainPageHandler.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, "es"))
  }

  @Test def unknownLanguage(): Unit = {
    initializeTestActor()

    val event: HttpRequestEvent = createHttpRequestEvent(HttpMethod.GET, MainPageHandler.WebAppRoot, Map(HttpHeaders.Names.ACCEPT_LANGUAGE -> "XX;q=0.8,en;q=0.6"))

    handler ! event

    assertResponseContent(event.response, MainPageHandler.LoginPage, ContentFolder, getMappingsForLanguage(ContentFolder, DefaultLanguage))
  }

  @Test def preferredLanguageIsEstablishedFromSessionData(): Unit = {
    initializeTestActor()

    sessionManagerProbe.setAutoPilot(new TestActor.AutoPilot {
      def run(sender: ActorRef, msg: Any): TestActor.AutoPilot =
        msg match {
          case Login(TestUsername, TestPassword, "es") =>
            sender ! Right(TestSessionId)
            KeepRunning
          case GetSession(TestSessionId) =>
            sender ! Some(new UserData(TestUsername, "es"))
            NoAutoPilot
        }
    })

    handler ! createHttpRequestEvent(HttpMethod.POST, MainPageHandler.WebAppRoot,
      Map(HttpHeaders.Names.CONTENT_TYPE -> "application/x-www-form-urlencoded", HttpHeaders.Names.ACCEPT_LANGUAGE -> "es-ES,es;q=0.8,en;q=0.6"),
      MainPageHandler.UsernameRequestParameter + "=" + TestUsername + "&" + MainPageHandler.PasswordRequestParameter + "=" + TestPassword)

    val event = createHttpRequestEvent(HttpMethod.GET, MainPageHandler.WebAppRoot,
      Map(HttpHeaders.Names.COOKIE -> ClientCookieEncoder.encode(createCookie(SessionIdCookieName, TestSessionId)),
        HttpHeaders.Names.ACCEPT_LANGUAGE -> "en;q=0.8"))

    initializeTestActor()
    handler ! event

    assertResponseContent(event.response, IndexPage, ContentFolder, getMappingsForLanguage(ContentFolder, "es"), Map(MainPageHandler.UserTemplateKey -> TestUsername))
  }

  private def initializeTestActor(): Unit = {
    handler = TestActorRef(new MainPageHandler(ContentFolder, sessionManagerProbe.ref))
  }

  private def assertCookie(expected: Cookie, event: HttpRequestEvent): Unit = {
    val cookies: Array[Cookie] = decodeCookie(event.response.headers.get(HttpHeaders.Names.SET_COOKIE).get).toArray
    assertEquals(1, cookies.length)
    assertEquals(expected.getName, cookies(0).getName)
    assertEquals(expected.getValue, cookies(0).getValue)
    assertTrue((expected.getMaxAge == cookies(0).getMaxAge) || (expected.getMaxAge + 1 == cookies(0).getMaxAge)) //hack to work around strange(?) behaviour of CookieDecoder
    assertEquals(expected, cookies(0)) //equals on DefaultCookie neglects to test a lot of things, but let's call it for good measure
  }
}
