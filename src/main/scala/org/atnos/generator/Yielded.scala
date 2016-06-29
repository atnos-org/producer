package org.atnos.generator

import cats.{Monad, data}, data._
import cats.implicits._
import org.atnos.eff._, all._, syntax.all._
import org.atnos.origami._

object Yielded {

  type Consumer[R, E] = E => Eff[R, Unit]
  type Producer[E] = Generator[E, Unit]

  trait Generator[E, A] {
    def run[R](consumer: Consumer[R, E]): Eff[R, A]
  }

  object Generator {
    implicit def GenMonad[E]: Monad[Generator[E, ?]] = new Monad[Generator[E, ?]] {
      def flatMap[A, B](fa: Generator[E, A])(f: A => Generator[E, B]): Generator[E, B] =
        new Generator[E, B] {
          def run[R](c: Consumer[R, E]) =
            fa.run(c).flatMap(a => f(a).run(c))
        }

      def pure[A](a: A): Generator[E, A] =
        new Generator[E, A] {
          def run[R](consumer: Consumer[R, E]) =
            Eff.pure(a)
        }
    }
  }

  def yielded[E](e: E): Generator[E, Unit] = new Generator[E, Unit] {
    def run[R](consumer: Consumer[R, E]): Eff[R, Unit] =
      consumer(e)
  }

  def foldG[R <: Effects, S, E](producer: Producer[E])(fold: (S, E) => Eff[R, S])(initial: S): Eff[R, S] =
    foldEff(producer)(FoldEff.fromFoldLeft(initial)(fold))

  def foldEff[R <: Effects, E, A](producer: Producer[E])(fold: FoldEff[R, E, A]): Eff[R, A] = {
    type RS = State[fold.S, ?] |: R

    def consumer:  E => Eff[RS, Unit] = (e: E) =>
      get[RS, fold.S] >>= (s => put[RS, fold.S](fold.fold(s, e)))

    fold.start.flatMap { initial =>
      execState(initial)(producer.run(consumer)).flatMap(fold.end _)
    }
  }

  def emit[R, A](elements: List[A]): Producer[A] =
    elements match {
      case Nil => Generator.GenMonad.pure(())
      case a :: as => yielded[A](a) >> emit(as)
    }

  def collect[R <: Effects, A](producer: Producer[A]): Eff[R, List[A]] =
    foldEff(producer)(Folds.list)

  def filter[A](producer: Producer[A])(f: A => Boolean): Producer[A] = {
    new Generator[A, Unit] {
      def run[R](consumer: Consumer[R, A]): Eff[R, Unit] =
        producer.run { a: A =>
          if (f(a)) consumer(a)
          else      pure(())
        }
    }
  }
}
