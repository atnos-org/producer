package org.atnos.generator

import cats.{Eval, Foldable, Monad, data}
import data._
import cats.implicits._
import org.atnos.eff._
import all._
import syntax.all._
import org.atnos.origami._

trait Generator[R, E, A] {
  def run(consumer: Consumer[R, E]): Eff[R, A]
}

object Generator { outer =>

  implicit def GenMonad[R, E]: Monad[Generator[R, E, ?]] = new Monad[Generator[R, E, ?]] {
    def flatMap[A, B](fa: Generator[R, E, A])(f: A => Generator[R, E, B]): Generator[R, E, B] =
      new Generator[R, E, B] {
        def run(c: Consumer[R, E]) =
          fa.run(c).flatMap(a => f(a).run(c))
      }

    def pure[A](a: A): Generator[R, E, A] =
      new Generator[R, E, A] {
        def run(consumer: Consumer[R, E]) =
          Eff.pure(a)
      }
  }

  implicit def ProducerMonad[R]: Monad[Producer[R, ?]] = new Monad[Producer[R, ?]] {
    def flatMap[A, B](fa: Producer[R, A])(f: A => Producer[R, B]): Producer[R, B] =
      outer.flatMap(fa)(f)

    def pure[A](a: A): Producer[R, A] =
      outer.yielded(a)
  }

  def yielded[R, E](e: E): Producer[R, E] = new Generator[R, E, Unit] {
    def run(consumer: Consumer[R, E]): Eff[R, Unit] =
      consumer(One(e))
  }

  def done[R, E]: Producer[R, E] = new Generator[R, E, Unit] {
    def run(consumer: Consumer[R, E]): Eff[R, Unit] =
      consumer(Done)
  }

  def foldG[R, S, E](producer: Producer[R, E])(fold: (S, E) => Eff[R, S])(initial: S): Eff[R, S] =
    foldEff(producer)(FoldEff.fromFoldLeft(initial)(fold))

  def foldEff[R, E, A](producer: Producer[R, E])(fold: FoldEff[R, E, A]): Eff[R, A] =
    fold.run(producer)

  implicit def FoldableProducer[R]: Foldable[Producer[R, ?]] = new Foldable[Producer[R, ?]] {
    def foldLeft[A, B](fa: Producer[R, A], b: B)(f: (B, A) => B): B = {
      var s = b
      fa.run {
        case One(a) => s = f(s, a); Eff.pure(())
        case Done   => Eff.pure(())
      }
      s
    }

    def foldRight[A, B](fa: Producer[R, A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = {
      var s = lb
      fa.run {
        case One(a) => s = s >> f(a, s); Eff.pure(())
        case Done   => Eff.pure(())
      }
      s
    }
  }


  def append[R, A](p1: Producer[R, A], p2: Producer[R, A]): Producer[R, A] =
    new Generator[R, A, Unit] {
      def run(consumer: Consumer[R, A]): Eff[R, Unit] =
        p1.run(consumer) >> p2.run(consumer)
    }

  def pipe[R, A, B](p: Producer[R, A], t: Transducer[R, A, B]): Producer[R, B] =
    t(p)

  def emit[R, A](elements: List[A]): Producer[R, A] =
    new Generator[R, A, Unit] {
      def run(consumer: Consumer[R, A]): Eff[R, Unit] =
        elements match {
          case Nil     => done.run(consumer)
          case a :: as => yielded(a).run(consumer) >> emit(as).run(consumer)
        }
    }

  def emitEff[R, A](elements: Eff[R, List[A]]): Producer[R, A] =
    new Generator[R, A, Unit] {
      def run(consumer: Consumer[R, A]): Eff[R, Unit] =
        elements.map {
          case Nil     => consumer(Done)
          case a :: as => consumer(One(a)) >> emit(as).run(consumer)
        }.flatMap(identity _)
    }

  def collect[R, A](producer: Producer[R, A])(implicit m: Member[Writer[A, ?], R]): Eff[R, Unit] =
    producer.run {
      case One(a) => tell(a)
      case Done   => pure(())
    }

  def flatMap[R, A, B](producer: Producer[R, A])(f: A => Producer[R, B]): Producer[R, B] =
    new Generator[R, B, Unit] {
      def run(consumer: Consumer[R, B]): Eff[R, Unit] =
        producer.run {
          case One(a) => f(a).run(consumer)
          case Done   => pure(())
        }
    }

  def map[R, A, B](producer: Producer[R, A])(f: A => B): Producer[R, B] =
    flatMap(producer)(a => yielded(f(a)))

  def flatten[R, A](producer: Producer[R, Producer[R, A]]): Producer[R, A] =
    flatMap(producer)(identity)

  def filter[R, A](producer: Producer[R, A])(f: A => Boolean): Producer[R, A] =
    flatMap(producer)((a: A) => if (f(a)) yielded (a) else done)

  def flattenList[R, A](producer: Producer[R, List[A]]): Producer[R, A] =
    flatMap(producer)(emit)

  def chunk[R, A](size: Int)(producer: Producer[R, A]): Producer[R, List[A]] =
    new Generator[R, List[A], Unit] {
      def run(consumer: Consumer[R, List[A]]): Eff[R, Unit] = {
        val elements = new collection.mutable.ListBuffer[A]
        producer.run {
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

  def sequence[R, F[_], A](n: Int)(producer: Producer[R, Eff[R, A]])(implicit f: F |= R) =
    chunk(n)(producer).flatMap { actions => emitEff(Eff.sequenceA(actions)) }

}

trait Transducers {
  import Generator.yielded

  def filter[R, A, B](f: A => Boolean): Transducer[R, A, A] =
    (p: Producer[R, A]) => Generator.filter(p)(f)

  def map[R, A, B](f: A => B): Transducer[R, A, B] =
    (p: Producer[R, A]) => Generator.map(p)(f)

  def receive[R, A, B](f: A => Producer[R, B]): Transducer[R, A, B] =
    (p: Producer[R, A]) => Generator.flatMap(p)(f)

  def transducer[R, A, B](f: A => B): Transducer[R, A, B] =
    (p: Producer[R, A]) => p.map(f)

  def receiveOr[R, A, B](f: A => Producer[R, B])(or: =>Producer[R, B]): Transducer[R, A, B] =
    (p: Producer[R, A]) => new Generator[R, B, Unit] {
      def run(consumer: Consumer[R, B]): Eff[R, Unit] =
        p.run {
          case One(a) => f(a).run(consumer)
          case Done   => or.run(consumer)
        }
    }

  def receiveOption[R, A, B]: Transducer[R, A, Option[A]] =
    receiveOr[R, A, Option[A]]((a: A) => yielded(Option(a)))(yielded(None))

}

object Transducers extends Transducers