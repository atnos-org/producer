package org.atnos.producer

import cats._
import org.atnos.eff.all._
import Producer._

sealed trait Stream[R, A]

case class Done[R, A]() extends Stream[R, A]
case class One[R, A](a: A) extends Stream[R, A]
case class More[R, A](as: List[A], next: Producer[R, A]) extends Stream[R, A]

object Stream {
  implicit def MonoidStream[R :_Safe, A]: Monoid[Stream[R, A]] = new Monoid[Stream[R, A]] {
    def empty: Stream[R, A] = Done()

    def combine(s1: Stream[R, A], s2: Stream[R, A]): Stream[R, A] =
      (s1, s2) match {
        case (Done(), _) => s2
        case (_, Done()) => s1
        case (One(a1), One(a2)) => More(List(a1, a2), done[R, A])
        case (One(a1), More(as2, next2)) => More(a1 :: as2, next2)
        case (More(as1, next1), One(a2)) => More(as1, next1 append one(a2))
        case (More(as1, next1), More(as2, next2)) => More(as1, next1 append Producer(pure(s2)))
      }
  }
}


