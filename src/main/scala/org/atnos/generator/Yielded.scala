package org.atnos.generator

import org.atnos.eff._

object Yielded {

  type GenT[E, M[_]] = Eff[Reader[Consumer[M[_], E]]]
  type Consumer[M[_], E] =



}
