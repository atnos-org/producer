package org.atnos

import org.atnos.eff.{Eff, IntoPoly}

package object generator {

  type Consumer[R, E] = On[E] => Eff[R, Unit]
  type Producer[R, E] = Generator[R, E, Unit]

  implicit class ProducerOps[R, A](p1: Producer[R, A]) {
    def >(p2: Producer[R, A]): Producer[R, A] =
      followedBy(p2)

    def followedBy(p2: Producer[R, A]): Producer[R, A] =
      Generator.append(p1, p2)
  }
}
