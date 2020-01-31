package co.ledger.wallet.daemon.services

import javax.inject.{Inject, Singleton}
import co.ledger.wallet.daemon.configurations.DaemonConfiguration
import co.ledger.wallet.daemon.database.DaemonCache
import co.ledger.wallet.daemon.database.DefaultDaemonCache.User
import co.ledger.wallet.daemon.services.AuthenticationService.{AuthenticationFailedException, AuthentifiedUserContext, UserNotFoundException}
import co.ledger.wallet.daemon.utils.HexUtils
import co.ledger.wallet.daemon.utils.Utils._
import com.twitter.finagle.http.Request
import com.twitter.util.Future

import scala.concurrent.ExecutionContext

@Singleton
class AuthenticationService @Inject()(daemonCache: DaemonCache, ecdsa: ECDSAService) extends DaemonService {
  import co.ledger.wallet.daemon.services.AuthenticationService.AuthContextContext._
  // Avoid ecdsa is unused error
  assert(ecdsa.eq(ecdsa))
  def authorize(request: Request)(implicit ec: ExecutionContext): Future[Unit] = {
    attemptAuthorize(request).rescue({
      case ex: UserNotFoundException =>
        if (DaemonConfiguration.isWhiteListDisabled) {
          daemonCache.createUser(HexUtils.valueOf(request.authContext.pubKey), 0).asTwitter().flatMap({ _ =>
            attemptAuthorize(request)
          })
        } else {
          Future.exception(AuthenticationFailedException(ex.getMessage))
        }
      case error => throw error
    })
  }

  private def attemptAuthorize(request: Request)(implicit ec: ExecutionContext): Future[Unit] = {
    try {
      val pubKey = request.authContext.pubKey
      daemonCache.getUser(HexUtils.valueOf(pubKey)) map {
        case None =>
          throw UserNotFoundException()
        case Some(user) =>
          AuthentifiedUserContext.setUser(request, user)
      } asTwitter()
    } catch {
      case _: IllegalStateException => throw AuthenticationFailedException("Missing authorization header")
    }
  }

}

object AuthenticationService {

  case class AuthenticationFailedException(msg: String) extends Exception(msg)
  case class UserNotFoundException() extends Exception("User doesn't exist")
  case class AuthContext(pubKey: Array[Byte], time: Long, signedMessage: Array[Byte])
  object AuthContextContext {
    private val AuthContextField = Request.Schema.newField[AuthContext]()
    implicit class AuthContextContextSyntax(val request: Request) extends AnyVal {
      def authContext: AuthContext = request.ctx(AuthContextField)
    }
    def setContext(request: Request, context: AuthContext): Unit = {
      request.ctx.update(AuthContextField, context)
    }
  }

  case class AuthentifiedUser(get: User)
  object AuthentifiedUserContext {
    private val UserField = Request.Schema.newField[AuthentifiedUser]()

    implicit class UserContextSyntax(val request: Request) extends AnyVal {
      def user: AuthentifiedUser = request.ctx(UserField)
    }
    def setUser(request: Request, user: User): Unit = request.ctx.update[AuthentifiedUser](UserField, AuthentifiedUser(user))
  }
}
