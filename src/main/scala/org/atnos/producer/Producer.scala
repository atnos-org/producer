package org.atnos.producer

import cats._, data._
import cats.implicits._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import Producer._

sealed trait Stream[R, A]
case class Done[R, A]() extends Stream[R, A]
case class One[R, A](a: A) extends Stream[R, A]
case class More[R, A](a: A, run: Producer[R, A]) extends Stream[R, A]

case class Producer[R, A](run: Eff[R, Stream[R, A]]) {

  def flatMap[B](f: A => Producer[R, B]): Producer[R, B] =
    cata[R, A, B](this)(
      done[R, B],
      (a: A) => f(a),
      (a: A, as: Producer[R, A]) => f(a) append as.flatMap(f))

  def map[B](f: A => B): Producer[R, B] =
    flatMap(a => one(f(a)))

  def append(other: =>Producer[R, A]): Producer[R, A] =
    Producer(run flatMap {
      case Done() => other.run
      case One(a) => pure(More(a, other))
      case More(a, as) => pure(More(a, as append other))
    })

  def zip[B](other: Producer[R, B]): Producer[R, (A, B)] =
    Producer(run flatMap {
      case Done() => done.run
      case One(a) =>
        peek(other).flatMap {
          case (Some(b), bs) => one((a, b)).run
          case (None, _)     => done.run
        }

      case More(a, as) =>
        peek(other).flatMap {
          case (Some(b), bs) => (one((a, b)) append (as zip bs)).run
          case (None, _)     => done.run
        }
    })
}


object Producer {

  def done[R, A]: Producer[R, A] =
    Producer[R, A](pure(Done()))

  def one[R, A](a: A): Producer[R, A] =
    Producer[R, A](pure(One(a)))

  def emit[R, A](elements: List[A]): Producer[R, A] =
    elements match {
      case Nil => done[R, A]
      case a :: as => one[R, A](a) append emit(as)
    }

  def eval[R, A](a: Eff[R, A]): Producer[R, A] =
    Producer(a.map(One(_)))

  def emitEff[R, A](elements: Eff[R, List[A]]): Producer[R, A] =
    Producer(elements flatMap {
      case Nil     => done[R, A].run
      case a :: as => (one(a) append emit(as)).run
    })

  implicit def FoldableProducer: Foldable[Producer[NoFx, ?]] = new Foldable[Producer[NoFx, ?]] {
    def foldLeft[A, B](fa: Producer[NoFx, A], b: B)(f: (B, A) => B): B = {
      var s = b
      fa.run.run match {
        case Done() => Eff.pure(())
        case One(a) => s = f(s, a); Eff.pure(())
        case More(a, as) => s = f(s, a); s = foldLeft(as, s)(f); Eff.pure(())
      }
      s
    }

    def foldRight[A, B](fa: Producer[NoFx, A], lb: Eval[B])(f: (A, Eval[B]) => Eval[B]): Eval[B] = {
      var s = lb
      fa.run.run match {
        case Done() => Eff.pure(())
        case One(a) => s = s >> f(a, s); Eff.pure(())
        case More(a, as) => s >> f(a, s) >> foldRight(as, s)(f); Eff.pure(())
      }
      s
    }
  }

  def fold[R, A, B, S](producer: Producer[R, A])(start: Eff[R, S], f: (S, A) => S, end: S => Eff[R, B]): Eff[R, B] =
    start.flatMap { s =>
      def go(p: Producer[R, A], s: S): Eff[R, S] =
        peek(p) flatMap {
          case (None, _)     => pure[R, S](s)
          case (Some(a), as) => go(as, f(s, a))
        }

      go(producer, s)
    } flatMap end

  def collect[R, A](producer: Producer[R, A])(implicit m: Member[Writer[A, ?], R]): Eff[R, Unit] =
    producer.run flatMap {
      case Done() => pure(())
      case One(a) => tell(a)
      case More(a, as) => tell(a) >> collect(as)
    }

  def into[R, U, A](producer: Producer[R, A])(implicit intoPoly: IntoPoly[R, U]): Producer[U, A] =
    Producer(producer.run.into[U] flatMap {
      case Done() => done[U, A].run
      case One(a) => one[U, A](a).run
      case More(a, as) => pure(More[U, A](a, into(as)))
    })

  implicit def GenMonad[R]: Monad[Producer[R, ?]] = new Monad[Producer[R, ?]] {
    def flatMap[A, B](fa: Producer[R, A])(f: A => Producer[R, B]): Producer[R, B] =
      fa.flatMap(f)

    def pure[A](a: A): Producer[R, A] =
      one(a)
  }

  def empty[R, A]: Producer[R, A] =
    done

  def pipe[R, A, B](p: Producer[R, A], t: Transducer[R, A, B]): Producer[R, B] =
    t(p)

  def filter[R, A](producer: Producer[R, A])(f: A => Boolean): Producer[R, A] =
    producer.flatMap((a: A) => if (f(a)) one(a) else done)

  def flatten[R, A](producer: Producer[R, Producer[R, A]]): Producer[R, A] =
    producer flatMap identity

  def chunk[R, A](size: Int)(producer: Producer[R, A]): Producer[R, List[A]] = {

    def go(p: Producer[R, A], elements: Vector[A]): Producer[R, List[A]] =
      Producer[R, List[A]](
      peek(p).flatMap {
        case (Some(a), as) =>
          val es = elements :+ a
          if (es.size == size) (one(es.toList) append go(as, Vector.empty)).run
          else                 go(as, es).run

        case (None, _) =>
          one(elements.toList).run
      })

    go(producer, Vector.empty)
  }

  def peek[R, A](producer: Producer[R, A]): Eff[R, (Option[A], Producer[R, A])] =
    producer.run map {
      case Done() => (None, done[R, A])
      case One(a) => (Option(a), done[R, A])
      case More(a, as) => (Option(a), as)
    }

  def flattenList[R, A](producer: Producer[R, List[A]]): Producer[R, A] =
    producer.flatMap(emit)

  def sequence[R, F[_], A](n: Int)(producer: Producer[R, Eff[R, A]])(implicit f: F |= R) =
    chunk(n)(producer).flatMap { actions => emitEff(Eff.sequenceA(actions)) }

  private[producer] def cata[R, A, B](producer: Producer[R, A])(onDone: Producer[R, B], onOne: A => Producer[R, B], onMore: (A, Producer[R, A]) => Producer[R, B]): Producer[R, B] =
    Producer[R, B](producer.run.flatMap {
      case Done() => onDone.run
      case One(a) => onOne(a).run
      case More(a, as) => onMore(a, as).run
    })

}


trait Transducers {
  def filter[R, A, B](f: A => Boolean): Transducer[R, A, A] =
    (p: Producer[R, A]) => Producer.filter(p)(f)

  def map[R, A, B](f: A => B): Transducer[R, A, B] =
    (p: Producer[R, A]) => p map f

  def receive[R, A, B](f: A => Producer[R, B]): Transducer[R, A, B] =
    (p: Producer[R, A]) => p.flatMap(f)

  def transducer[R, A, B](f: A => B): Transducer[R, A, B] =
    (p: Producer[R, A]) => p.map(f)

  def receiveOr[R, A, B](f: A => Producer[R, B])(or: =>Producer[R, B]): Transducer[R, A, B] =
    cata_[R, A, B](
      or,
      (a: A) => f(a),
      (a: A, as: Producer[R, A]) => f(a))

  def receiveOption[R, A, B]: Transducer[R, A, Option[A]] =
    receiveOr[R, A, Option[A]]((a: A) => one(Option(a)))(one(None))

  def drop[R, A](n: Int): Transducer[R, A, A] =
    cata_[R, A, A](
      done[R, A],
      (a: A) => if (n <= 0) one(a) else done,
      (a: A, as: Producer[R, A]) =>
        if (n == 0) one(a) append as
        else if (n == 1) as
        else  drop(n - 1)(as))

  def dropRight[R, A](n: Int): Transducer[R, A, A] =
    (producer: Producer[R, A]) => {
       def go(p: Producer[R, A], elements: Vector[A]): Producer[R, A] =
         Producer(peek(p).flatMap {
           case (Some(a), as) =>
             val es = elements :+ a
             if (es.size >= n) (emit(es.toList) append go(as, Vector.empty[A])).run
             else go(as, es).run

           case (None, _) =>
             if (elements.size <= n) done.run
             else emit(elements.toList).run

         })
      go(producer, Vector.empty[A])
    }

  def take[R, A](n: Int): Transducer[R, A, A] =
    cata_[R, A, A](
      done[R, A],
      (a: A) => if (n <= 0) done else one(a),
      (a: A, as: Producer[R, A]) =>
        if (n == 0) done
        else if (n == 1) one(a)
        else  one(a) append take(n - 1)(as))

  def zipWithPrevious[R, A]: Transducer[R, A, (Option[A], A)] =
    (p: Producer[R, A]) =>
      (one(None: Option[A]) append (p |> dropRight(1)).map(Option(_))) zip p

  def zipWithNext[R, A]: Transducer[R, A, (A, Option[A])] =
    (p: Producer[R, A]) =>
      p zip ((p |> drop(1)).map(Option(_)) append one(None))

  def zipWithPreviousAndNext[R, A]: Transducer[R, A, (Option[A], A, Option[A])] =
    (p: Producer[R, A]) =>
      ((p |> zipWithPrevious) zip (p |> zipWithNext)).map { case ((p, a), (_, n)) => (p, a, n) }

  private def cata_[R, A, B](onDone: Producer[R, B], onOne: A => Producer[R, B], onMore: (A, Producer[R, A]) => Producer[R, B]): Transducer[R, A, B] =
    (producer: Producer[R, A]) => cata(producer)(onDone, onOne, onMore)
}

object Transducers extends Transducers
