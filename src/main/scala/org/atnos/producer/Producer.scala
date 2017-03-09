package org.atnos.producer

import cats._
import data._
import cats.implicits._
import cats.free.Free.foldLeftM
import org.atnos.eff._
import org.atnos.eff.safe._
import org.atnos.eff.writer._
import org.atnos.eff.state._
import org.atnos.eff.syntax.all._
import Producer._

case class Producer[M[_] : MonadDefer, A](run: M[Stream[M, A]]) {

  private val monad: MonadDefer[M] = MonadDefer[M]
  import monad._

  def flatMap[B](f: A => Producer[M, B]): Producer[M, B] =
    cata[M, A, B](this)(
      done[M, B],
      (a: A) => f(a),
      (as: List[A], next: Producer[M, A]) => {
        as match {
          case Nil => next.flatMap(f)
          case a :: Nil => f(a) append next.flatMap(f)
          case a :: tail => f(a) append Producer[M, A](protect(More(tail, next))).flatMap(f)
        }
      }
    )

  def map[B](f: A => B): Producer[M, B] =
    cata[M, A, B](this)(
      done[M, B],
      (a: A) => one(f(a)),
      (as: List[A], next: Producer[M, A]) => emit(as.map(f)) append next.map(f))

  def append(other: Producer[M, A]): Producer[M, A] = {
    Producer(run flatMap {
      case Done()         => other.run
      case One(a)         => protect(More(List(a), other))
      case More(as, next) => protect(More(as, next append other))
    })
  }

  def flatMapList[B](f: A => List[B]): Producer[M, B] =
    Producer(run flatMap {
      case Done() => done.run
      case One(a) => f(a) match {
        case Nil => done.run
        case bs => pure(More(bs, done))
      }
      case More(as, next) =>
        pure(More(as flatMap f, next flatMapList f))
    })

  def zip[B](other: Producer[M, B]): Producer[M, (A, B)] =
    Producer(run flatMap {
      case Done() => done.run
      case One(a) =>
        other.run flatMap {
          case Done() => done.run
          case One(b) => one((a, b)).run

          case More(bs, next) =>
            bs.headOption.map(b => one[M, (A, B)]((a, b))).getOrElse(done).run
        }

      case More(Nil, next) => done.run

      case More(as, nexta) =>
        other.run flatMap {
          case Done() => done.run
          case One(b) => as.headOption.map(a => one[M, (A, B)]((a, b))).getOrElse(done).run

          case More(bs, nextb) =>
            if (as.size == bs.size)
              emit(as zip bs).run
            else if (as.size < bs.size)
              (emit(as zip bs) append (nexta zip (emit(bs.drop(as.size)) append nextb))).run
            else
              (emit(as zip bs) append ((emit(as.drop(bs.size)) append nexta) zip nextb)).run
        }
    })

  private def protect[X](x: =>X): M[X] =
    ApplicativeEval[M].delay(x)
}


object Producer extends Producers {

  implicit def MonoidProducer[M[_] : MonadDefer, A]: Monoid[Producer[M, A]] = new Monoid[Producer[M, A]] {
    def empty: Producer[M, A] = done[M, A]
    def combine(p1: Producer[M, A], p2: Producer[M, A]): Producer[M, A] =
      p1 append p2
  }

  implicit def ProducerMonad[M[_] : MonadDefer]: Monad[Producer[M, ?]] = new Monad[Producer[M, ?]] {
    def flatMap[A, B](fa: Producer[M, A])(f: A => Producer[M, B]): Producer[M, B] =
      fa.flatMap(f)

    def pure[A](a: A): Producer[M, A] =
      one(a)

    def tailRecM[A, B](a: A)(f: A => Producer[M, Either[A, B]]): Producer[M, B] =
      flatMap(f(a)) {
        case Right(b)   => pure(b)
        case Left(next) => tailRecM(next)(f)
      }
  }

}

trait Producers {

  def MonadDeferEffSafe[R :_Safe]: MonadDefer[Eff[R, ?]] = new MonadDefer[Eff[R, ?]] {
    def pure[A](a: A): Eff[R, A] =
      Eff.pure(a)

    def flatMap[A, B](fa: Eff[R, A])(f: A => Eff[R, B]): Eff[R, B] =
      fa.flatMap(f)

    def tailRecM[A, B](a: A)(f: A => Eff[R, Either[A,B]]): Eff[R,B] =
      Eff.EffMonad[R].tailRecM(a)(f)

    def eval[A](a: Eval[A]): Eff[R, A] =
      safe.eval(a)
  }

  def done[M[_] : MonadDefer, A]: Producer[M, A] =
    Producer[M, A](MonadDefer[M].pure(Done()))

  def one[M[_] : MonadDefer, A](a: A): Producer[M, A] =
    Producer[M, A](MonadDefer[M].pure(One(a)))

  def oneEval[M[_] : MonadDefer, A](e: M[A]): Producer[M, A] =
    Producer[M, A](e.flatMap(a => one(a).run))

  def oneOrMore[M[_] : MonadDefer, A](a: A, as: List[A]): Producer[M, A] =
    Producer[M, A](MonadDefer[M].pure(More(a +: as, done)))

  def fromState[R :_Safe, A, S](state: State[S, A])(implicit m: State[S, ?] |= R): Producer[Eff[R, ?], A] = {
    implicit val monad = MonadDeferEffSafe[R]
    Producer.eval {
      for {
        s <- get[R, S]
        (s1, a) = state.run(s).value
        _ <- put[R, S](s1)
      } yield a
    }append fromState(state)
  }

  def unfoldState[M[_] : MonadDefer, A, S](s: S)(state: State[S, A]): Producer[M, A] =
    unfold(s)(s1 => Some(state.run(s1).value))

  def unfold[M[_] : MonadDefer, A, B](a: A)(f: A => Option[(A, B)]): Producer[M, B] =
    Producer[M, B](MonadDefer[M].delay {
      f(a) match {
        case Some((a1, b)) => More[M, B](List(b), unfold(a1)(f))
        case None          => Done[M, B]()
      }
    })

  def unfoldList[M[_] : MonadDefer, A, B](a: A)(f: A => Option[(A, NonEmptyList[B])]): Producer[M, B] =
    Producer[M, B](MonadDefer[M].delay {
      f(a) match {
        case Some((a1, bs)) => More[M, B](bs.toList, unfoldList(a1)(f))
        case None           => Done[M, B]()
      }
    })

  def repeat[M[_] : MonadDefer, A](p: Producer[M, A]): Producer[M, A] =
    Producer(p.run flatMap {
      case Done() => MonadDefer[M].pure(Done())
      case One(a) => MonadDefer[M].delay(More(List(a), repeat(p)))
      case More(as, next) => MonadDefer[M].delay(More(as, next append repeat(p)))
    })

  def repeatValue[M[_] : MonadDefer, A](a: A): Producer[M, A] =
    Producer(MonadDefer[M].delay(More(List(a), repeatValue(a))))

  def repeatEval[M[_] : MonadDefer, A](e: M[A]): Producer[M, A] =
    Producer(e.map(a => More(List(a), repeatEval(e))))

  def fill[M[_] : MonadDefer, A](n: Int)(p: Producer[M, A]): Producer[M, A] =
    if (n <= 0) done[M, A]
    else p append fill(n - 1)(p)

  def emit[M[_] : MonadDefer, A](elements: List[A]): Producer[M, A] =
    elements match {
      case Nil      => done[M, A]
      case a :: Nil => one[M, A](a)
      case a :: as  => oneOrMore(a, as)
    }

  def emitSeq[M[_] : MonadDefer, A](elements: Seq[A]): Producer[M, A] =
    elements.headOption match {
      case None    => done[M, A]
      case Some(a) => one[M, A](a) append emitSeq(elements.tail)
    }

  def emitIterator[M[_] : MonadDefer, A](elements: Iterator[A]): Producer[M, A] =
    if (elements.hasNext) one[M, A](elements.next) append emitIterator(elements)
    else done[M, A]

  def eval[M[_] : MonadDefer, A](a: M[A]): Producer[M, A] =
    Producer(a.map(One(_)))

  def emitEval[M[_] : MonadDefer, A](elements: M[List[A]]): Producer[M, A] =
    Producer(elements flatMap {
      case Nil      => done[M, A].run
      case a :: Nil => one(a).run
      case a :: as  => oneOrMore(a, as).run
    })

  def fold[M[_] : MonadDefer, A, B, S](producer: Producer[M, A])(start: M[S], f: (S, A) => M[S], end: S => M[B]): M[B] = {
    producer.run flatMap {
      case Done() => start.flatMap(end)
      case One(a) => start.flatMap(s1 => f(s1, a).flatMap(end))
      case More(as, next) => start.flatMap(s1 => foldLeftM(as, s1)(f).flatMap(s => fold(next)(MonadDefer[M].pure(s), f, end)))
    }
  }

  def observe[M[_] : MonadDefer, A, S](producer: Producer[M, A])(start: M[S], f: (S, A) => M[S], end: S => M[Unit]): Producer[M, A] = {
    def go(p: Producer[M, A], s: M[S]): Producer[M, A] =
      Producer[M, A] {
        p.run flatMap {
          case Done() => s.flatMap(end) >> done[M, A].run
          case One(a) => s.flatMap(end) >> one[M, A](a).run
          case More(as, next) =>
            val newS = s.flatMap(s1 => foldLeftM(as, s1)(f))
            (emit(as) append go(next, newS)).run
        }
      }
      Producer[M, A](go(producer, start).run)
  }

  def runLast[M[_] : MonadDefer, A](producer: Producer[M, A]): M[Option[A]] =
    producer.run flatMap {
      case Done() => MonadDefer[M].pure(None)
      case One(a) => MonadDefer[M].pure(Option(a))
      case More(as, next) => runLast(next).map(_.orElse(as.lastOption))
    }

  def runList[M[_] : MonadDefer, A](producer: Producer[M, A]): M[List[A]] =
    producer.fold(MonadDefer[M].pure(Vector[A]()), (vs: Vector[A], a: A) => MonadDefer[M].pure(vs :+ a), (vs:Vector[A]) => MonadDefer[M].pure(vs.toList))

  def collect[R : _Safe, A](producer: Producer[Eff[R, ?], A])(implicit m: Member[Writer[A, ?], R]): Eff[R, Unit] =
    producer.run flatMap {
      case Done() => Eff.pure(())
      case One(a) => tell(a)
      case More(as, next) => as.traverse(tell[R, A]) >> collect(next)
    }

  def into[R :_Safe, U, A](producer: Producer[Eff[R, ?], A])(implicit intoPoly: IntoPoly[R, U], s :_Safe[U]): Producer[Eff[U, ?], A] = {
    implicit val monadR = MonadDeferEffSafe[R]
    implicit val monadU = MonadDeferEffSafe[U]

    Producer(producer.run.into[U] flatMap {
      case Done() => done[Eff[U, ?], A].run
      case One(a) => one[Eff[U, ?], A](a).run
      case More(as, next) => protect(More[Eff[U, ?], A](as, into(next)))
    })
  }

  def empty[M[_] : MonadDefer, A]: Producer[M, A] =
    done

  def pipe[M[_], A, B](p: Producer[M, A], t: Transducer[M, A, B]): Producer[M, B] =
    t(p)

  def filter[M[_] : MonadDefer, A](producer: Producer[M, A])(f: A => Boolean): Producer[M, A] =
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(a) => MonadDefer[M].delay(a).as(if (f(a)) One(a) else Done())
      case More(as, next) =>
        as filter f match {
          case Nil => next.filter(f).run
          case a :: rest => (oneOrMore(a, rest) append next.filter(f)).run
        }
    })

  def flatten[M[_] : MonadDefer, A](producer: Producer[M, Producer[M, A]]): Producer[M, A] =
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(p) => p.run
      case More(ps, next) => (flattenProducers(ps) append flatten(next)).run
    })

  def flattenProducers[M[_] : MonadDefer, A](producers: List[Producer[M, A]]): Producer[M, A] =
    producers match {
      case Nil => done
      case p :: rest => p append flattenProducers(rest)
    }

  def flattenSeq[M[_] : MonadDefer, A](producer: Producer[M, Seq[A]]): Producer[M, A] =
    producer.flatMap(as => emitSeq(as.toList))

  /** accumulate chunks of size n inside More nodes */
  def chunk[M[_] : MonadDefer, A](size: Int)(producer: Producer[M, A]): Producer[M, A] =
    if (size < 1) producer
    else
      Producer[M, A](
        producer.run flatMap {
          case Done() => MonadDefer[M].pure(Done())
          case One(a) => MonadDefer[M].pure(One(a))
          case More(as, next) =>
            val (first, second) = as.splitAt(size)
            if (first.isEmpty) chunk(size)(next).run
            else if (second.isEmpty)
              (emit(first) append chunk(size)(next)).run
            else
              (emit(first) append chunk(size)(emit(second) append next)).run
        })

  def sliding[M[_] : MonadDefer, A](size: Int)(producer: Producer[M, A]): Producer[M, List[A]] = {

    def go(p: Producer[M, A], elements: Vector[A]): Producer[M, List[A]] =
      Producer[M, List[A]](
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

  def peek[M[_] : MonadDefer, A](producer: Producer[M, A]): M[(Option[A], Producer[M, A])] =
    producer.run map {
      case Done() => (None, done[M, A])
      case One(a) => (Option(a), done[M, A])
      case More(as, next) => (as.headOption, emit(as.tail) append next)
    }

  def flattenList[M[_] : MonadDefer, A](producer: Producer[M, List[A]]): Producer[M, A] =
    producer.flatMap(emit[M, A])

  def sequence[R :_Safe, F[_], A](n: Int)(producer: Producer[Eff[R, ?], Eff[R, A]]) = {
    implicit val monad = MonadDeferEffSafe[R]
    sliding(n)(producer).flatMap { actions => emitEval(Eff.sequenceA(actions)) }
  }

  private[producer] def cata[M[_] : MonadDefer, A, B](producer: Producer[M, A])(onDone: Producer[M, B], onOne: A => Producer[M, B], onMore: (List[A], Producer[M, A]) => Producer[M, B]): Producer[M, B] =
    Producer[M, B](producer.run.flatMap {
      case Done() => onDone.run
      case One(a) => onOne(a).run
      case More(as, next) => onMore(as, next).run
    })

}
