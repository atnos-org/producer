package org.atnos

import org.atnos.eff.Eff

package object generator {

  type Consumer[R, E] = On[E] => Eff[R, Unit]
  type Producer[E] = Generator[E, Unit]


}
