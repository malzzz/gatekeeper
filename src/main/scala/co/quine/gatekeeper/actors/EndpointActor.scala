package co.quine.gatekeeper.actors

import akka.actor._
import scala.collection.mutable

import co.quine.gatekeeper.Codec._

object EndpointActor {
  case class ResourceToken(token: Token, remaining: Int, reset: Long)

  def props(resource: TwitterResource, tokens: TokenBook) = Props(new EndpointActor(resource, tokens))
}

class EndpointActor(resource: TwitterResource, tokens: TokenBook) extends Actor with ActorLogging {

  import EndpointActor._

  val tokenPool = mutable.ArrayBuffer[ResourceToken]()

  override def preStart() = resetPool()

  def receive = command orElse update

  def command: Receive = {
    case cmd: Grant => sender ! grant
    case cmd: Remaining => sender ! remaining
    case cmd: TTL => sender ! ttl
  }

  def update: Receive = {
    case u: RateLimit =>
      tokens.findByKey(u.token).foreach(updateResourceToken(_, u.remaining, u.reset))
  }

  def addToken(token: Token) = tokenPool.append(ResourceToken(token, 0, 0))

  def grant: Token = hasCalls match {
    case true => takeFromResourceToken(mostCalls)
    case false => UnavailableToken(ttl)
  }

  def hasCalls: Boolean = if (remaining > 0) true else false

  def limit: Int = tokenPool.foldLeft(0)((count, rt) => count + getLimit(rt.token))

  def mostCalls: ResourceToken = tokenPool.max(Ordering.by((t: ResourceToken) => t.remaining))

  def remaining: Int = tokenPool.foldLeft(0)((count, rt) => count + rt.remaining)

  def ttl: Long = tokenPool.min(Ordering.by((t: ResourceToken) => t.reset)).reset

  private def getLimit(t: Token): Int = t match {
    case x: AccessToken => resource.userLimit
    case x: BearerToken => resource.appLimit
  }

  private def resetPool(): Unit = {
    tokenPool.clear()
    tokens.access.foreach(t => addToken(t))
    addToken(tokens.bearer)
  }

  private def takeFromResourceToken(t: ResourceToken): Token = {
    val tokenIndex = tokenPool.indexOf(t)
    tokenPool.update(tokenIndex, t.copy(remaining = t.remaining - 1))
    t.token
  }

  private def updateResourceToken(t: Token, remaining: Int, reset: Long): Unit = {
    val tokenIndex = tokenPool.indexOf {
      tokenPool.find(r => r.token == t) match {
        case Some(token) => token
        case _ =>
      }
    }
    tokenPool.update(tokenIndex, ResourceToken(t, remaining, reset))
  }
}