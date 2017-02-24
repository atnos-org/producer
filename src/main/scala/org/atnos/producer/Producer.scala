package org.atnos.producer

import cats._
import data._
import cats.implicits._
import cats.free.Free.foldLeftM
import org.atnos.eff._
import org.atnos.eff.all._
import org.atnos.eff.syntax.all._
import Producer._

case class Producer[R :_Safe, A](run: Eff[R, Stream[R, A]]) {

  def flatMap[B](f: A => Producer[R, B]): Producer[R, B] =
    cata[R, A, B](this)(
      done[R, B],
      (a: A) => f(a),
      (as: List[A], next: Producer[R, A]) => {
        as match {
          case Nil => next.flatMap(f)
          case a :: Nil => f(a) append next.flatMap(f)
          case a :: tail => f(a) append Producer[R, A](protect(More(tail, next))).flatMap(f)
        }
      }
    )

  def map[B](f: A => B): Producer[R, B] =
    cata[R, A, B](this)(
      done[R, B],
      (a: A) => one(f(a)),
      (as: List[A], next: Producer[R, A]) => emit(as.map(f)) append next.map(f))

  def append(other: Producer[R, A]): Producer[R, A] = {
    Producer(run flatMap {
      case Done()         => other.run
      case One(a)         => protect(More(List(a), other))
      case More(as, next) => protect(More(as, next append other))
    })
  }

  def flatMapList[B](f: A => List[B]): Producer[R, B] =
    Producer(run flatMap {
      case Done() => done.run
      case One(a) => f(a) match {
        case Nil => done.run
        case bs => pure(More(bs, done))
      }
      case More(as, next) =>
        pure(More(as flatMap f, next flatMapList f))
    })

  def zip[B](other: Producer[R, B]): Producer[R, (A, B)] =
    Producer(run flatMap {
      case Done() => done.run
      case One(a) =>
        other.run flatMap {
          case Done() => done.run
          case One(b) => one((a, b)).run

          case More(bs, next) =>
            bs.headOption.map(b => one[R, (A, B)]((a, b))).getOrElse(done).run
        }

      case More(Nil, next) => done.run

      case More(as, nexta) =>
        other.run flatMap {
          case Done() => done.run
          case One(b) => as.headOption.map(a => one[R, (A, B)]((a, b))).getOrElse(done).run

          case More(bs, nextb) =>
            if (as.size == bs.size)
              emit(as zip bs).run
            else if (as.size < bs.size)
              (emit(as zip bs) append (nexta zip (emit(bs.drop(as.size)) append nextb))).run
            else
              (emit(as zip bs) append ((emit(as.drop(bs.size)) append nexta) zip nextb)).run
        }
    })

  def andFinally(last: Eff[R, Unit]): Producer[R, A] =
    Producer(run.addLast(last))
}


object Producer extends Producers {

  implicit def MonoidProducer[R :_Safe, A]: Monoid[Producer[R, A]] = new Monoid[Producer[R, A]] {
    def empty: Producer[R, A] = done[R, A]
    def combine(p1: Producer[R, A], p2: Producer[R, A]): Producer[R, A] =
      p1 append p2
  }

  implicit def ProducerMonad[R :_Safe]: Monad[Producer[R, ?]] = new Monad[Producer[R, ?]] {
    def flatMap[A, B](fa: Producer[R, A])(f: A => Producer[R, B]): Producer[R, B] =
      fa.flatMap(f)

    def pure[A](a: A): Producer[R, A] =
      one(a)

    def tailRecM[A, B](a: A)(f: A => Producer[R, Either[A, B]]): Producer[R, B] =
      flatMap(f(a)) {
        case Right(b)   => pure(b)
        case Left(next) => tailRecM(next)(f)
      }
  }

}

trait Producers {
  def done[R :_Safe, A]: Producer[R, A] =
    Producer[R, A](pure(Done()))

  def one[R :_Safe, A](a: A): Producer[R, A] =
    Producer[R, A](pure(One(a)))

  def oneEff[R :_Safe, A](e: Eff[R, A]): Producer[R, A] =
    Producer[R, A](e.flatMap(a => one(a).run))

  def oneOrMore[R :_Safe, A](a: A, as: List[A]): Producer[R, A] =
    Producer[R, A](pure(More(a +: as, done)))

  def fromState[R :_Safe, A, S](state: State[S, A])(implicit m: State[S, ?] |= R): Producer[R, A] = {
    Producer.eval {
      for {
        s <- get[R, S]
        (s1, a) = state.run(s).value
        _ <- put[R, S](s1)
      } yield a
    } append fromState(state)
  }

  def unfoldState[R :_Safe, A, S](s: S)(state: State[S, A]): Producer[R, A] =
    unfold(s)(s1 => Some(state.run(s1).value))

  def unfold[R :_Safe, A, B](a: A)(f: A => Option[(A, B)]): Producer[R, B] =
    Producer[R, B](protect {
      f(a) match {
        case Some((a1, b)) => More[R, B](List(b), unfold(a1)(f))
        case None          => Done[R, B]()
      }
    })

  def unfoldList[R :_Safe, A, B](a: A)(f: A => Option[(A, NonEmptyList[B])]): Producer[R, B] =
    Producer[R, B](protect {
      f(a) match {
        case Some((a1, bs)) => More[R, B](bs.toList, unfoldList(a1)(f))
        case None           => Done[R, B]()
      }
    })

  def repeat[R :_Safe, A](p: Producer[R, A]): Producer[R, A] =
    Producer(p.run flatMap {
      case Done() => pure(Done())
      case One(a) => protect(More(List(a), repeat(p)))
      case More(as, next) => protect(More(as, next append repeat(p)))
    })

  def repeatValue[R :_Safe, A](a: A): Producer[R, A] =
    Producer(protect(More(List(a), repeatValue(a))))

  def repeatEval[R :_Safe, A](e: Eff[R, A]): Producer[R, A] =
    Producer(e.map(a => More(List(a), repeatEval(e))))

  def fill[R :_Safe, A](n: Int)(p: Producer[R, A]): Producer[R, A] =
    if (n <= 0) done[R, A]
    else p append fill(n - 1)(p)

  def emit[R :_Safe, A](elements: List[A]): Producer[R, A] =
    elements match {
      case Nil      => done[R, A]
      case a :: Nil => one[R, A](a)
      case a :: as  => oneOrMore(a, as)
    }

  def emitSeq[R :_Safe, A](elements: Seq[A]): Producer[R, A] =
    elements.headOption match {
      case None    => done[R, A]
      case Some(a) => one[R, A](a) append emitSeq(elements.tail)
    }

  def emitIterator[R :_Safe, A](elements: Iterator[A]): Producer[R, A] =
    if (elements.hasNext) one[R, A](elements.next) append emitIterator(elements)
    else done[R, A]

  def eval[R :_Safe, A](a: Eff[R, A]): Producer[R, A] =
    Producer(a.map(One(_)))

  def emitEff[R :_Safe, A](elements: Eff[R, List[A]]): Producer[R, A] =
    Producer(elements flatMap {
      case Nil      => done[R, A].run
      case a :: Nil => one(a).run
      case a :: as  => oneOrMore(a, as).run
    })

  def fold[R :_Safe, A, B, S](producer: Producer[R, A])(start: Eff[R, S], f: (S, A) => Eff[R, S], end: S => Eff[R, B]): Eff[R, B] = {
    producer.run flatMap {
      case Done() => start.flatMap(end)
      case One(a) => start.flatMap(s1 => f(s1, a).flatMap(end))
      case More(as, next) => start.flatMap(s1 => foldLeftM(as, s1)(f).flatMap(s => fold(next)(pure(s), f, end)))
    }
  }

  def observe[R :_Safe, A, S](producer: Producer[R, A])(start: Eff[R, S], f: (S, A) => Eff[R, S], end: S => Eff[R, Unit]): Producer[R, A] = {
    def go(p: Producer[R, A], s: Eff[R, S]): Producer[R, A] =
      Producer[R, A] {
        p.run flatMap {
          case Done() => s.flatMap(end) >> done[R, A].run
          case One(a) => s.flatMap(end) >> one[R, A](a).run
          case More(as, next) =>
            val newS = s.flatMap(s1 => foldLeftM(as, s1)(f))
            (emit(as) append go(next, newS)).run
        }
      }
      Producer[R, A](go(producer, start).run)
  }

  def runLast[R :_Safe, A](producer: Producer[R, A]): Eff[R, Option[A]] =
    producer.run flatMap {
      case Done() => pure[R, Option[A]](None)
      case One(a) => pure[R, Option[A]](Option(a))
      case More(as, next) => runLast(next).map(_.orElse(as.lastOption))
    }

  def runList[R :_Safe, A](producer: Producer[R, A]): Eff[R, List[A]] =
    producer.fold(pure(Vector[A]()), (vs: Vector[A], a: A) => pure(vs :+ a), (vs:Vector[A]) => pure(vs.toList))

  def collect[R :_Safe, A](producer: Producer[R, A])(implicit m: Member[Writer[A, ?], R]): Eff[R, Unit] =
    producer.run flatMap {
      case Done() => pure(())
      case One(a) => tell(a)
      case More(as, next) => as.traverse(tell[R, A]) >> collect(next)
    }

  def into[R, U, A](producer: Producer[R, A])(implicit intoPoly: IntoPoly[R, U], s: _Safe[U]): Producer[U, A] =
    Producer(producer.run.into[U] flatMap {
      case Done() => done[U, A].run
      case One(a) => one[U, A](a).run
      case More(as, next) => protect(More[U, A](as, into(next)))
    })

  def empty[R :_Safe, A]: Producer[R, A] =
    done

  def pipe[R, A, B](p: Producer[R, A], t: Transducer[R, A, B]): Producer[R, B] =
    t(p)

  def filter[R :_Safe, A](producer: Producer[R, A])(f: A => Boolean): Producer[R, A] =
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(a) => protect[R, A](a).as(if (f(a)) One(a) else Done())
      case More(as, next) =>
        as filter f match {
          case Nil => next.filter(f).run
          case a :: rest => (oneOrMore(a, rest) append next.filter(f)).run
        }
    })

  def flatten[R :_Safe, A](producer: Producer[R, Producer[R, A]]): Producer[R, A] =
    Producer(producer.run flatMap {
      case Done() => done.run
      case One(p) => p.run
      case More(ps, next) => (flattenProducers(ps) append flatten(next)).run
    })

  def flattenProducers[R :_Safe, A](producers: List[Producer[R, A]]): Producer[R, A] =
    producers match {
      case Nil => done
      case p :: rest => p append flattenProducers(rest)
    }

  def flattenSeq[R :_Safe, A](producer: Producer[R, Seq[A]]): Producer[R, A] =
    producer.flatMap(as => emitSeq(as.toList))

  /** accumulate chunks of size n inside More nodes */
  def chunk[R :_Safe, A](size: Int)(producer: Producer[R, A]): Producer[R, A] =
    if (size < 1) producer
    else
      Producer[R, A](
        producer.run flatMap {
          case Done() => pure(Done())
          case One(a) => pure(One(a))
          case More(as, next) =>
            val (first, second) = as.splitAt(size)
            if (first.isEmpty) chunk(size)(next).run
            else if (second.isEmpty)
              (emit(first) append chunk(size)(next)).run
            else
              (emit(first) append chunk(size)(emit(second) append next)).run
        })

  def sliding[R :_Safe, A](size: Int)(producer: Producer[R, A]): Producer[R, List[A]] = {

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

  def peek[R :_Safe, A](producer: Producer[R, A]): Eff[R, (Option[A], Producer[R, A])] =
    producer.run map {
      case Done() => (None, done[R, A])
      case One(a) => (Option(a), done[R, A])
      case More(as, next) => (as.headOption, emit(as.tail) append next)
    }

  def flattenList[R :_Safe, A](producer: Producer[R, List[A]]): Producer[R, A] =
    producer.flatMap(emit[R, A])

  def sequence[R :_Safe, F[_], A](n: Int)(producer: Producer[R, Eff[R, A]]) =
    sliding(n)(producer).flatMap { actions => emitEff(Eff.sequenceA(actions)) }

  private[producer] def cata[R :_Safe, A, B](producer: Producer[R, A])(onDone: Producer[R, B], onOne: A => Producer[R, B], onMore: (List[A], Producer[R, A]) => Producer[R, B]): Producer[R, B] =
    Producer[R, B](producer.run.flatMap {
      case Done() => onDone.run
      case One(a) => onOne(a).run
      case More(as, next) => onMore(as, next).run
    })

}
