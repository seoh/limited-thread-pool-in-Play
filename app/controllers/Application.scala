package controllers

import java.util.Calendar
import java.util.concurrent.Executors

import play.api.Play.current
import play.api.libs.concurrent.Akka
import play.api.libs.iteratee.{Input, Step, Iteratee}
import play.api.libs.json.Json
import play.api.libs.ws.WS
import play.api.mvc._

import scala.collection.immutable.IndexedSeq
import scala.concurrent.{ExecutionContextExecutor, ExecutionContext, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

object Contexts {
  implicit val myEc = Akka.system.dispatchers.lookup("my-context")
  implicit val dispatcher = Akka.system.dispatchers.lookup("my-thread-pool-dispatcher")
  implicit val ec: ExecutionContextExecutor = ExecutionContext.fromExecutor(Executors.newScheduledThreadPool(1))

}


class Application extends Controller {

  def delayThread(n: Int)(implicit executor: ExecutionContext): Future[Int] = Future {
    Thread.sleep(n * 1000);
    n
  }(executor)


  def now = Calendar.getInstance.getTimeInMillis

  val cores = Runtime.getRuntime.availableProcessors

  def futures(implicit ec: ExecutionContext) = (0 until cores * 100).map(_ => delayThread(2)(ec))


  /**
   * with default configuration.
   *
   * @return
   */
  def index = Action.async {
    import play.api.libs.concurrent.Execution.Implicits.defaultContext

    val s = now

    Future.sequence(futures) map { res =>
      Ok(s"${res.length} futures after ${now - s}ms \n")
    }
  }

  /**
   * parallelism-factor = 1.0
   */
  def simple = Action.async {
    import Contexts.myEc

    val s = now
    Future.sequence(futures) map { res =>
      Ok(s"${res.length} futures after ${now - s}ms \n")
    }
  }

  /**
   * type = Dispatcher
   * core-pool-size-factor = 1.0
   */
  def dispatcher = Action.async {
    val ec = Contexts.dispatcher

    val s = now
    Future.sequence(futures(ec))(implicitly, ec).map({ res =>
      Ok(s"${res.length} futures after ${now - s}ms \n")
    })(ec)
  }

  /**
   * force to use `n` threads. (in this case, same as `newSingleThreadExecutor`)
   */
  def manual = Action.async {
    import Contexts.ec

    val s = now
    Future.sequence(futures) map { res =>
      Ok(s"${res.length} futures after ${now - s}ms \n")
    }
  }

  /**
   * asynchronous job within sequential scheduler
   */
  def rx = Action.async {
    import play.api.libs.concurrent.Execution.Implicits.defaultContext

    val s = now
    val factor = 4 // 4 threads per cores?
    val g = futures.grouped(cores * factor)

    def consume(xss: Iterator[IndexedSeq[Future[Int]]], is: IndexedSeq[Int] = IndexedSeq[Int]()): Future[IndexedSeq[Int]] = {

      if (xss.hasNext) {
        val xs = xss.next()

        Future.sequence(xs) flatMap { res =>
          println(s"${res.length} futures after ${now - s}ms \n")
          consume(xss, is ++ res)
        }
      } else {
        Future(is)
      }
    }

    consume(g) map { res =>
      Ok(s"${res.length} futures after ${now - s}ms \n")
    }
  }

}