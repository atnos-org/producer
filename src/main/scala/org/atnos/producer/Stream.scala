package org.atnos.producer

import cats._
import org.atnos.eff.all._
import Producer._

sealed trait Stream[M[_], A]

case class Done[M[_], A]() extends Stream[M, A]
case class One[M[_], A](a: A) extends Stream[M, A]
case class More[M[_], A](as: List[A], next: Producer[M, A]) extends Stream[M, A]

object Stream {

  implicit def MonoidStream[M[_] : MonadDefer, A]: Monoid[Stream[M, A]] = new Monoid[Stream[M, A]] {
    def empty: Stream[M, A] = Done()

    def combine(s1: Stream[M, A], s2: Stream[M, A]): Stream[M, A] =
      (s1, s2) match {
        case (Done(), _) => s2
        case (_, Done()) => s1
        case (One(a1), One(a2)) => More(List(a1, a2), done[M, A])
        case (One(a1), More(as2, next2)) => More(a1 :: as2, next2)
        case (More(as1, next1), One(a2)) => More(as1, next1 append one(a2))
        case (More(as1, next1), More(as2, next2)) => More(as1, next1 append Producer(MonadDefer[M].pure(s2)))
      }
  }
}


