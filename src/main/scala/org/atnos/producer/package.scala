package org.atnos

import cats.data.Xor
import org.atnos.eff._
import eval._
import safe._

package object producer {

  type Transducer[R, A, B] = Producer[R, A] => Producer[R, B]

  object transducers extends Transducers

  implicit class ProducerOps[R, A](p: Producer[R, A]) {
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

    def into[U](implicit intoPoly: IntoPoly[R, U]): Producer[U, A] =
      Producer.into(p)
  }

  implicit class LazyProducerOps[R :_eval, A](p: Producer[R, A]) {
    def repeat: Producer[R, A] =
      Producer.repeat(p)
  }

  implicit class ProducerListOps[R, A](p: Producer[R, List[A]]) {
    def flattenList: Producer[R, A] =
      Producer.flattenList(p)
  }

  implicit class ProducerFlattenOps[R, A](p: Producer[R, Producer[R, A]]) {
    def flatten: Producer[R, A] =
      Producer.flatten(p)
  }

  implicit class ProducerTransducerOps[R, A](p: Producer[R, A]) {
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

    def zipWithPrevious: Producer[R, (Option[A], A)] =
      p |> transducers.zipWithPrevious

    def zipWithNext: Producer[R, (A, Option[A])] =
      p |> transducers.zipWithNext

    def zipWithPreviousAndNext: Producer[R, (Option[A], A, Option[A])] =
      p |> transducers.zipWithPreviousAndNext

    def zipWithIndex: Producer[R, (A, Int)] =
      p |> transducers.zipWithIndex
  }

  implicit class ProducerResourcesOps[R, A](p: Producer[R, A])(implicit s: Safe <= R) {
    def `finally`(e: Eff[R, Unit]): Producer[R, A] =
      Producer[R, A](andFinally(p.run, e))

    def attempt: Producer[R, Throwable Xor A] =
      Producer[R, Throwable Xor A](SafeInterpretation.attempt(p.run) map {
        case Xor.Right(Done()) => Done()
        case Xor.Right(One(a)) => One(Xor.Right(a))
        case Xor.Right(More(as, next)) => More(as.map(Xor.right), next.map(Xor.right))

        case Xor.Left(t) => One(Xor.left(t))
      })
  }

  def bracket[R, A, B, C](open: Eff[R, A])(step: A => Producer[R, B])(close: A => Eff[R, C])(implicit s: Safe <= R): Producer[R, B] =
    Producer[R, B](SafeInterpretation.bracket(open)((a: A) => step(a).run)(close))

}
