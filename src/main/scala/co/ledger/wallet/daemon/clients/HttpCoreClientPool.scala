package co.ledger.wallet.daemon.clients

import java.io.BufferedInputStream
import java.net.URL
import java.util

import co.ledger.core._
import co.ledger.wallet.daemon.exceptions.InvalidUrlException
import co.ledger.wallet.daemon.utils.Utils.RichTwitterFuture
import com.sun.xml.internal.messaging.saaj.util.ByteOutputStream
import com.twitter.finagle.http.{Method, Request, Response}
import com.twitter.inject.Logging
import com.twitter.io.Buf

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class HttpCoreClientPool(val ec: ExecutionContext, client: ScalaHttpClientPool) extends co.ledger.core.HttpClient with Logging {

  import ScalaHttpClientPool._

  implicit val executionContext: ExecutionContext = ec

  override def execute(request: HttpRequest): Unit =
    Future(Try(new URL(request.getUrl)).map(urlToHost))
      .flatMap {
        case Success(host) =>
          val req: Request = Request(resolveMethod(request.getMethod), request.getUrl)
          request.getHeaders.entrySet.forEach(hkv => req.headerMap.put(hkv.getKey, hkv.getValue))
          if (request.getBody.nonEmpty) {
            req.content(Buf.ByteArray.Owned(request.getBody))
          }
          client.execute(host, req)
            .map { response =>
              info(s"Core Http received from ${request.getUrl} status=${response.status.code} error=${isOnError(response.status.code)} " +
                s"- statusText=${response.status.reason}")
              new ScalaHttpUrlConnection(
                response.status.code,
                response.status.reason,
                getResponseHeaders(response),
                readResponseBody(response, isOnError(response.status.code)))
            }
            .map(r => request.complete(r, null)).asScala()
        case Failure(exception) => Future(request.complete(null, new Error(ErrorCode.HTTP_ERROR, s"Failed to parse url ${request.getUrl} => ${exception.getMessage}")))
      }

  private def getResponseHeaders(response: Response) = {
    val headers = new util.HashMap[String, String]()
    for ((key, value) <- response.headerMap) {
      headers.put(key, value)
    }
    headers
  }

  private def readResponseBody(resp: Response, onError: Boolean): HttpReadBodyResult = {
    val response = new BufferedInputStream(resp.getInputStream)
    val buffer = new Array[Byte](PROXY_BUFFER_SIZE)
    val outputStream = new ByteOutputStream()
    try {
      var size = 0
      do {
        size = response.read(buffer)
        if (size < buffer.length) {
          outputStream.write(buffer.slice(0, size))
        } else {
          outputStream.write(buffer)
        }
      } while (size > 0)
      val data = outputStream.getBytes
      if (onError) info(s"Received ${new String(data)}")
      new HttpReadBodyResult(null, data)
    } catch {
      case t: Throwable =>
        logger.error("Failed to read response body", t)
        val error = new co.ledger.core.Error(ErrorCode.HTTP_ERROR, "An error happened during body reading.")
        new HttpReadBodyResult(error, null)
    } finally {
      outputStream.close()
      response.close()
    }
  }

  private def resolveMethod(method: HttpMethod): Method = method match {
    case HttpMethod.GET => Method.Get
    case HttpMethod.POST => Method.Post
    case HttpMethod.PUT => Method.Put
    case HttpMethod.DEL => Method.Delete
    case _ => throw InvalidUrlException(s"Unsupported method ${method.name()}")
  }

  def poolCacheSize: Long = client.poolCacheSize

}
