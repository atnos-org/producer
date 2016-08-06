package org.atnos

import org.atnos.eff._

package object generator {

  type Consumer[R, E] = On[E] => Eff[R, Unit]
  type Producer[R, E] = Generator[R, E, Unit]
  type Transducer[R, A, B] = Producer[R, A] => Producer[R, B]

  object transducers extends Transducers

  implicit class ProducerOps[R, A](p: Producer[R, A]) {
    def filter(f: A => Boolean): Producer[R, A] =
      Generator.filter(p)(f)

    def map[B](f: A => B): Producer[R, B] =
      Generator.map(p)(f)

    def flatMap[B](f: A => Producer[R, B]): Producer[R, B] =
      Generator.flatMap(p)(f)

    def chunk(n: Int): Producer[R, List[A]] =
      Generator.chunk(n)(p)

    def >(p2: Producer[R, A]): Producer[R, A] =
      append(p2)

    def append(p2: Producer[R, A]): Producer[R, A] =
      Generator.append(p, p2)

    def |>[B](t: Transducer[R, A, B]): Producer[R, B] =
      pipe(t)

    def pipe[B](t: Transducer[R, A, B]): Producer[R, B] =
      Generator.pipe(p, t)
  }

  implicit class ProducerListOps[R, A](p: Producer[R, List[A]]) {
    def flattenList: Producer[R, A] =
      Generator.flattenList(p)
  }

  implicit class ProducerFlattenOps[R, A](p: Producer[R, Producer[R, A]]) {
    def flatten: Producer[R, A] =
      Generator.flatten(p)

  }

}
