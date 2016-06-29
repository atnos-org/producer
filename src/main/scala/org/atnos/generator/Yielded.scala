package org.atnos.generator

import cats.{Monad, data}, data._
import cats.implicits._
import org.atnos.eff._, all._, syntax.all._
import org.atnos.origami._

object Yielded {

  sealed trait On[+A]
  case class One[A](a: A) extends On[A]
  case object Done extends On[Nothing]

  type Consumer[R, E] = On[E] => Eff[R, Unit]
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
      consumer(One(e))
  }

  def done[E]: Generator[E, Unit] = new Generator[E, Unit] {
    def run[R](consumer: Consumer[R, E]): Eff[R, Unit] =
      consumer(Done)
  }

  def foldG[R <: Effects, S, E](producer: Producer[E])(fold: (S, E) => Eff[R, S])(initial: S): Eff[R, S] =
    foldEff(producer)(FoldEff.fromFoldLeft(initial)(fold))

  def foldEff[R <: Effects, E, A](producer: Producer[E])(fold: FoldEff[R, E, A]): Eff[R, A] = {
    type RS = State[fold.S, ?] |: R

    def consumer: On[E] => Eff[RS, Unit] = {
      case One(e1) =>
        get[RS, fold.S] >>= (s => put[RS, fold.S](fold.fold(s, e1)))
      case Done =>
        pure(())
    }

    fold.start.flatMap { initial =>
      execState(initial)(producer.run(consumer)).flatMap(fold.end)
    }
  }

  def emit[R, A](elements: List[A]): Producer[A] =
    elements match {
      case Nil => done
      case a :: as => yielded[A](a) >> emit(as)
    }

  def collect[R <: Effects, A](producer: Producer[A]): Eff[R, List[A]] =
    foldEff(producer)(Folds.list)

  def filter[A](producer: Producer[A])(f: A => Boolean): Producer[A] =
    new Generator[A, Unit] {
      def run[R](consumer: Consumer[R, A]): Eff[R, Unit] =
        producer.run { ona: On[A] =>
          ona match {
            case One(a) =>
              if (f(a)) consumer(One(a))
              else      pure(())
            case Done =>
              pure(())
          }
        }
    }

  def chunk[A](size: Int)(producer: Producer[A]): Producer[List[A]] =
    new Generator[List[A], Unit] {
      def run[R](consumer: Consumer[R, List[A]]): Eff[R, Unit] = {
        val elements = new collection.mutable.ListBuffer[A]
        producer.run { ona: On[A] =>
          ona match {
            case One(a) =>
              elements.append(a)
              if (elements.size >= size) {
                val es = elements.toList
                elements.clear
                consumer(One(es))
              }
              else pure(())

            case Done =>
              consumer(One(elements.toList)) >> consumer(Done)
          }
        }
      }
    }

}
