package org.atnos

import cats._
import cats.implicits._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.origami.{Fold, Sink}
import org.atnos.origami.fold._

package object producer {

  type Transducer[M[_], A, B] = Producer[M, A] => Producer[M, B]

  type TransducerFx[R, A, B] = Transducer[Eff[R, ?], A, B]

  type ProducerFx[R, A] = Producer[Eff[R, ?], A]

  type FoldFx[R, A, B] = Fold[Eff[R, ?], A, B]

  type SinkFx[R, A] = Sink[Eff[R, ?], A]

  object producers extends Producers

  object transducers extends Transducers

  implicit class ProducerOps[M[_] : MonadDefer, A](p: Producer[M, A]) {
    def >(p2: Producer[M, A]): Producer[M, A] =
      p append p2

    def |>[B](t: Transducer[M, A, B]): Producer[M, B] =
      pipe(t)

    def pipe[B](t: Transducer[M, A, B]): Producer[M, B] =
      Producer.pipe(p, t)

    def fold[B, S](start: M[S], f: (S, A) => M[S], end: S => M[B]): M[B] =
      Producer.fold(p)(start, f, end)

    def to[B](f: Fold[M, A, B]): M[B] =
      fold[B, f.S](f.start, f.fold, f.end)

    def foldLeft[S](init: S)(f: (S, A) => S): M[S] =
      Producer.fold(p)(MonadDefer[M].pure(init), (s: S, a: A) => MonadDefer[M].pure(f(s, a)), (s: S) => MonadDefer[M].pure(s))

    def foldMonoid(implicit m: Monoid[A]): M[A] =
      foldLeft(Monoid[A].empty)(Monoid[A].combine)

    def observe[S](start: M[S], f: (S, A) => M[S], end: S => M[Unit]): Producer[M, A] =
      Producer.observe(p)(start, f, end)

    def observe(f: Fold[M, A, Unit]): Producer[M, A] =
      Producer.observe(p)(f.start, f.fold, f.end)

    def runLast: M[Option[A]] =
      Producer.runLast(p)

    def drain: M[Unit] =
      Producer.runLast(p).void

    def runList: M[List[A]] =
      Producer.runList(p)

    def repeat: Producer[M, A] =
      Producer.repeat(p)

  }

  implicit class ProducerEffOps[R :_Safe, A](p: Producer[Eff[R, ?], A]) {
    def into[U](implicit intoPoly: IntoPoly[R, U], s: _Safe[U]): Producer[Eff[U, ?], A] =
      Producer.into(p)

    def fold[B](f: Fold[Id, A, B]): Eff[R, B] =
      p.to(f.into[Eff[R, ?]])

    def fold[B, S](start: Eff[R, S], f: (S, A) => Eff[R, S], end: S => Eff[R, B]): Eff[R, B] =
      Producer.fold(p)(start, f, end)

    def andFinally(last: Eff[R, Unit]): Producer[Eff[R, ?], A] =
      Producer(p.run.addLast(last))
  }

  implicit class ProducerEffSequenceOps[R :_Safe, A](p: Producer[Eff[R, ?], Eff[R, A]]) {
    def sequence[F[_]](n: Int): Producer[Eff[R, ?], A] =
      p |> transducers.sequence[R, F, A](n)
  }

  implicit class ProducerListOps[M[_] : MonadDefer, A](p: Producer[M, List[A]]) {
    def flattenList: Producer[M, A] =
      p |> transducers.flattenList
  }

  implicit class ProducerSeqOps[M[_] : MonadDefer, A](p: Producer[M, Seq[A]]) {
    def flattenSeq: Producer[M, A] =
      p |> transducers.flattenSeq
  }

  implicit class ProducerFlattenOps[M[_] : MonadDefer, A](p: Producer[M, Producer[M, A]]) {
    def flatten: Producer[M, A] =
      p |> transducers.flatten
  }

  implicit class ProducerTransducerOps[M[_] : MonadDefer, A](p: Producer[M, A]) {
    def filter(f: A => Boolean): Producer[M, A] =
      p |> transducers.filter(f)

    def sliding(n: Int): Producer[M, List[A]] =
      p |> transducers.sliding(n)

    def chunk(n: Int): Producer[M, A] =
      p |> transducers.chunk(n)

    def receiveOr[B](f: A => Producer[M, B])(or: =>Producer[M, B]): Producer[M, B] =
      p |> transducers.receiveOr(f)(or)

    def receiveOption[B]: Producer[M, Option[A]] =
      p |> transducers.receiveOption

    def drop(n: Int): Producer[M, A] =
      p |> transducers.drop(n)

    def dropRight(n: Int): Producer[M, A] =
      p |> transducers.dropRight(n)

    def take(n: Int): Producer[M, A] =
      p |> transducers.take(n)

    def takeWhile(f: A => Boolean): Producer[M, A] =
      p |> transducers.takeWhile(f)

    def zipWithPrevious: Producer[M, (Option[A], A)] =
      p |> transducers.zipWithPrevious

    def zipWithNext: Producer[M, (A, Option[A])] =
      p |> transducers.zipWithNext

    def zipWithPreviousAndNext: Producer[M, (Option[A], A, Option[A])] =
      p |> transducers.zipWithPreviousAndNext

    def zipWithPreviousN(n: Int): Producer[M, (List[A], A)] =
      p |> transducers.zipWithPreviousN(n)

    def zipWithNextN(n: Int): Producer[M, (A, List[A])] =
      p |> transducers.zipWithNextN(n)

    def zipWithPreviousAndNextN(n: Int): Producer[M, (List[A], A, List[A])] =
      p |> transducers.zipWithPreviousAndNextN(n)

    def zipWithIndex: Producer[M, (A, Int)] =
      p |> transducers.zipWithIndex

     def intersperse(a: A): Producer[M, A] =
       p |> transducers.intersperse(a: A)

    def first: Producer[M, A] =
      p |> transducers.first

    def last: Producer[M, A] =
      p |> transducers.last

    def scan[B](start: B)(f: (B, A) => B): Producer[M, B] =
      p |> transducers.scan(start)(f)

    def scan1(f: (A, A) => A): Producer[M, A] =
      p |> transducers.scan1(f)

    def reduce(f: (A, A) => A): Producer[M, A] =
      p |> transducers.reduce(f)

    def reduceSemigroup(implicit semi: Semigroup[A]): Producer[M, A] =
      p |> transducers.reduceSemigroup

    def reduceMonoid(implicit monoid: Monoid[A]): Producer[M, A] =
      p |> transducers.reduceMonoid

    def reduceMap[B : Monoid](f: A => B): Producer[M, B] =
      p |> transducers.reduceMap[M, A, B](f)

    def mapEval[B](f: A => M[B]): Producer[M, B] =
      p |> transducers.mapEval[M, A, B](f)
  }

  implicit class TransducerOps[M[_] : MonadDefer, A, B](t: Transducer[M, A, B]) {
    def |>[C](next: Transducer[M, B, C]): Transducer[M, A, C] =
      (p: Producer[M, A]) => next(t(p))

    def filter(predicate: B => Boolean): Transducer[M, A, B] = (producer: Producer[M, A]) =>
      t(producer).filter(predicate)

    def map[C](f: B => C): Transducer[M, A, C] = (producer: Producer[M, A]) =>
      t(producer).map(f)

  }

  implicit class ProducerResourcesOps[R :_Safe, A](p: Producer[Eff[R, ?], A]) {

    def `finally`(e: Eff[R, Unit]): Producer[Eff[R, ?], A] =
      p.andFinally(e)

    def attempt: Producer[Eff[R, ?], Throwable Either A] =
      Producer[Eff[R, ?], Throwable Either A](SafeInterpretation.attempt(p.run) map {
        case Right(Done()) => Done()
        case Right(One(a)) => One(Either.right(a))
        case Right(More(as, next)) => More(as.map(Either.right), next.map(Either.right))
        case Left(t) => One(Either.left(t))
      })
  }

  def bracket[R :_Safe, A, B, C](open: Eff[R, A])(step: A => Producer[Eff[R, ?], B])(close: A => Eff[R, C]): Producer[Eff[R, ?], B] = {

    Producer[Eff[R, ?], B] {
      open flatMap { resource =>
        (step(resource) `finally` close(resource).map(_ => ())).run
      }
    }
  }
}
