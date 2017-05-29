package org.atnos.producer

import org.specs2.Specification
import QueueEffect._
import org.atnos.eff._, all._
import org.atnos.eff.syntax.all._
import org.atnos.eff.syntax.future._
import org.specs2.concurrent.ExecutionEnv
import cats.implicits._

class QueueEffectSpec(implicit ee: ExecutionEnv) extends Specification { def is = s2"""

 queue elements $queueElements

 A Producer can be created out of a queue $producerFromQueue

"""

  implicit val es = ExecutorServices.schedulerFromScheduledExecutorService(ee.scheduledExecutorService)

  def queueElements = {
    val queue1 = Queue.create[Int]("q1", maxSize = 10)

    def action[R :_queue]: Eff[R, Int] =
      for {
        _ <- enqueue[R, Int](queue1, 1)
        _ <- enqueue[R, Int](queue1, 2)
        i <- dequeue[R, Int](queue1)
        j <- dequeue[R, Int](queue1)
      } yield i + j

    type S = Fx.fx2[QueueOp, TimedFuture]

    runQueueFuture(action[S]).runAsync must be_==(3).await
  }

  def producerFromQueue = {
    val queue1 = Queue.create[Int]("q1", maxSize = 10)

    type S = Fx.fx3[QueueOp, TimedFuture, Safe]
    type ES[A] = Eff[S, A]

    val p = Producer.repeatEval[ES, Int](dequeue(queue1))

    val add = (1 to 5).toList.traverse(i => enqueue[S, Int](queue1, i))

    val action =
      runQueueFuture(add >> p.take(5).runList).execSafe.runAsync

    action must beRight((1 to 5).toList).await
  }

}
