package org.atnos

import cats.{Eval, Monoid, Semigroup}
import cats.data.Xor
import org.atnos.eff._
import org.atnos.eff.all._

package object producer {

  type Transducer[R, A, B] = Producer[R, A] => Producer[R, B]

  object producers extends Producers
  object transducers extends Transducers

  implicit class ProducerOps[R :_safe, A](p: Producer[R, A]) {
    def filter(f: A => Boolean): Producer[R, A] =
      Producer.filter(p)(f)

    def sliding(n: Int): Producer[R, List[A]] =
      Producer.sliding(n)(p)

    def chunk(n: Int): Producer[R, A] =
      Producer.chunk(n)(p)

    def >(p2: Producer[R, A]): Producer[R, A] =
      p append p2

    def |>[B](t: Transducer[R, A, B]): Producer[R, B] =
      pipe(t)

    def pipe[B](t: Transducer[R, A, B]): Producer[R, B] =
      Producer.pipe(p, t)

    def into[U](implicit intoPoly: IntoPoly[R, U], s: Safe |= U): Producer[U, A] =
      Producer.into(p)

    def fold[B, S](start: Eff[R, S], f: (S, A) => S, end: S => Eff[R, B]): Eff[R, B] =
      Producer.fold(p)(start, f, end)

    def observe[S](start: Eff[R, S], f: (S, A) => S, end: S => Eff[R, Unit]): Producer[R, A] =
      Producer.observe(p)(start, f, end)

    def runLast: Eff[R, Option[A]] =
      Producer.runLast(p)

    def runList: Eff[R, List[A]] =
      Producer.runList(p)

    def repeat: Producer[R, A] =
      Producer.repeat(p)
  }

  implicit class ProducerListOps[R :_safe, A](p: Producer[R, List[A]]) {
    def flattenList: Producer[R, A] =
      Producer.flattenList(p)
  }

  implicit class ProducerSeqOps[R :_safe, A](p: Producer[R, Seq[A]]) {
    def flattenSeq: Producer[R, A] =
      Producer.flattenSeq(p)
  }

  implicit class ProducerFlattenOps[R :_safe, A](p: Producer[R, Producer[R, A]]) {
    def flatten: Producer[R, A] =
      Producer.flatten(p)
  }

  implicit class ProducerTransducerOps[R :_safe, A](p: Producer[R, A]) {
    def receiveOr[B](f: A => Producer[R, B])(or: =>Producer[R, B]): Producer[R, B] =
      p |> transducers.receiveOr(f)(or)

    def receiveOption[B]: Producer[R, Option[A]] =
      p |> transducers.receiveOption

    def drop(n: Int): Producer[R, A] =
      p |> transducers.drop(n)

    def dropRight(n: Int): Producer[R, A] =
      p |> transducers.dropRight(n)

    def take(n: Int): Producer[R, A] =
      p |> transducers.take(n)

    def takeWhile(f: A => Boolean): Producer[R, A] =
      p |> transducers.takeWhile(f)

    def zipWithPrevious: Producer[R, (Option[A], A)] =
      p |> transducers.zipWithPrevious

    def zipWithNext: Producer[R, (A, Option[A])] =
      p |> transducers.zipWithNext

    def zipWithPreviousAndNext: Producer[R, (Option[A], A, Option[A])] =
      p |> transducers.zipWithPreviousAndNext

    def zipWithIndex: Producer[R, (A, Int)] =
      p |> transducers.zipWithIndex

     def intersperse(a: A): Producer[R, A] =
       p |> transducers.intersperse(a: A)

    def first: Producer[R, A] =
      p |> transducers.first

    def last: Producer[R, A] =
      p |> transducers.last

    def scan[B](start: B)(f: (B, A) => B): Producer[R, B] =
      p |> transducers.scan(start)(f)

    def scan1(f: (A, A) => A): Producer[R, A] =
      p |> transducers.scan1(f)

    def reduce(f: (A, A) => A): Producer[R, A] =
      p |> transducers.reduce(f)

    def reduceSemigroup(implicit semi: Semigroup[A]): Producer[R, A] =
      p |> transducers.reduceSemigroup

    def reduceMonoid(implicit monoid: Monoid[A]): Producer[R, A] =
      p |> transducers.reduceMonoid

    def reduceMap[B : Monoid](f: A => B): Producer[R, B] =
      p |> transducers.reduceMap[R, A, B](f)
  }

  implicit class ProducerResourcesOps[R :_safe, A](p: Producer[R, A])(implicit s: Safe <= R) {
    def andFinally(e: Eff[R, Unit]): Producer[R, A] =
      Producer[R, A](p.run flatMap {
        case Done() => safe.andFinally(Producer.done[R, A].run, e)
        case One(a) => safe.andFinally(Producer.one[R, A](a).run, e)
        case More(as, next) => protect(More(as, ProducerResourcesOps(next).andFinally(e)))
      })

    def `finally`(e: Eff[R, Unit]): Producer[R, A] =
      p.andFinally(e)

    def attempt: Producer[R, Throwable Xor A] =
      Producer[R, Throwable Xor A](SafeInterpretation.attempt(p.run) map {
        case Xor.Right(Done()) => Done()
        case Xor.Right(One(a)) => One(Xor.Right(a))
        case Xor.Right(More(as, next)) => More(as.map(Xor.right), next.map(Xor.right))

        case Xor.Left(t) => One(Xor.left(t))
      })
  }

  def bracket[R :_safe, A, B, C](open: Eff[R, A])(step: A => Producer[R, B])(close: A => Eff[R, C])(implicit s: Safe <= R): Producer[R, B] =
    Producer[R, B] {
      open flatMap { resource =>
        (step(resource) `finally` close(resource).map(_ => ())).run
      }
    }
}
