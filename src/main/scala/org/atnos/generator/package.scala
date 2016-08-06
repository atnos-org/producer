package org.atnos

import org.atnos.eff.{Eff, IntoPoly}

package object generator {

  type Consumer[R, E] = On[E] => Eff[R, Unit]
  type Producer[R, E] = Generator[R, E, Unit]
}
