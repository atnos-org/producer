package org.atnos

import org.atnos.eff._

package object producer {

  type Transducer[R, A, B] = Producer[R, A] => Producer[R, B]

  object transducers extends Transducers

  implicit class ProducerOps[R, A](p: Producer[R, A]) {
    def filter(f: A => Boolean): Producer[R, A] =
      Producer.filter(p)(f)

    def chunk(n: Int): Producer[R, List[A]] =
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

  implicit class ProducerListOps[R, A](p: Producer[R, List[A]]) {
    def flattenList: Producer[R, A] =
      Producer.flattenList(p)
  }

  implicit class ProducerFlattenOps[R, A](p: Producer[R, Producer[R, A]]) {
    def flatten: Producer[R, A] =
      Producer.flatten(p)

  }

}
