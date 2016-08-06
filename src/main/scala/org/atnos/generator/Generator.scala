package org.atnos.generator

import cats.{Monad, data}, data._
import cats.implicits._
import org.atnos.eff._, all._, syntax.all._
import org.atnos.origami._

trait Generator[E, A] {
  def run[R](consumer: Consumer[R, E]): Eff[R, A]
}

object Generator { outer =>

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

  implicit def ProducerMonad: Monad[Producer] = new Monad[Producer] {
    def flatMap[A, B](fa: Producer[A])(f: A => Producer[B]): Producer[B] =
      outer.flatMap(fa)(f)

    def pure[A](a: A): Producer[A] =
      outer.yielded(a)
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

  def foldEff[R, E, A](producer: Producer[E])(fold: FoldEff[R, E, A]): Eff[R, A] = {
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

  def collect[R, A](producer: Producer[A]): Eff[R, List[A]] =
    foldEff(producer)(Folds.list)

  def flatMap[A, B](producer: Producer[A])(f: A => Producer[B]): Producer[B] =
    new Generator[B, Unit] {
      def run[R](consumer: Consumer[R, B]): Eff[R, Unit] =
        producer.run { ona: On[A] =>
          ona match {
            case One(a) =>
              f(a).run(consumer)

            case Done =>
              pure(())
          }
        }
    }

  def map[A, B](producer: Producer[A])(f: A => B): Producer[B] =
    flatMap(producer)(a => yielded(f(a)))

  def flatten[A](producer: Producer[Producer[A]]): Producer[A] =
    flatMap(producer)(identity)

  def filter[A](producer: Producer[A])(f: A => Boolean): Producer[A] =
    flatMap(producer)((a: A) => if (f(a)) yielded (a) else done)

  def flattenList[A](producer: Producer[List[A]]): Producer[A] =
    flatMap(producer)(emit)

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
