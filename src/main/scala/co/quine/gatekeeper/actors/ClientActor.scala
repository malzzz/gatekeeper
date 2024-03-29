package co.quine.gatekeeper.actors

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.io.Tcp._
import akka.pattern.ask
import akka.util.{ByteString, Timeout}
import scala.concurrent.duration._
import scala.util.{Failure, Success}
import co.quine.gatekeeper._

object ClientActor {
  case object WriteAck extends Event

  def props(gate: ActorRef, client: ActorRef): Props = Props(new ClientActor(gate, client))
}

class ClientActor(gate: ActorRef, client: ActorRef) extends Actor with ActorLogging {

  import BufferActor._
  import Codec._
  import context.dispatcher

  implicit val timeout = Timeout(5.seconds)

  var buffer: ActorRef = _

  override def preStart() = {
    val heartbeat = context.actorOf(HeartbeatActor.props(client), s"${self.path.name}-heartbeat")
    context.watch(heartbeat)
    buffer = context.actorOf(BufferActor.props(self), s"${self.path.name}-buffer")
  }

  def receive = tcp

  def tcp: Receive = {
    case Received(bs) => buffer ! bs
    case Packet(bs) => onData(bs)
    case (Closed | ErrorClosed | PeerClosed) =>
      context.stop(self)
  }

  def onData(bs: ByteString) = bs.head match {
    case REQUEST => onRequest(bs.utf8String)
    case UPDATE => onUpdate(bs.utf8String)
  }

  def onRequest(s: String) = s.split('|') match {
    case Array(typeId, uuid, cmdArgs) =>
      val requestId = uuid.tail

      val response = cmdArgs.split(':') match { case Array(c, a) => onCommand(c, a) }

      response andThen {
        case Success(x: String) => client ! Write(ByteString(Response(requestId, x).serialize + '\n'))
        case Failure(_) => client ! Write(ByteString(Error(requestId, "UNAVAILABLE", "DOWN").serialize + '\n'))
      }
  }

  def onUpdate(s: String) = s.split('|') match {
    case Array(typeId, cmd, payload) => cmd match {
      case "RATELIMIT" =>
        payload.split(':') match { case Array(resource, tokenKey, remaining, reset) =>
          gate ! RateLimit(resource, tokenKey, remaining.toInt, reset.toLong)
        }
    }
  }

  def onCommand(cmd: String, args: String) = cmd match {
    case "CLIENTS" => context.parent ? ListConnectedClients collect {
      case clients: ConnectedClients => s"${UPDATE}CLIENTS:${clients.serialize}"
    }
    case "CONSUMER" => gate ? Consumer collect {
      case response: Token => response.serialize
    }

    case "GRANT" => gate ? Grant(args) collect {
      case response: Token => response.serialize
    }

    case "NEWBEARER" => gate ? NewBearer collect {
      case response: Token => response.serialize
    }

    case "REM" => gate ? Remaining(args) collect {
      case response: Int => s"${UPDATE}REM:$response"
    }

    case "TTL" => gate ? TTL(args) collect {
      case response: Long => s"${UPDATE}TTL:$response"
    }
  }
}