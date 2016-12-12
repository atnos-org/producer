package org.atnos.producer

import org.specs2.Specification
import QueueEffect._
import org.atnos.eff._, all._
import org.atnos.eff.syntax.all._
import org.specs2.concurrent.ExecutionEnv
import cats.implicits._

class QueueEffectSpec(implicit ee: ExecutionEnv) extends Specification { def is = s2"""

 queue elements $queueElements

 A Producer can be created out of a queue $producerFromQueue

"""

  def queueElements = {
    val queue1 = Queue.create[Int]("q1", maxSize = 10)

    def action[R :_queue]: Eff[R, Int] =
      for {
        _ <- enqueue[R, Int](queue1, 1)
        _ <- enqueue[R, Int](queue1, 2)
        i <- dequeue[R, Int](queue1)
        j <- dequeue[R, Int](queue1)
      } yield i + j

    type S = Fx.fx2[QueueOp, Async]

    val interpreter = AsyncFutureInterpreter.create
    import interpreter._

    runQueueAsync(action[S]).runAsyncFuture must be_==(3).await
  }

  def producerFromQueue = {
    val queue1 = Queue.create[Int]("q1", maxSize = 10)

    type S = Fx.fx3[QueueOp, Async, Safe]
    val p = Producer.repeatEval[S, Int](dequeue(queue1))

    val add = (1 to 5).toList.traverse(i => enqueue[S, Int](queue1, i))

    val interpreter = AsyncFutureInterpreter.create
    import interpreter._

    val action =
      runQueueAsync(add >> p.runList).execSafe.runAsyncFuture

    action must beRight((1 to 5).toList).await
  }

}
