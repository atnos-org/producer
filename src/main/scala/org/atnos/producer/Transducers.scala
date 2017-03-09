package org.atnos.producer

import cats._
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import Producer._
import cats.data.State
import cats.implicits._

trait Transducers {

  def id[M[_] : MonadDefer, A, B]: Transducer[M, A, A] =
    (p: Producer[M, A]) => p

  def filter[M[_] : MonadDefer, A, B](f: A => Boolean): Transducer[M, A, A] =
    (p: Producer[M, A]) => Producer.filter(p)(f)

  def receive[M[_] : MonadDefer, A, B](f: A => Producer[M, B]): Transducer[M, A, B] =
    (p: Producer[M, A]) => p.flatMap(f)

  def transducer[M[_] : MonadDefer, A, B](f: A => B): Transducer[M, A, B] =
    (p: Producer[M, A]) => p.map(f)

  def producerState[M[_] : MonadDefer, A, B, S](start: S, last: Option[S => Producer[M, B]] = None)(f: (A, S) => (Producer[M, B], S)): Transducer[M, A, B] =
    (producer: Producer[M, A]) => {
      def go(p: Producer[M, A], s: S): Producer[M, B] =
        Producer(p.run flatMap {
          case Done() =>
            last.map(_(s).run).getOrElse(done.run)

          case One(a) =>
            val (b, news) = f(a, s)
            last match {
              case None => b.run
              case Some(l) => (b append l(news)).run
            }

          case More(as, next) =>
            val (bs, news) = as.drop(1).foldLeft(f(as.head, s)) { case ((pb, s1), a) =>
              val (pb1, s2) = f(a, s1)
              (pb append pb1, s2)
            }
            (bs append go(next, news)).run
        })
      go(producer, start)
    }

  def producerStateEff[R :_Safe, A, B, S](start: S, last: Option[S => Producer[Eff[R, ?], B]] = None)(f: (A, S) => Eff[R, (Producer[Eff[R, ?], B], S)]): Transducer[Eff[R, ?], A, B] =
    (producer: Producer[Eff[R, ?], A]) => {
      implicit val monad: MonadDefer[Eff[R, ?]] = MonadDefer.MonadDeferEffSafe[R]

      def go(p: Producer[Eff[R, ?], A], s: S): Producer[Eff[R, ?], B] =
        Producer(p.run flatMap {
          case Done() =>
            last match {
              case Some(l) => l(s).run
              case None    => done.run
            }

          case One(a) =>
            f(a, s).flatMap { case (b, news) =>
              last match {
                case None => b.run
                case Some(l) => (b append l(news)).run
              }
            }

          case More(as, next) =>
            val res = as.drop(1).foldLeft(f(as.head, s)) { (res, a) =>
              res.flatMap { case (pb, s1) =>
                f(a, s1).map { case (pb1, s2) =>
                  (pb append pb1, s2)
                }
              }
            }
            producers.eval(res.map { case (bs, news) => bs append go(next, news) }).flatten.run
        })

      go(producer, start)
    }

  def state[M[_] : MonadDefer, A, B, S](start: S)(f: (A, S) => (B, S)): Transducer[M, A, B] = (producer: Producer[M, A]) => {
    def go(p: Producer[M, A], s: S): Producer[M, B] =
      Producer(p.run flatMap {
        case Done() => done.run
        case One(a) => one(f(a, s)._1).run
        case More(as, next) =>
          val (bs, news) = as.foldLeft((List[B](), s)) { case ((bs1, s1), a) =>
            val (b, s2) = f(a, s1)
            (bs1 :+ b, s2)
          }
          (emit(bs) append go(next, news)).run
      })
    go(producer, start)
  }

  def stateEffEval[R :_Safe, A, B, S](start: S)(f: (A, S) => Eff[R, (B, S)]): Transducer[Eff[R, ?], A, B] = (producer: Producer[Eff[R, ?], A]) => {
    implicit val monad: MonadDefer[Eff[R, ?]] = MonadDefer.MonadDeferEffSafe[R]

    def go(p: Producer[Eff[R, ?], A], s: S): Producer[Eff[R, ?], B] =
      Producer(p.run flatMap {
        case Done() => done.run
        case One(a) => oneEval(f(a, s).map(_._1)).run
        case More(as, next) =>
          type U = Fx.prepend[State[S, ?], R]
          val traversed: Eff[U, List[B]] =
            as.traverse { a: A =>
              for {
                s1 <- get[U, S]
                bs <- f(a, s1).into[U]
                _ <- put[U, S](bs._2)
              } yield bs._1
            }

          val result: Eff[R, (List[B], S)] = traversed.runState(s)
          result.flatMap { case (bs, news) =>
            (emit(bs) append go(next, news)).run
          }
      })

    go(producer, start)
  }

  def stateEff[R :_Safe, A, B, S](start: S)(f: (A, S) => (Eff[R, B], S)): Transducer[Eff[R, ?], A, Eff[R, B]] = (producer: Producer[Eff[R, ?], A]) => {
    implicit val monad: MonadDefer[Eff[R, ?]] = MonadDefer.MonadDeferEffSafe[R]

    def go(p: Producer[Eff[R, ?], A], s: S): Producer[Eff[R, ?], Eff[R, B]] =
      Producer(p.run flatMap {
        case Done() => done.run
        case One(a) => one(f(a, s)._1).run
        case More(as, next) =>
          as match {
            case Nil => go(next, s).run
            case a :: rest =>
              val (b, s1) = f(a, s)
              (one(b) append go(emit(rest) append next, s1)).run
          }
      })

    go(producer, start)
  }

  def receiveOr[M[_] : MonadDefer, A, B](f: A => Producer[M, B])(or: =>Producer[M, B]): Transducer[M, A, B] =
    cata_[M, A, B](
      or,
      (a: A) => f(a),
      (as: List[A], next: Producer[M, A]) => as.headOption.map(f).getOrElse(or))

  def receiveOption[M[_] : MonadDefer, A, B]: Transducer[M, A, Option[A]] =
    receiveOr[M, A, Option[A]]((a: A) => one(Option(a)))(one(None))

  def drop[M[_] : MonadDefer, A](n: Int): Transducer[M, A, A] =
    cata_[M, A, A](
      done[M, A],
      (a: A) => if (n <= 0) one(a) else done,
      (as: List[A], next: Producer[M, A]) =>
        if (n < as.size) emit(as.drop(n)) append next
        else next |> drop(n - as.size))

  def dropRight[M[_] : MonadDefer, A](n: Int): Transducer[M, A, A] =
    (producer: Producer[M, A]) => {
      def go(p: Producer[M, A], elements: Vector[A]): Producer[M, A] =
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

  def take[M[_] : MonadDefer, A](n: Int): Transducer[M, A, A] =
    (producer: Producer[M, A]) => {
      def go(p: Producer[M, A], i: Int): Producer[M, A] =
        if (i <= 0) done
        else
          Producer(p.run flatMap {
            case Done() => done.run
            case One(a) => one(a).run
            case More(as, next) =>
              if (as.size <= i) (emit(as) append go(next, i - as.size)).run
              else              emit(as take i).run
          })

      go(producer, n)
    }

  def takeWhile[M[_] : MonadDefer, A](f: A => Boolean): Transducer[M, A, A] =
    cata_[M, A, A](
      done[M, A],
      (a: A) => if (f(a)) one(a) else done,
      (as: List[A], next: Producer[M, A]) =>
        as.takeWhile(f) match {
          case Nil => done
          case some => emit(some) append next.takeWhile(f)
        })

  def first[M[_] : MonadDefer, A]: Transducer[M, A, A] = (producer: Producer[M, A]) => {
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(a) => one(a).run
      case More(as, next) => as.headOption.map(fr => one(fr)).getOrElse(done).run
    })
  }

  def last[M[_] : MonadDefer, A]: Transducer[M, A, A] = (producer: Producer[M, A]) => {
    def go(p: Producer[M, A], previous: Option[A]): Producer[M, A] =
      Producer(p.run flatMap {
        case Done() => previous.map(pr => one(pr)).getOrElse(done).run
        case One(a) => one(a).run
        case More(as, next) => go(next, as.lastOption).run
      })

    go(producer, None)
  }

  def scan[M[_] : MonadDefer, A, B](start: B)(f: (B, A) => B): Transducer[M, A, B] = (producer: Producer[M, A]) => {
    def go(p: Producer[M, A], previous: B): Producer[M, B] =
      Producer(p.run flatMap {
        case Done() => done.run
        case One(a) => one(f(previous, a)).run
        case More(as, next) =>
          val scanned = as.scanLeft(previous)(f).drop(1)
          (emit(scanned) append go(next, scanned.lastOption.getOrElse(previous))).run
      })

    one(start) append go(producer, start)
  }

  def scan1[M[_] : MonadDefer, A](f: (A, A) => A): Transducer[M, A, A] = (producer: Producer[M, A]) =>
    producer.first.flatMap(a => producer.drop(1).scan(a)(f))

  def reduceSemigroup[M[_] : MonadDefer, A : Semigroup]: Transducer[M, A, A] =
    reduce(Semigroup[A].combine)

  def reduce[M[_] : MonadDefer, A](f: (A, A) => A): Transducer[M, A, A] = (producer: Producer[M, A]) =>
    last.apply(scan1(f).apply(producer))

  def reduceMonoid[M[_] : MonadDefer, A : Monoid]: Transducer[M, A, A] =
    reduceSemigroup[M, A]

  def reduceMap[M[_] : MonadDefer, A, B : Monoid](f: A => B): Transducer[M, A, B] = (producer: Producer[M, A]) =>
    reduceMonoid[M, B].apply(transducer(f).apply(producer))

  def zipWithPrevious[M[_] : MonadDefer, A]: Transducer[M, A, (Option[A], A)] =
    (producer: Producer[M, A]) => {
      def go(p: Producer[M, A], previous: Option[A]): Producer[M, (Option[A], A)] =
        Producer(peek(p) flatMap {
          case (Some(a), as) => (one((previous, a)) append go(as, Option(a))).run
          case (None, _)     => done.run
        })

      go(producer, None)
    }

  def zipWithNext[M[_] : MonadDefer, A]: Transducer[M, A, (A, Option[A])] =
    (producer: Producer[M, A]) => {
      def go(p: Producer[M, A], previous: A): Producer[M, (A, Option[A])] =
        Producer(peek(p) flatMap {
          case (Some(a), as) => (one((previous, Option(a))) append go(as, a)).run
          case (None, _)     => one[M, (A, Option[A])]((previous, None)).run
        })
      Producer(peek(producer) flatMap {
        case (Some(a), as) => go(as, a).run
        case (None, _) => done.run
      })
    }

  def zipWithPreviousAndNext[M[_] : MonadDefer, A]: Transducer[M, A, (Option[A], A, Option[A])] =
    (p: Producer[M, A]) =>
      ((p |> zipWithPrevious) zip (p |> zipWithNext)).map { case ((prev, a), (_, next)) => (prev, a, next) }


  def zipWithIndex[M[_] : MonadDefer, A]: Transducer[M, A, (A, Int)] =
    zipWithState[M, A, Int](0)((_, n: Int) => n + 1)

  def zipWithState[M[_] : MonadDefer, A, B](b: B)(f: (A, B) => B): Transducer[M, A, (A, B)] =
    (producer: Producer[M, A]) => {
      def go(p: Producer[M, A], state: B): Producer[M, (A, B)] =
        Producer[M, (A, B)] {
          producer.run flatMap {
            case Done() => done.run
            case One(a) => one((a, state)).run

            case More(as, next) =>
              val zipped: Vector[(A, B)] =
                as match {
                  case Nil => Vector.empty
                  case a :: rest => rest.foldLeft((Vector((a, state)), state)) { case ((ls, s), cur) =>
                    val newState = f(cur, s)
                    (ls :+ ((cur, newState)), newState)
                  }._1
                }

              (emit(zipped.toList) append zipWithState(b)(f).apply(next)).run

          }
        }
      go(producer, b)
    }

  def intersperse[M[_] : MonadDefer, A](in: A): Transducer[M, A, A] =
    (producer: Producer[M, A]) =>
      Producer[M, A](
        producer.run flatMap {
          case Done() => done.run
          case One(a) => one(a).run
          case More(Nil, next) => intersperse(in).apply(next).run
          case More(as, next) =>
            val interspersed = as.init.foldRight(as.lastOption.toList)(_ +: in +: _)

            (emit(interspersed) append
              Producer(next.run flatMap {
                case Done() => done[M, A].run
                case _ =>      (one[M, A](in) append intersperse(in).apply(next)).run
              })).run
        })

  def mapEval[M[_] : MonadDefer, A, B](f: A => M[B]): Transducer[M, A, B] = (p: Producer[M, A]) =>
    p.flatMap(a => Producer.eval[M, B](f(a)))

  private def cata_[M[_] : MonadDefer, A, B](onDone: Producer[M, B], onOne: A => Producer[M, B], onMore: (List[A], Producer[M, A]) => Producer[M, B]): Transducer[M, A, B] =
    (producer: Producer[M, A]) => cata(producer)(onDone, onOne, onMore)
}

object Transducers extends Transducers

