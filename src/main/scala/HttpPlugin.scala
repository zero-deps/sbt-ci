package synrc.ci

import akka.actor.ActorSystem
import akka.http.Http
import akka.io.IO
import akka.pattern.ask
import akka.stream.scaladsl.Flow
import akka.stream.{FlowMaterializer, MaterializerSettings}
import akka.util.Timeout

import scala.concurrent.duration._

object AkkaHttp extends App{
  object Routes {
    import akka.http.model.HttpMethods._
    import akka.http.model._

    val requestHandler: HttpRequest ⇒ HttpResponse = {
      case HttpRequest(GET, Uri.Path("/"), _, _, _) ⇒
        HttpResponse(entity = HttpEntity(MediaTypes.`text/html`, "<html><body>Hello world!</body></html>"))
      case HttpRequest(GET, Uri.Path("/ping"), _, _, _)  ⇒ HttpResponse(entity = "PONG!")
      case HttpRequest(GET, Uri.Path("/crash"), _, _, _) ⇒ sys.error("BOOM!")
      case _: HttpRequest                                ⇒ HttpResponse(404, entity = "Unknown resource!")
    }
  }

  println("START SERVER")
  implicit val system = ActorSystem()
  implicit val materializer = FlowMaterializer(MaterializerSettings(system))
  import synrc.ci.AkkaHttp.system.dispatcher

  implicit val askTimeout: Timeout = 500.millis

  val bindingFuture = (IO(Http) ? Http.Bind(interface = "0.0.0.0", port = 8080)).mapTo[Http.ServerBinding]

  bindingFuture foreach {
    case Http.ServerBinding(localAddress, connectionStream) ⇒
      Flow(connectionStream).foreach {
        case Http.IncomingConnection(remoteAddress, requestProducer, responseConsumer) ⇒
//          println("Accepted new connection from " + remoteAddress)
          Flow(requestProducer).map(Routes.requestHandler).produceTo(responseConsumer)
      }//.consume(materializer)
  }

  println(s"Server online at http://localhost:8080/\nPress RETURN to stop...")
  scala.Console.readLine()
  system.shutdown()
}
